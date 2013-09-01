package com.github.philcali.aws

import sbt._
import Keys._
import complete.DefaultParsers._
import complete.Parser

import com.amazonaws.auth.{
  AWSCredentials,
  BasicAWSCredentials
}
import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
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

  implicit class SshFileTransfers(client: SshClient) extends SshClient(client.config) with ScpTransferable

  case class NamedSshScript(name: String, description: String = "", execute: SshClient => Either[String,Any]) extends NamedExecution[SshClient, Either[String,Any]]
  case class NamedAwsAction(name: String, description: String = "", execute: String => Unit) extends NamedExecution[String,Unit]
  case class NamedAwsRequest(name: String, execute: ImageRequest => ImageRequest) extends NamedRequest
  case class JSONAwsFileRequest(name: String, base: File) extends JSONRequestExecution {
    def source = IO.read(base / (name + ".json"))
  }

  object aws {
    lazy val key = SettingKey[String]("aws-key", "The AWS key")
    lazy val secret = SettingKey[String]("aws-secret", "The AWS secret")
    lazy val credentials = SettingKey[AWSCredentials]("aws-credentials", "The AWS credentials")

    object ec2 {
      lazy val client = SettingKey[AmazonEC2Client]("aws-ec2-client", "The AWS EC2 client")
      lazy val requestDir = SettingKey[File]("aws-ec2-request-dir")
      lazy val requests = SettingKey[Seq[NamedRequest]]("aws-ec2-requests")
      lazy val configuredInstance = SettingKey[ConfiguredRun]("aws-ec2-configured-instance")
      lazy val jsonFormat = SettingKey[JSONFormat.ValueFormatter]("aws-ec2-json-format")
      lazy val instanceFormat = SettingKey[InstanceFormat]("aws-ec2-instance-format")
      lazy val pollingInterval = SettingKey[Int]("aws-ec2-polling-interval")
      lazy val actions = TaskKey[Seq[NamedAwsAction]]("aws-ec2-actions")
      lazy val created = TaskKey[InstanceCallback]("aws-ec2-created")
      lazy val finished = TaskKey[InstanceCallback]("aws-ec2-finished")
      lazy val listActions = TaskKey[Unit]("aws-ec2-list-actions")
      lazy val run = InputKey[Unit]("aws-ec2-run", "Run a configured AWS action")

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
            collection.findOne(dbObj).get
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

    object elb {
      lazy val client = SettingKey[AmazonElasticLoadBalancingClient]("aws-elb-client")
    }

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
      lazy val scripts = SettingKey[Seq[NamedSshScript]]("aws-ssh-scripts", "Some post run execution scripts")
      lazy val listScripts = TaskKey[Unit]("aws-ssh-list-scripts", "Lists the SSH scripts.")
      lazy val run = InputKey[Unit]("aws-ssh-run", "Execute some ssh script on a AWS instance group")

      def connect(instance: MongoDBObject, config: HostConfigProvider)(body: SshClient => Either[String,Any]) = {
        SSH(instance.as[String]("publicDns"), config)(s => body(s))
      }

      def retry(limit: Int = 5, delay: Int = 10000)(body: => Either[String,Any]) {
        body.left.foreach {
          case str if str.contains("java.net.ConnectException") && limit >= 1 =>
          Thread.sleep(delay)
          retry(limit - 1, delay)(body)
          case str =>
          throw new Exception(s"aws.ssh.retry exceedes retry limit: ${str}")
        }
      }

      def connectScript(instance: MongoDBObject, config: HostConfigProvider)(script: NamedSshScript) =
        connect(instance, config)(script.execute)
    }
  }

  private val actionParser: Def.Initialize[State => Parser[(String,String)]] =
    Def.setting {
      (state: State) =>
      (Space ~> (StringBasic.examples("action") <~ Space) ~
      (token("*") | (aws.ec2.requests.value.map(a => token(a.name)).reduceLeft(_ | _))))
  }

  private val sshActionParser: Def.Initialize[State => Parser[(String,String)]] =
    Def.setting {
      (state: State) =>
      (Space ~> (aws.ssh.scripts.value.map(s => token(s.name)).reduceLeft(_ | _) <~ Space) ~
      (aws.ec2.requests.value.map(r => token(r.name)).reduceLeft(_|_)))
    }

  lazy val awsMongoSettings: Seq[Setting[_]] = Seq(
    aws.mongo.client := MongoClient(),
    aws.mongo.db := "sbt-aws-environment",
    aws.mongo.collectionName := name.value.replace("-", "") + "instances",
    aws.mongo.collection := aws.mongo.client.value(aws.mongo.db.value)(aws.mongo.collectionName.value)
  )

  lazy val awsSettings: Seq[Setting[_]] = awsMongoSettings ++ Seq(
    aws.credentials := new BasicAWSCredentials(aws.key.value, aws.secret.value),
    aws.ec2.client := new AmazonEC2Client(aws.credentials.value),
    aws.elb.client := new AmazonElasticLoadBalancingClient(aws.credentials.value),

    aws.ec2.jsonFormat := JSONFormat.defaultFormatter,
    aws.ec2.instanceFormat := (reservation => {
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

    aws.ec2.configuredInstance := {
      case (group, image) =>
      aws.ec2.defaultRunRequest(image)
        .withMinCount(1)
        .withMaxCount(1)
        .withSecurityGroups("default")
    },

    aws.ec2.created := {
      instance => println(s"Instance with id ${instance("instanceId")} was created.")
    },

    aws.ec2.finished := {
      instance => println(s"Instance with id ${instance("instanceId")} is now running.")
    },

    aws.ec2.pollingInterval := 10000,
    aws.ec2.requestDir := baseDirectory.value / "aws-ec2-request",
    aws.ec2.requests := Seq(),

    aws.ec2.actions := { Seq(
      NamedAwsAction("test", "Tests a given request input", (input => aws.ec2.createRequest(input, aws.ec2.requests.value) {
        case (group, request) =>
        streams.value.log.info(s"Dry running request group ${group}")
        aws.ec2.client.value.describeImages(request).getImages.foreach(println)
      })),
      NamedAwsAction("create", "Creates an evironment", (input => aws.ec2.createRequest(input, aws.ec2.requests.value) {
        case (group, request) =>
        aws.ec2.client.value.describeImages(request).getImages().foreach {
          image =>
          aws.ec2.createInstance(
            aws.ec2.client.value,
            aws.mongo.collection.value,
            image,
            aws.ec2.configuredInstance.value,
            group) foreach {
            result =>
            aws.ec2.created.value(result)
          }
        }
      })),
      NamedAwsAction("alert", "Triggers on hot instances", { input =>
        def describeRequest = {
          val request = aws.ec2.groupRequest(input, aws.mongo.collection.value, "state" -> "pending")
          if (request.getInstanceIds.isEmpty) None else Some(request)
        }
        do {
          describeRequest.foreach {
            request =>
            aws.ec2.client.value.describeInstances(request).getReservations() foreach {
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
                    try {
                      aws.ec2.finished.value(aws.mongo.collection.value.findOneByID(o._id).get)
                    } catch {
                      case e: Throwable =>
                      streams.value.log.warn(s"aws.finished callback failed: ${e.getMessage}")
                    }
                  }
              }
            }
          }
          describeRequest foreach { _ =>
            streams.value.log.info(s"Polling group ${input} for running state in the next ${aws.ec2.pollingInterval.value / 1000} seconds.")
            Thread.sleep(aws.ec2.pollingInterval.value)
          }
        } while(describeRequest.isDefined)
        streams.value.log.success(s"Group ${input} is hot.")
      }),
      NamedAwsAction("status", "Checks on the environment status", { input =>
        val request = aws.ec2.groupRequest(input, aws.mongo.collection.value)
        val formats = aws.ec2.client.value.describeInstances(request).getReservations().map(aws.ec2.instanceFormat.value)
        streams.value.log.info(JSONArray(formats.toList).toString(aws.ec2.jsonFormat.value))
      }),
      NamedAwsAction("terminate", "Terminates the environment", { input =>
        val filter = MongoDBObject("group" -> input)
        val request = new TerminateInstancesRequest(aws.mongo.collection.value.find(filter).map(_.as[String]("instanceId")).toList)
        aws.ec2.client.value.terminateInstances(request).getTerminatingInstances().foreach { instance =>
          streams.value.log.success("%s: %s => %s" format (
            instance.getInstanceId(),
            instance.getPreviousState().getName(),
            instance.getCurrentState().getName()
          ))
        }
        streams.value.log.info(s"Clearing local instance collection group ${input}.")
        aws.mongo.collection.value.find(filter).foreach {
          obj => aws.mongo.collection.value -= obj
        }
      })
    ) },

    aws.ec2.listActions := aws.ec2.actions.value.foreach {
      action =>
      streams.value.log.info("%s - %s" format (action.name, action.description))
    },

    aws.ec2.run := {
      actionParser.parsed match {
        case (name, input) =>
        aws.ec2.actions.value.find(_.name == name).foreach(_.execute(input))
      }
    }
  )

  lazy val awsSshSettings: Seq[Setting[_]] = Seq(
    aws.ssh.config := HostFileConfig(),
    aws.ssh.scripts := Seq(),
    aws.ssh.listScripts := aws.ssh.scripts.value.foreach {
      script =>
      streams.value.log.info("%s - %s" format (script.name, script.description))
    },
    aws.ssh.run := {
      sshActionParser.parsed match {
        case (script, group) =>
        aws.ssh.scripts.value.find(_.name == script).foreach {
          script =>
          aws.mongo.collection.value.find(MongoDBObject("group" -> group)).foreach {
            instance =>
            aws.ssh.retry(delay = aws.ec2.pollingInterval.value) {
              aws.ssh.connectScript(instance, aws.ssh.config.value)(script).right.map {
                _ => streams.value.log.success(s"Finished executing ${script.name} on instance ${instance("instanceId")}.")
              }
            }
          }
        }
      }
    }
  )
}
