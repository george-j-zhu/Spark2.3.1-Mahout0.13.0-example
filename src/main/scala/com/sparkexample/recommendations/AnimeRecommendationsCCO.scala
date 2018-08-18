package com.sparkexample.recommendations

import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.broadcast.Broadcast
import org.apache.log4j._
import scala.io.Source
import java.nio.charset.CodingErrorAction
import scala.io.Codec
import org.apache.mahout.sparkbindings.indexeddataset.IndexedDatasetSpark
import org.apache.mahout.math.cf.SimilarityAnalysis
import org.apache.mahout.sparkbindings._
import org.apache.mahout.math.drm.DistributedContext
import org.apache.mahout.math._
import scalabindings._
import org.apache.mahout.math.drm._
import org.apache.mahout.math.scalabindings.RLikeOps._
import org.apache.mahout.math.drm.RLikeDrmOps._
import org.apache.mahout.math.scalabindings.MahoutCollections._
import collection._
import JavaConversions._

object AnimeRecommendationsCCO {

  def loadAnimeRatingData(sc: SparkDistributedContext):
      (IndexedDatasetSpark, IndexedDatasetSpark, Broadcast[scala.collection.immutable.Map[String, String]]) = {

    // Handle character encoding issues:
    implicit val codec = Codec("UTF-8")
    codec.onMalformedInput(CodingErrorAction.REPLACE)
    codec.onUnmappableCharacter(CodingErrorAction.REPLACE)

    val animeUserRDD = sc.textFile("/opt/nfs/rating.csv")
      .map(line => line.split(","))
      .map(rating => (rating(1), rating(0))) //(anime_id, user_id)
      .filter(_._1 != "anime_id")

    val animeGenreRDD = sc.textFile("/opt/nfs/anime.csv")
      .map(line => line.split(","))
      .map(anime => (anime(0), anime(2))) //(anime_id, genre)
      .filter(_._1 != "anime_id")

    val animeRDD = animeUserRDD.join(animeGenreRDD) //(anime_id, (user_id, genre))

    val userAnimeIdRDD = animeRDD
      .map {case (anime_id, (user_id, genre)) => (user_id, anime_id)} // (user_id, anime_id)
    val userGenreRDD = animeRDD
      .map {case (anime_id, (user_id, genre)) => (user_id, genre)} // (user_id, genre)
      .flatMapValues(genre => genre.split(","))
      .mapValues(genre => genre.replaceAll("\"|\\s", ""))

    // broadcast genreMap to each executor.
    val genreMap = sc.broadcast(userGenreRDD
      .map {case (user_id, genre) => genre}
      .distinct()
      .zipWithIndex() // (genre, index)
      .mapValues(idx => idx.toString())
      .collect
      .toMap
    )

    // convert genre texts to digits.
    val userGenreIdRDD = userGenreRDD.mapValues(genre => genreMap.value(genre))

    val userAnimeIDS = IndexedDatasetSpark.apply(userAnimeIdRDD)(sc)
    val userGenreIDS = IndexedDatasetSpark.apply(userGenreIdRDD)(sc)

    return (userAnimeIDS, userGenreIDS, genreMap)
  }

  /** Our main function where the action happens */
  def main(args: Array[String]) {

    // Set the log level to only print errors
    Logger.getLogger("org").setLevel(Level.ERROR)

    // Spark Distributed Context
    implicit var sdc = mahoutSparkContext(
      masterUrl = args(0),
      appName = "AnimeRecommendationsCCO",
      sparkConf = new SparkConf()
          .set("spark.kryoserializer.buffer.mb", "40")
          .set("spark.kryoserializer.buffer", "40")
          .set("spark.akka.frameSize", "30")
          .set("spark.default.parallelism", "10")
          .set("spark.executor.memory", "2G")
    )

    println("loading anime data...")
    val (userAnimeIDS, userGenreIDS, genreMap) = loadAnimeRatingData(sdc)

    // Now we compute our co-occurrence matrices
    val userAnimeGenreCoOccuList = SimilarityAnalysis.cooccurrencesIDSs(
      Array(userAnimeIDS, userGenreIDS),
      maxInterestingItemsPerThing = 20,
      maxNumInteractions = 500,
      randomSeed = 1234)

    // set my favourites
    val myAnimes = svec(
      (userAnimeIDS.columnIDs.get("5114").get, 1) :: // 5114 = Fullmetal Alchemist: Brotherhood
      (userAnimeIDS.columnIDs.get("11061").get, 1) :: // 11061 = Hunter x Hunter (2011)
      Nil,
      cardinality = userAnimeIDS.columnIDs.size
    )
    val myGrenres = svec(
      (userGenreIDS.columnIDs.get(genreMap.value("Action")).get, 1) ::
      (userGenreIDS.columnIDs.get(genreMap.value("Fantasy")).get, 1) ::
      Nil,
      cardinality = userGenreIDS.columnIDs.size
    )

    // make recommendations
    val recommendations = (userAnimeGenreCoOccuList(0).matrix %*% myAnimes +
      userAnimeGenreCoOccuList(1).matrix %*% myGrenres).collect

    println(recommendations(::, 0).toMap.toList.sortWith(_._2 > _._2).take(5))

    sdc.close()
  }
}
