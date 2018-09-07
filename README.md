# Recommendation example with Apache Spark2.3.1 and Apache Mahout0.13.0

As Apache Mahout0.13.0 is build on Scala2.10 which is no longer supported in Apache Spark2.x.
we need to build mahout 0.13.0 in order to run with scala2.11 and Spark2.x.

This is an example for Correlated Cross-Occurrence algorithm playing Apache Mahout0.13.0 with the latest Apache Spark.
I will update this project when Apache Mahout0.14.0 comes up.

## Build Mahout0.13.0
<pre>
$ git clone http://github.com/apache/mahout
$ cd mahout
$ mvn clean install -Pscala-2.11,spark-2.1 -DskipTests
</pre>
or just download prebuilt mahout with Scala2.11 from<br>
[https://github.com/heroku/predictionio-buildpack/tree/master/repo/org/apache/mahout](https://github.com/heroku/predictionio-buildpack/tree/master/repo/org/apache/mahout)

## Build this repository
```
$ mvn clean scala:compile package
```

## Set up your datasources
Copy all datasources from [kaggle](https://www.kaggle.com/CooperUnion/anime-recommendations-database) to /opt/nfs.

You need to set up your NFS server and nfs directory as /opt/nfs when your spark is in cluster mode.

## Run the driver program on your local machine
```
$ mvn exec:exec@run-local
```

## Run the driver program on your Spark Cluster
```
$ mvn exec:exec@run-cluster
```

Confirm that your master node has a hostname as 'master' or you need to change the master url specifiled in pom.xml
