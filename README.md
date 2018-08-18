# Recommendation example with Apache Spark2.3.1 and Apache Mahout0.13.0

As Apache Mahout0.13.0 is build on Scala2.10 which is no longer supported in Apache Spark since Ver2.0 and Mahout0.14.0 is still on the way,
this is an example for playing Apache Mahout0.13.0 with the latest Apache Spark.
I use Apache Mahout just as a distributed linear algebra here.

I will update this project when Apache Mahout0.14.0 comes up.

## Build the project
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
