package io.github.benfradet.spark.ml.in.action

import org.apache.spark.sql.{SparkSession, Row, SaveMode}
import org.apache.spark.sql.functions._

object DataPreparation {
  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      System.err.println("Usage: DataPreparation <input file> <output file>")
      System.exit(1)
    }

    val spark = SparkSession
      .builder()
      .appName("Data preparation for chapter 7")
      .getOrCreate()
    import spark.implicits._

    val inputPath = args(0)
    val events = spark.read.json(inputPath)
    events.printSchema()
    events.show(5, truncate = false)

    val splitEventUDF = udf(splitEvent)
    val projectedEvents = events.select(
      $"actor.login".alias("username"),
      splitEventUDF($"type", $"payload").alias("type"),
      lit(1L).alias("count")
    )
    projectedEvents.printSchema()
    projectedEvents.show(5, truncate = false)

    val distinctEventTypes = projectedEvents
      .select("type")
      .distinct()
      .map(_.getString(0))
      .collect()
    val pivotedEvents = projectedEvents
      .groupBy("username")
      .pivot("type", distinctEventTypes)
      .sum("count")
      .na.fill(0L)
    pivotedEvents.printSchema()
    pivotedEvents.show(5, truncate = false)

    val outputPath = args(1)
    pivotedEvents
      .drop("username")
      .write
      .format("csv")
      .option("header", "true")
      .mode(SaveMode.Overwrite)
      .save(outputPath)

    spark.stop()
  }

  val splitEvent = (evtType: String, payload: Row) => {
    val getEvent = (evt: String, subEvt: String) => subEvt.capitalize + evt

    val refTypeEvents = Set("CreateEvent", "DeleteEvent")
    val actionEvents = Set("IssuesEvent", "PullRequestEvent", "IssueCommentEvent",
      "PullRequestReviewCommentEvent", "RepositoryEvent")

    evtType match {
      case s if refTypeEvents.contains(s) =>
        getEvent(s, payload.getAs[String]("ref_type"))
      case s if actionEvents.contains(s) =>
        getEvent(s, payload.getAs[String]("action"))
      case "WatchEvent" => "StarEvent"
      case other => other
    }
  }
}
