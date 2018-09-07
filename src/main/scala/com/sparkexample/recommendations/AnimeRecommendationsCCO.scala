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
      .filter(_(0) != "user_id")
      .filter(line_array => line_array(2).toInt > 7) // select rating > 7 only
      .map(rating => (rating(1), rating(0))) //(anime_id, user_id)

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

    val genreMap = userGenreRDD
      .map {case (user_id, genre) => genre}
      // try to avoid using distict() in cluster mode. Distinct moves all data to one executor where performance problems may happen.
      .distinct()
      .zipWithIndex() // (genre, index)
      .mapValues(idx => idx.toString())
      .collect()
      .toMap

    // broadcast genreMap to each executor.
    val genreMapBc = sc.broadcast(genreMap)

    // convert genre texts to digits.
    val userGenreIdRDD = userGenreRDD.mapValues(genre => genreMapBc.value(genre))

    val userAnimeIDS = IndexedDatasetSpark.apply(userAnimeIdRDD)(sc)
    val userGenreIDS = IndexedDatasetSpark.apply(userGenreIdRDD)(sc)

    return (userAnimeIDS, userGenreIDS, genreMapBc)
  }

  /** Our main function where the action happens */
  def main(args: Array[String]) {

    // Set the log level to only print errors
    Logger.getLogger("org").setLevel(Level.ERROR)

    val sparkConf = new SparkConf()
        .set("spark.kryo.registrator", "org.apache.mahout.sparkbindings.io.MahoutKryoRegistrator")
        .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        .set("spark.kryo.referenceTracking", "false")
        .set("spark.kryoserializer.buffer", "32k")
        .set("spark.kryoserializer.buffer.max", "1g")
        //.set("spark.default.parallelism", "10")
        //.set("spark.executor.memory", "1G")

    val sc = new SparkContext(sparkConf)
    implicit val sdc: org.apache.mahout.sparkbindings.SparkDistributedContext = sc2sdc(sc)

    println("loading anime data...")
    val (userAnimeIDS, userGenreIDS, genreMap) = loadAnimeRatingData(sdc)

    println("calculating similarities...")
    // Now we compute our co-occurrence matrices
    val userAnimeGenreCoOccuList = SimilarityAnalysis.cooccurrencesIDSs(
      Array(userAnimeIDS, userGenreIDS),
      maxInterestingItemsPerThing = 20,
      maxNumInteractions = 500,
      randomSeed = 1234)

    // set my favourites
    val myAnimes = svec(
      (userAnimeIDS.columnIDs.get("1").get, 1) :: // 1 = Cowboy Bebop
        (userAnimeIDS.columnIDs.get("7").get, 1) :: // 7 = Witch Hunter Robin
        (userAnimeIDS.columnIDs.get("19").get, 1) :: // 19 = Monster
        (userAnimeIDS.columnIDs.get("30").get, 1) :: // 30 = Neon Genesis Evangelion
        (userAnimeIDS.columnIDs.get("43").get, 1) :: // 43 = Ghost in the Shell
        (userAnimeIDS.columnIDs.get("71").get, 1) :: // 71 = Full Metal Panic!
        (userAnimeIDS.columnIDs.get("317").get, 1) :: // 317 = Final Fantasy VII: Advent Children
        (userAnimeIDS.columnIDs.get("527").get, 1) :: // 527 = Pokemon
        (userAnimeIDS.columnIDs.get("732").get, 1) :: // 732 = Vampire Hunter D
        (userAnimeIDS.columnIDs.get("1364").get, 1) :: // 1364 = Detective Conan Movie 05: Countdown to Heaven
        (userAnimeIDS.columnIDs.get("4792").get, 1) :: // 4792 = Pokemon: Pikachu Tankentai
      Nil,
      cardinality = userAnimeIDS.columnIDs.size
    )
    val myGrenres = svec(
      (userGenreIDS.columnIDs.get(genreMap.value("Action")).get, 1) ::
        (userGenreIDS.columnIDs.get(genreMap.value("Fantasy")).get, 1) ::
        (userGenreIDS.columnIDs.get(genreMap.value("Adventure")).get, 1) ::
        (userGenreIDS.columnIDs.get(genreMap.value("Shounen")).get, 1) ::
        (userGenreIDS.columnIDs.get(genreMap.value("SuperPower")).get, 1) ::
        (userGenreIDS.columnIDs.get(genreMap.value("Comedy")).get, 1) ::
        (userGenreIDS.columnIDs.get(genreMap.value("Supernatural")).get, 1) ::
        (userGenreIDS.columnIDs.get(genreMap.value("Drama")).get, 1) ::
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
