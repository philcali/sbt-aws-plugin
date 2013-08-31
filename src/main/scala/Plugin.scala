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
  JSONFormat,
  JSONObject,
  JSONArray,
  JSONType
}

import com.decodified.scalassh.SSH
import com.decodified.scalassh.SshClient
import com.decodified.scalassh.HostConfigProvider
import com.decodified.scalassh.HostFileConfig

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Plugin extends sbt.Plugin {

  type InstanceCallback = MongoDBObject => Unit
  type ConfiguredRun = (String, Image) => RunInstancesRequest
  type InstanceFormat = Reservation => JSONType

  case class NamedSSHScript(name: String, description: String = "", execute: SshClient => Unit) extends NamedExecution[SshClient,Unit]
  case class NamedAwsAction(name: String, description: String = "", execute: String => Unit) extends NamedExecution[String,Unit]
  case class NamedAwsRequest(name: String, execute: ImageRequest => ImageRequest) extends NamedRequest
  case class JSONAwsFileRequest(name: String, base: File) extends JSONRequestExecution {
    def source = IO.read(base / (name + ".json"))
  }

  object aws {
    lazy val key = SettingKey[String]("aws-key", "The AWS key")
    lazy val secret = SettingKey[String]("aws-secret", "The AWS secret")
    lazy val credentials = SettingKey[AWSCredentials]("aws-credentials", "The AWS credentials")
    lazy val client = SettingKey[AmazonEC2Client]("aws-client", "The AWS EC2 client")

    lazy val configuredInstance = SettingKey[ConfiguredRun]("aws-configured-instance")

    lazy val jsonFormat = SettingKey[JSONFormat.ValueFormatter]("aws-json-format")
    lazy val instanceFormat = SettingKey[InstanceFormat]("aws-instance-format")

    lazy val requestDir = SettingKey[File]("aws-request-dir")
    lazy val requests = SettingKey[Seq[NamedRequest]]("aws-requests")
    lazy val created = TaskKey[InstanceCallback]("aws-created")
    lazy val finished = TaskKey[InstanceCallback]("aws-finished")
    lazy val actions = TaskKey[Seq[NamedAwsAction]]("aws-actions")
    lazy val listActions = TaskKey[Unit]("aws-list-actions")
    lazy val run = InputKey[Unit]("aws-run", "Run a configured AWS action")

    object mongo {
      lazy val url = SettingKey[MongoClientURI]("aws-mongo-uri", "Configured Mongo client URI")
      lazy val addresses = SettingKey[List[ServerAddress]]("aws-mongo-addresses", "Mongo connection Server Addresses")

      lazy val client = SettingKey[MongoClient]("aws-mongo-client", "Configured Mongo Client")
      lazy val db = SettingKey[String]("aws-mongo-db", "Mongo DB Name")
      lazy val collectionName = SettingKey[String]("aws-mongo-collection-name")
      lazy val collection = SettingKey[MongoCollection]("aws-mongo-collection")
    }

    object ssh {
      lazy val config = SettingKey[HostConfigProvider]("aws-ssh-config", "The configure an SSH client")
      lazy val scripts = SettingKey[Seq[NamedSSHScript]]("aws-ssh-scripts", "Some post run execution scripts")
      lazy val run = InputKey[Unit]("aws-ssh-run", "Execute some ssh script on a AWS instance group")

      def connect(instance: MongoDBObject, config: HostConfigProvider)(body: SshClient => Unit) = {
        import SSH.Result._
        SSH(instance.as[String]("publicDns"), config)(s => body(s))
      }

      def connectScript(instance: MongoDBObject, config: HostConfigProvider)(script: NamedSSHScript) =
        connect(instance, config)(script.execute)
    }

    def createInstance(
      client: AmazonEC2Client,
      collection: MongoCollection,
      image: Image,
      configure: ConfiguredRun,
      input: String
    ) = {
      client
        .runInstances(configure(input, image))
        .getReservation()
        .getInstances() map { instance =>
          val dbObj = MongoDBObject("instanceId" -> instance.getInstanceId())
          collection += collection.findOne(dbObj).getOrElse(
            dbObj ++ (
              "ownerId" -> image.getOwnerId(),
              "image" -> Map(
                "id" -> instance.getImageId(),
                "name" -> image.getName()
              ),
              "type" -> instance.getInstanceType(),
              "platform" -> instance.getPlatform(),
              "state" -> instance.getState.getName(),
              "group" -> input
            )
          )
          dbObj
        }
    }

    def groupRequest(group: String, collection: MongoCollection, fields: (String, Any)*) = {
      val query = (MongoDBObject("group" -> group) /: fields)(_ + _)
      (new DescribeInstancesRequest() /: collection.find(query))(
        (r, o) => r.withInstanceIds(o.as[String]("instanceId"))
      )
    }

    def defaultRunRequest(image: Image, instanceType: String = "t1.micro") = {
      new RunInstancesRequest()
        .withImageId(image.getImageId())
        .withInstanceType(instanceType)
    }

    def createRequest(group: String, requests: Seq[NamedRequest])(body: (String, ImageRequest) => Unit) = group match {
      case "*" => requests.foreach(r => body(r.name, r.execute(new ImageRequest())))
      case input => requests
        .find(_.name == group)
        .orElse(Some(NamedAwsRequest("", (_.withFilters(new Filter("name", List(group)))))))
        .foreach(r => body(r.name, r.execute(new ImageRequest())))
    }
  }

  val actionParser: Def.Initialize[State => Parser[(String,String)]] =
    Def.setting {
      (state: State) =>
      (Space ~> (StringBasic.examples("action") <~ Space) ~
      (token("*") | (aws.requests.value.map(a => token(a.name)).reduceLeft(_ | _))))
  }

  val sshActionParser: Def.Initialize[State => Parser[(String,String)]] =
    Def.setting {
      (state: State) =>
      (Space ~> (aws.ssh.scripts.value.map(s => token(s.name)).reduceLeft(_ | _) <~ Space) ~
      (aws.requests.value.map(r => token(r.name)).reduceLeft(_|_)))
    }

  lazy val awsMongoSettings: Seq[Setting[_]] = Seq(
    aws.mongo.client := MongoClient(),
    aws.mongo.db := "sbt-aws-environment",
    aws.mongo.collectionName := name.value.replace("-", "") + "instances",
    aws.mongo.collection := aws.mongo.client.value(aws.mongo.db.value)(aws.mongo.collectionName.value)
  )

  lazy val awsSettings: Seq[Setting[_]] = awsMongoSettings ++ Seq(
    aws.credentials := new BasicAWSCredentials(aws.key.value, aws.secret.value),
    aws.client := new AmazonEC2Client(aws.credentials.value),

    aws.jsonFormat := JSONFormat.defaultFormatter,
    aws.instanceFormat := (reservation => {
      JSONArray(reservation.getInstances().map { instance =>
        JSONObject(Map(
          "instance-id" -> instance.getInstanceId(),
          "image-id" -> instance.getImageId(),
          "public-dns" -> instance.getPublicDnsName(),
          "key-name" -> instance.getKeyName(),
          "type" -> instance.getInstanceType(),
          "state" -> instance.getState().getName()
        ).filter(_._2 != null))
      }.toList)
    }),

    aws.configuredInstance := {
      case (group, image) =>
      aws.defaultRunRequest(image)
        .withMinCount(1)
        .withMaxCount(1)
        .withSecurityGroups("default")
    },

    aws.created := {
      instanceId => println(s"Instance with id $instanceId was created.")
    },

    aws.finished := {
      instanceId => println(s"Instance with id $instanceId is now running.")
    },

    aws.requestDir := baseDirectory.value / "aws-request",
    aws.requests := Seq(),

    aws.actions := { Seq(
      NamedAwsAction("test", "Tests a given request input", (input => aws.createRequest(input, aws.requests.value) {
        case (group, request) =>
        streams.value.log.info(s"Dry running request group ${group}")
        aws.client.value.describeImages(request).getImages.foreach(println)
      })),
      NamedAwsAction("create", "Creates an evironment", (input => aws.createRequest(input, aws.requests.value) {
        case (group, request) =>
        aws.client.value.describeImages(request).getImages().foreach {
          image =>
          aws.createInstance(
            aws.client.value,
            aws.mongo.collection.value,
            image,
            aws.configuredInstance.value,
            group) foreach {
            result =>
            aws.created.value(result)
          }
        }
      })),
      NamedAwsAction("alert", "Triggers on hot instances", { input =>
        def describeRequest = aws.groupRequest(input, aws.mongo.collection.value, "state" -> "pending")
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val callback = new Runnable {
          def run() {
            aws.client.value.describeInstances(describeRequest).getReservations() foreach {
              reservation =>
              reservation.getInstances() foreach {
                instance =>
                val obj = MongoDBObject("instanceId" -> instance.getInstanceId())
                aws.mongo.collection.value
                  .findOne(obj)
                  .filter(_.as[String]("state") != instance.getState().getName())
                  .foreach {
                    o =>
                    aws.mongo.collection.value += o ++ (
                      "state" -> instance.getState().getName(),
                      "publicDns" -> instance.getPublicDnsName()
                    )
                    aws.finished.value(o)
                  }
              }
            }
            if (describeRequest.getInstanceIds().isEmpty) {
              streams.value.log.info(s"Shutting down poller for group ${input}.")
              scheduler.shutdownNow()
            } else {
              streams.value.log.info(s"Checking group ${input} in 10 seconds.")
            }
          }
        }
        streams.value.log.info("Polling group ${input} for running state.")
        scheduler.scheduleAtFixedRate(callback, 0L, 10L, TimeUnit.SECONDS)
      }),
      NamedAwsAction("status", "Checks on the environment status", { input =>
        val request = aws.groupRequest(input, aws.mongo.collection.value)
        val formats = aws.client.value.describeInstances(request).getReservations().map(aws.instanceFormat.value)
        streams.value.log.info(JSONArray(formats.toList).toString(aws.jsonFormat.value))
      }),
      NamedAwsAction("terminate", "Terminates the environment", { input =>
        val filter = MongoDBObject("group" -> input)
        val request = new TerminateInstancesRequest(aws.mongo.collection.value.find(filter).map(_.as[String]("instanceId")).toList)
        aws.client.value.terminateInstances(request).getTerminatingInstances().foreach { instance =>
          streams.value.log.success("%s: %s => %s" format (
            instance.getInstanceId(),
            instance.getPreviousState().getName(),
            instance.getCurrentState().getName()
          ))
        }
        streams.value.log.info(s"Clearing local instance collection group ${input}.")
        aws.mongo.collection.value.drop()
      })
    ) },

    aws.listActions := aws.actions.value.foreach {
      action =>
      streams.value.log.info("%s - %s" format (action.name, action.description))
    },

    aws.run := {
      actionParser.parsed match {
        case (name, input) =>
        aws.actions.value.find(_.name == name).foreach(_.execute(input))
      }
    }
  )

  lazy val awsSshSettings: Seq[Setting[_]] = Seq(
    aws.ssh.config := HostFileConfig(),
    aws.ssh.scripts := Seq(),
    aws.ssh.run := {
      sshActionParser.parsed match {
        case (script, group) =>
        aws.ssh.scripts.value.find(_.name == script).foreach {
          script =>
          aws.mongo.collection.value.find(MongoDBObject("group" -> group)).foreach {
            instance =>
            aws.ssh.connectScript(instance, aws.ssh.config.value)(script) match {
              case Left(msg) => streams.value.log.error(msg)
              case Right(_) => streams.value.log.success("Finished executing %s on instance %s" format (script.name, instance("instanceId")))
            }
          }
        }
      }
    }
  )
}
