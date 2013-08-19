package com.github.philcali.aws

import sbt._
import Keys._
import complete.DefaultParsers._
import complete.Parser

import com.amazonaws.auth.{
  AWSCredentials,
  BasicAWSCredentials
}
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{
  Reservation,
  Image,
  Filter,
  RunInstancesRequest,
  DescribeAddressesRequest,
  DescribeImagesRequest => ImageRequest,
  DescribeInstancesRequest,
  TerminateInstancesRequest
}

import collection.JavaConversions._
import com.mongodb.casbah.Imports._
import util.parsing.json.{
  JSON,
  JSONFormat,
  JSONObject,
  JSONArray,
  JSONType
}

object Plugin extends sbt.Plugin {

  type ConfiguredRun = Image => RunInstancesRequest
  type InstanceFormat = Reservation => JSONType

  trait NamedExecution[A,B] {
    def name: String
    def execute: A => B
  }

  trait NamedRequest extends NamedExecution[ImageRequest, ImageRequest]
  trait JSONRequestExecution extends NamedRequest {
    def source: String

    def flattenJson(request: ImageRequest, json: Any): ImageRequest = json match {
      case JSONArray(list) => (request /: list)(flattenJson(_, _))
      case JSONObject(obj) =>
        val withOwner = (request /: obj.get("owners"))({
          case (req, JSONArray(list)) =>
            (req /: list.map(_.toString))(_.withOwners(_))
          case (req, owners: String) =>
            (req /: owners.split(","))(_.withOwners(_))
        })
        (withOwner /: obj.get("filters"))({
          case (req, JSONArray(list)) =>
            (req /: list.map(l => new Filter(l.toString)))(_.withFilters(_))
          case (req, filters: String) =>
            (req /: filters.split(",").map(f => new Filter(f)))(_.withFilters(_))
        })
    }

    def execute = {
      (request => {
        val json = JSON.parseRaw(source)
        (request /: json)(flattenJson(_, _))
      })
    }
  }

  case class NamedAwsAction(name: String, execute: String => Unit) extends NamedExecution[String,Unit]
  case class NamedAwsRequest(name: String, execute: ImageRequest => ImageRequest) extends NamedRequest
  case class JSONAwsFileRequest(name: String) extends JSONRequestExecution {
    def source = IO.read(file(name + ".json"))
  }

  object aws {

    lazy val key = SettingKey[String]("aws-key", "The AWS key")
    lazy val secret = SettingKey[String]("aws-secret", "The AWS secret")
    lazy val credentials = SettingKey[AWSCredentials]("aws-credentials", "The AWS credentials")
    lazy val client = SettingKey[AmazonEC2Client]("aws-client", "The AWS EC2 client")

    lazy val configuredInstance = SettingKey[ConfiguredRun]("aws-configured-instance")

    lazy val jsonFormat = SettingKey[JSONFormat.ValueFormatter]("aws-json-format")
    lazy val instanceFormat = SettingKey[InstanceFormat]("aws-instance-format")

    lazy val actions = TaskKey[Seq[NamedAwsAction]]("aws-actions")
    lazy val requests = SettingKey[Seq[NamedRequest]]("aws-requests")
    lazy val run = InputKey[Unit]("aws-run", "Run a configured AWS action")

    object mongo {

      lazy val url = SettingKey[MongoClientURI]("aws-mongo-uri", "Configured Mongo client URI")
      lazy val addresses = SettingKey[List[ServerAddress]]("aws-mongo-addresses", "Mongo connection Server Addresses")

      lazy val client = SettingKey[MongoClient]("aws-mongo-client", "Configured Mongo Client")
      lazy val db = SettingKey[String]("aws-mongo-db", "Mongo DB Name")
      lazy val collectionName = SettingKey[String]("aws-mongo-collection-name")
      lazy val collection = SettingKey[MongoCollection]("aws-mongo-collection")
    }
  }

