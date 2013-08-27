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

  type InstanceCallback = String => Unit
  type ConfiguredRun = Image => RunInstancesRequest
  type InstanceFormat = Reservation => JSONType

  case class NamedSSHScript(name: String, execute: SshClient => Unit) extends NamedExecution[SshClient,Unit]
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

    lazy val requests = SettingKey[Seq[NamedRequest]]("aws-requests")
    lazy val created = TaskKey[InstanceCallback]("aws-created")
    lazy val finished = TaskKey[InstanceCallback]("aws-finished")
    lazy val actions = TaskKey[Seq[NamedAwsAction]]("aws-actions")
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
    }
  }

  private val actionParser: Project.Initialize[State => Parser[(String,String)]] =
    (aws.requests) {
      (requests) => {
        (state: State) =>
        (Space ~> (StringBasic <~ Space) ~ (StringBasic | token("*") | (requests.map(a => token(a.name)).reduceLeft(_ | _))))
      }
    }

  private val runActionTask = (parsed: TaskKey[(String,String)]) => {
    (parsed, aws.actions) map {
      case ((name, input), actions) =>
        actions.find(_.name == name).foreach(_.execute(input))
    }
  }

  private val sshActionParser: Project.Initialize[State => Parser[(String,String)]] =
    (aws.ssh.scripts, aws.requests) {
      (scripts, requests) => {
        (state: State) =>
        (Space ~> (scripts.map(s => token(s.name)).reduceLeft(_ | _) <~ Space) ~ (requests.map(r => token(r.name)).reduceLeft(_|_)))
      }
    }

  private val executeSshScript = (parsed: TaskKey[(String,String)]) => {
    (parsed, aws.ssh.config, aws.ssh.scripts, aws.mongo.collection, streams) map {
      case ((script, group), config, scripts, collection, s) =>
      import SSH.Result._
      scripts.find(_.name == script).foreach {
        script =>
        collection.filter(_.as[String]("group") == group).foreach {
          instance =>
          SSH(instance.as[String]("publicDns"), config)(s => script.execute(s)) match {
            case Left(msg) => s.log.error(msg)
            case Right(_) => s.log.success("Finished executing %s on instance %s" format (script, instance("instanceId")))
          }
        }
      }
    }
  }

  def createInstance(
    client: AmazonEC2Client,
    collection: MongoCollection,
    image: Image,
    configure: ConfiguredRun,
    input: String
  ) = {
    client
      .runInstances(configure(image))
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
    val query = ((obj: DBObject) =>
      (fields :+ ("group" -> group)).forall {
        case (k, v) => obj(k) == v
      }
    )
    (new DescribeInstancesRequest() /: collection.filter(query))(
      (r, o) => r.withInstanceIds(o.as[String]("instanceId"))
    )
  }

  def createRequest(group: String, requests: Seq[NamedRequest])(body: ImageRequest => Unit) = group match {
    case "*" => requests.map(_.execute(new ImageRequest())).foreach(body)
    case input => requests
      .find(_.name == group)
      .orElse(Some(NamedAwsRequest("", (_.withFilters(new Filter("name", List(group)))))))
      .map(_.execute(new ImageRequest()))
      .foreach(body)
  }

  def awsMongoSettings: Seq[Setting[_]] = Seq(
    aws.mongo.client := MongoClient(),
    aws.mongo.db := "sbt-aws-environment",
    aws.mongo.collectionName <<= (name)(_.replace("-", "") + "instances"),
    aws.mongo.collection <<= (aws.mongo.client, aws.mongo.db, aws.mongo.collectionName)(_(_)(_))
  )

  def awsSettings: Seq[Setting[_]] = awsMongoSettings ++ Seq(
    aws.credentials <<= (aws.key, aws.secret) (new BasicAWSCredentials(_, _)),
    aws.client <<= aws.credentials (new AmazonEC2Client(_)),

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

    aws.configuredInstance := (image =>
      new RunInstancesRequest()
        .withImageId(image.getImageId)
        .withMaxCount(1)
        .withMinCount(1)
        .withInstanceType("t1.micro")
        .withSecurityGroups("default")
    ),

    aws.created <<= (streams) map {
      s => instanceId => s.log.info("Instance with id %s was created" format instanceId)
    },

    aws.finished <<= (streams) map {
      s => instanceId => println("Instance with id %s is now running" format instanceId)
    },

    aws.requests := Seq(JSONAwsFileRequest("local")),

    aws.actions <<= (
      aws.client,
      aws.mongo.collection,
      aws.requests,
      aws.configuredInstance,
      aws.jsonFormat,
      aws.instanceFormat,
      aws.created,
      aws.finished,
      streams
    ) map {
      (client, collection, requests, configure, jsonFormat, instanceFormat, created, finished, s) => Seq(
        // Tests a given request input
        NamedAwsAction("test", (input => createRequest(input, requests) {
          request =>
          s.log.info("Dry running request group %s" format input)
          client.describeImages(request).getImages.foreach(println)
        })),
        // Creates an environment
        NamedAwsAction("create", (input => createRequest(input, requests) {
          request =>
          client.describeImages(request).getImages().foreach {
            image =>
            createInstance(client, collection, image, configure, input) foreach {
              result =>
              created(result.as[String]("instanceId"))
            }
          }
        })),
        // Triggers on hot instances
        NamedAwsAction("alert", { input =>
          def describeRequest = groupRequest(input, collection, "state" -> "pending")
          val scheduler = Executors.newSingleThreadScheduledExecutor()
          val callback = new Runnable {
            def run() {
              client.describeInstances(describeRequest).getReservations() foreach {
                reservation =>
                reservation.getInstances() foreach {
                  instance =>
                  val obj = MongoDBObject("instanceId" -> instance.getInstanceId())
                  collection
                    .findOne(obj)
                    .filter(_.as[String]("state") != instance.getState().getName())
                    .foreach {
                      o =>
                      collection += o ++ (
                        "state" -> instance.getState().getName(),
                        "publicDns" -> instance.getPublicDnsName()
                      )
                      finished(instance.getInstanceId())
                    }
                }
              }
              if (describeRequest.getInstanceIds().isEmpty) {
                s.log.info("Shutting down poller for group %s" format input)
                scheduler.shutdownNow()
              } else {
                s.log.info("Checking group %s in 10 seconds" format input)
              }
            }
          }
          s.log.info("Polling group %s for running state" format input)
          scheduler.scheduleAtFixedRate(callback, 0L, 10L, TimeUnit.SECONDS)
        }),
        // Checks on the environment status
        NamedAwsAction("status", { input =>
          val request = groupRequest(input, collection)
          val formats = client.describeInstances(request).getReservations().map(instanceFormat)
          s.log.info(JSONArray(formats.toList).toString(jsonFormat))
        }),
        // Terminates the local environment
        NamedAwsAction("terminate", { input =>
          val filter = ((obj: DBObject) => obj.as[String]("group") == input)
          val request = new TerminateInstancesRequest(collection.filter(filter).map(_.as[String]("instanceId")).toList)
          client.terminateInstances(request).getTerminatingInstances().foreach { instance =>
            s.log.success("%s: %s => %s" format (
              instance.getInstanceId(),
              instance.getPreviousState().getName(),
              instance.getCurrentState().getName()
            ))
          }
          s.log.info("Clearing local instance collection group %s" format input)
          collection.drop()
        })
      )
    },

    aws.run <<= InputTask(actionParser)(runActionTask)
  )

  def awsSshSettings = Seq(
    aws.ssh.config := HostFileConfig(),
    aws.ssh.scripts := Seq(),
    aws.ssh.run <<= InputTask(sshActionParser)(executeSshScript)
  )
}
