package com.scality.clueso.tools

import java.io.File
import java.util.UUID

import com.scality.clueso.{CluesoConfig, CluesoConstants, PathUtils, SparkUtils}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema

import scala.util.Random

object LandingMetadataPopulatorTool extends LazyLogging {

  def main(args: Array[String]): Unit = {
    require(args.length == 4, "Usage: ./landing-populator-tool.sh application.conf <bucketName> <num records> <num parquet files>")

    val parsedConfig = ConfigFactory.parseFile(new File(args(0)))
    val _config = ConfigFactory.load(parsedConfig)

    implicit val config = new CluesoConfig(_config)

    val spark = SparkUtils.buildSparkSession(config)
      .master("local[*]")
      .appName("")
      .getOrCreate()

    val bucketName = args(1)
    val totalNumRecords = args(2).toLong
    val totalNumFiles = args(3).toLong

    val fs = SparkUtils.buildHadoopFs(config)

    val landingBucketPath = s"${PathUtils.landingURI}/bucket=$bucketName"

    if (fs.exists(new Path(s"$landingBucketPath/"))) {
      logger.info(s"Deleting landing path: $landingBucketPath")
      fs.delete(new Path(landingBucketPath), true)
    }

    val numRecordsPerPartition = List.fill(Math.max(totalNumFiles - 1, 0).toInt)(totalNumRecords / totalNumFiles) ++
      Array(totalNumRecords / totalNumFiles + totalNumRecords % totalNumFiles)

    val partitionsRdd = spark.sparkContext.parallelize(numRecordsPerPartition, totalNumFiles.toInt)

    val generatedData = partitionsRdd.mapPartitions(it => {
      val prefix = UUID.randomUUID().toString.substring(0, 4)

      val numRecords = it.next.toInt
      (1 to numRecords).map { recordNo =>
        val key = s"${prefix}_${recordNo}"
        val food = if (Random.nextBoolean()) { "pizza" } else { "pasta" }
        val userMd = Map("x-amz-meta-food" -> food, "x-amz-meta-random" -> Random.nextInt(10).toString)

        //        val eventType = if (Random.nextBoolean()) { "delete" } else { "put" }
        val eventType = "put"

        val message = new GenericRowWithSchema(Array(
          userMd, bucketName, "", key, key,
          new GenericRowWithSchema(Array("private", Array[String](), Array[String](), Array[String](), Array[String]()), CluesoConstants.eventAclSchema),
          Array(), // locations
          Map[String, String](), // tags
          new GenericRowWithSchema(Array("", Array[String](), "", "", ""), CluesoConstants.replicationInfoSchema), // replicationInfo
          1, // md-model-version
          "", // owner-display-name", StringType)
          "", // owner-id", StringType)
          Random.nextInt(200), // content-length", IntegerType)
          "", // content-type
          "2017-08-08T03:57:02.249Z", // last-modified", StringType)
          "4b02d12ad7f063d67aec9dc2116a57a2", // content-md5
          "", // x-amz-server-version-id
          "", // x-amz-storage-class
          "", // x-amz-server-side-encryption
          "", // x-amz-server-side-encryption-aws-kms-key-id
          "", // x-amz-server-side-encryption-customer-algorithm
          "", // x-amz-website-redirect-location
          false, // isDeleteMarker
          "" // x-amz-version-id
        ), CluesoConstants.eventValueSchema)

        val opIndex = "%012d_%d".format(recordNo,Random.nextInt(200))
        val values : Array[Any] = Array(bucketName, key, opIndex, eventType, message)

        new GenericRowWithSchema(values, CluesoConstants.storedEventSchema).asInstanceOf[Row]

      }.iterator
    })

    spark.createDataFrame(generatedData, CluesoConstants.storedEventSchema)
      .write
      .parquet(landingBucketPath)
  }
}