  private val actionParser: Project.Initialize[State => Parser[(String,String)]] =
    (aws.requests) {
      (requests) => {
        (state: State) =>
        (Space ~> (StringBasic <~ Space) ~ requests.map(a => token(a.name)).reduceLeft(_ | _))
      }
    }

  private val runActionTask = (parsed: TaskKey[(String,String)]) => {
    (parsed, aws.actions) map {
      case ((name, input), actions) =>
        actions.find(_.name == name).foreach(_.execute(input))
    }
  }

  def createInstance(client: AmazonEC2Client, collection: MongoCollection, request: RunInstancesRequest) {
    client
      .runInstances(request)
      .getReservation()
      .getInstances() foreach { instance =>
        val dbObj = MongoDBObject("instanceId" -> instance.getInstanceId())
        collection += collection.findOne(dbObj).getOrElse(dbObj)
      }
  }

  def mongoSettings: Seq[Setting[_]] = Seq(
    aws.mongo.client := MongoClient(),
    aws.mongo.db := "sbt-aws-environment",
    aws.mongo.collectionName := "instances",
    aws.mongo.collection <<= (aws.mongo.client, aws.mongo.db, aws.mongo.collectionName)(_(_)(_))
  )

  def awsSettings: Seq[Setting[_]] = mongoSettings ++ Seq(
    aws.credentials <<= (aws.key, aws.secret) (new BasicAWSCredentials(_, _)),
    aws.client <<= aws.credentials (new AmazonEC2Client(_)),

    aws.jsonFormat := JSONFormat.defaultFormatter,
    aws.instanceFormat := (reservation => {
      JSONArray(reservation.getInstances().map { instance =>
        JSONObject(Map(
          "instance-id" -> instance.getInstanceId(),
          "image-id" -> instance.getImageId(),
          "type" -> instance.getInstanceType(),
          "key-name" -> instance.getKeyName(),
          "state" -> instance.getState().getName()
        ).filter(_._2 != null))
      }.toList)
    }),

    aws.configuredInstance := (image =>
      new RunInstancesRequest()
        .withImageId(image.getImageId)
        .withMaxCount(1)
        .withMinCount(1)
        .withInstanceType("t1.micro")
        .withSecurityGroups("default")
    ),

    aws.requests := Seq(JSONAwsFileRequest("local")),

    aws.actions <<= (
      aws.client,
      aws.mongo.collection,
      aws.requests,
      aws.configuredInstance,
      aws.jsonFormat,
      aws.instanceFormat,
      streams
    ) map {
      (client, collection, requests, configure, jsonFormat, instanceFormat, s) => Seq(
        // Creates an environment
        NamedAwsAction("create", { input =>
          val request = requests
            .find(_.name == input)
            .orElse({
              Some(
                NamedAwsRequest("", (_.withFilters(new Filter(input))))
              )
            })
            .map(_.execute(new ImageRequest()))
          client.describeImages(request.get).getImages.foreach { image =>
            createInstance(client, collection, configure(image))
          }
        }),
        // Checks on the environment status
        NamedAwsAction("status", { _ =>
          val request = (new DescribeInstancesRequest() /: collection)((r, o) => r.withInstanceIds(o.as[String]("instanceId")))
          s.log.info(JSONArray(client.describeInstances(request).getReservations().map(instanceFormat).toList).toString(jsonFormat))
        }),
        // Terminates the local environment
        NamedAwsAction("terminate", { _ =>
          val request = new TerminateInstancesRequest(collection.map(_.as[String]("instanceId")).toList)
          client.terminateInstances(request).getTerminatingInstances().foreach { instance =>
            s.log.success("%s: %s => %s" format (
              instance.getInstanceId(),
              instance.getPreviousState(),
              instance.getCurrentState()
            ))
          }
          s.log.info("Clearing local instance collection")
          collection.drop()
        })
      )
    },

    aws.run <<= InputTask(actionParser)(runActionTask)
  )
}
