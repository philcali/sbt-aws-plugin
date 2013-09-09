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
  Image,
  InstanceStateChange,
  DescribeImagesRequest => ImageRequest,
  DescribeInstancesRequest,
  Reservation,
  RunInstancesRequest
}

import collection.JavaConversions._
import com.mongodb.casbah.Imports._
import util.parsing.json.{
  JSONFormat,
  JSONObject,
  JSONArray,
  JSONType
}

import com.decodified.scalassh.SshClient
import com.decodified.scalassh.HostConfigProvider
import com.decodified.scalassh.HostFileConfig

object Plugin extends sbt.Plugin {

  type InstanceCallback = MongoDBObject => Unit
  type ConfiguredRun = (String, Image) => RunInstancesRequest
  type InstanceFormat = Reservation => JSONType
  type StateChangeFormat = InstanceStateChange => String

  /**
   * Implicitly converts a SshClient to ScpTransferable
   */
  implicit class SshFileTransfers(client: SshClient) extends SshClient(client.config) with ScpTransferable

  /**
   * A named ssh script to be used in awsSsh.scripts
   *
   * @param name String
   * @param description String (Optional)
   * @param execute (SshClient =&gt; Either[String, Any])
   */
  case class NamedSshScript(name: String, description: String = "", execute: SshClient => Either[String,Any]) extends NamedExecution[SshClient, Either[String,Any]]

  /**
   * A named AWS action to be used in awsEc2.actions
   *
   * @param name String
   * @param description String (Optional)
   * @param execute (String =&gt; Unit)
   */
  case class NamedAwsAction(name: String, description: String = "", execute: String => Unit) extends NamedExecution[String,Unit]

  /**
   * A named AWS image request to be used in awsEc2.requests
   *
   * @param name String
   * @param execute (DescribeImagesRequest =&gt; DescribeImagesRequest)
   */
  case class NamedAwsRequest(name: String, execute: ImageRequest => ImageRequest) extends NamedRequest

  /**
   * A JSON file representing a DescribeImagesRequest
   *
   * @param name String
   * @param base File
   */
  case class JSONAwsFileRequest(name: String, base: File) extends JSONRequestExecution {
    def source = IO.read(base / (name + ".json"))
  }

  /**
   * Utility object to pull multiple JSONAwsFileRequest's from a given
   * directory
   */
  object JSONAwsDirectory {
    /**
     * @param base File
     * @return Seq[JSONAwsFileRequest]
     */
    def apply(base: File) = {
      (base ** "*.json").get.map { file =>
        JSONAwsFileRequest(file.base, file.asFile.getParentFile)
      }
    }
  }

  /**
   * Object representing a collection of build keys for AWS
   */
  object aws extends keys.Aws

  /**
   * Object representing a collection of build keys for EC2
   */
  object awsEc2 extends keys.Ec2 with utils.Ec2

  /**
   * Object containing build keys for the Mongo related settings
   */
  object awsMongo extends keys.Mongo with utils.Mongo

  /**
   * Object containing build keys for the SSH related settings
   */
  object awsSsh extends keys.Ssh with utils.Ssh

  private object awsParsers {
    /**
     * Safe tokeizer for a named collection
     *
     * @param values Seq[NamedExecution]
     * @return Parser
     */
    def tokizedNames(values: Seq[NamedExecution[_,_]]) = values.headOption match {
      case None =>
      StringBasic.examples("<none defined>")
      case Some(_) =>
      values.map(a => token(a.name)).reduceLeft(_ | _)
    }

    /**
     * Retrieves tab completion for triggering an ec2 action
     */
    lazy val ec2Action: Def.Initialize[Parser[(String,String)]] =
      Def.setting {
        (Space ~> (StringBasic.examples("action") <~ Space) ~
        (token("*") | tokizedNames(awsEc2.requests.value)))
      }

    /**
     * Retrieves tab completion for triggering an ssh action script
     */
    lazy val sshAction: Def.Initialize[Parser[(String,String)]] =
      Def.setting {
        (Space ~> (tokizedNames(awsSsh.scripts.value) <~ Space) ~
        (tokizedNames(awsEc2.requests.value)))
      }

    /**
     * Retrieves tab completion for triggering a one time ssh action
     */
    lazy val sshExecute: Def.Initialize[Parser[(String,String)]] =
      Def.setting {
        (Space ~> (tokizedNames(awsEc2.requests.value) <~ Space) ~
        repsep(StringBasic.examples("command", "<arg1>", "<arg2>"), Space) map {
          case (group, commands) => group -> commands.mkString(" ")
        })
      }

    /**
     * Retrieves tab completion for triggering an upload
     */
    lazy val sshUpload: Def.Initialize[Parser[(String, String, String)]] =
      Def.setting {
        (Space ~> (tokizedNames(awsEc2.requests.value) <~ Space) ~
          (StringBasic.examples("localFile") <~ Space) ~ StringBasic.examples("remoteFile")) map {
            case ((first, second), third) => (first, second, third)
          }
      }
  }

  lazy val awsMongoSettings: Seq[Setting[_]] = Seq(
    awsMongo.client := MongoClient(),
    awsMongo.db := "sbt-aws-environment",
    awsMongo.collectionName := name.value.replace("-", "") + "instances",
    awsMongo.collection := awsMongo.client.value(awsMongo.db.value)(awsMongo.collectionName.value)
  )

  lazy val awsSettings: Seq[Setting[_]] = awsMongoSettings ++ Seq(
    aws.credentials := new BasicAWSCredentials(aws.key.value, aws.secret.value),
    awsEc2.client := new AmazonEC2Client(aws.credentials.value),

    awsEc2.jsonFormat := JSONFormat.defaultFormatter,
    awsEc2.instanceFormat := (reservation => {
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

    awsEc2.stateChangeFormat := (instance =>
      "%s: %s => %s" format(
        instance.getInstanceId(),
        instance.getPreviousState().getName(),
        instance.getCurrentState().getName()
      )
    ),

    awsEc2.configuredInstance := {
      case (group, image) =>
      awsEc2.defaultRunRequest(image)
        .withMinCount(1)
        .withMaxCount(1)
        .withSecurityGroups("default")
    },

    awsEc2.started := {
      instance => println(s"Instance with id ${instance("instanceId")} was created.")
    },

    awsEc2.running := {
      instance => println(s"Instance with id ${instance("instanceId")} is now running.")
    },

    awsEc2.stopped := {
      instance => println(s"instance with id ${instance("instanceId")} is now stopped.")
    },

    awsEc2.pollingInterval := 10000,
    awsEc2.requestDir := baseDirectory.value / "aws-ec2-request",
    awsEc2.requests := JSONAwsDirectory(awsEc2.requestDir.value),

    awsEc2.actions := { Seq(
      NamedAwsAction("test", "Tests a given request input", (input => awsEc2.createRequest(input, awsEc2.requests.value) {
        case (group, request) =>
        streams.value.log.info(s"Dry running request group ${group}")
        awsEc2.client.value.describeImages(request).getImages.foreach(println)
      })),

      NamedAwsAction("create", "Creates an evironment", (input => awsEc2.createRequest(input, awsEc2.requests.value) {
        case (group, request) =>
        awsEc2.client.value.describeImages(request).getImages().foreach {
          image =>
          awsEc2.createInstance(
            awsEc2.client.value,
            awsMongo.collection.value,
            image,
            awsEc2.configuredInstance.value,
            group) foreach {
            result =>
            awsEc2.started.value(result)
          }
        }
      })),

      NamedAwsAction("start", "Starts an instance environment", (input =>
      awsEc2.startRequest(
        awsEc2.client.value,
        input,
        awsEc2.requests.value,
        awsMongo.collection.value
      ) {
        instance =>
        streams.value.log.success(awsEc2.stateChangeFormat.value(instance))
        val record = MongoDBObject("instanceId" -> instance.getInstanceId())
        awsMongo.collection.value += awsMongo.collection.value.findOne(record).getOrElse(record) ++ (
          "state" -> "pending"
        )
        awsEc2.started.value(awsMongo.collection.value.findOne(record).get)
      })),

      NamedAwsAction("stop", "Stops an instance environment", (input =>
      awsEc2.stopRequest(
        awsEc2.client.value,
        input,
        awsEc2.requests.value,
        awsMongo.collection.value
      ) {
        instance =>
        streams.value.log.success(awsEc2.stateChangeFormat.value(instance))
        val record = MongoDBObject("instanceId" -> instance.getInstanceId())
        awsMongo.collection.value += awsMongo.collection.value.findOne(record).getOrElse(record) ++ (
          "state" -> "stopped"
        )
        awsEc2.stopped.value(awsMongo.collection.value.findOne(record).get)
      })),

      NamedAwsAction("alert", "Triggers on hot instances", (input => awsEc2.spread(input, awsEc2.requests.value) {
        sRequest =>
        def describeRequest = {
          val request = awsEc2.groupRequest(sRequest.name, awsMongo.collection.value, "state" -> "pending")
          if (request.getInstanceIds.isEmpty) None else Some(request)
        }
        do {
          describeRequest.foreach {
            request =>
            awsEc2.client.value.describeInstances(request).getReservations() foreach {
              reservation =>
              reservation.getInstances() foreach {
                instance =>
                val obj = MongoDBObject("instanceId" -> instance.getInstanceId())
                awsMongo.collection.value
                  .findOne(obj)
                  .filter(_.as[String]("state") != instance.getState().getName())
                  .foreach {
                    o =>
                    awsMongo.collection.value += o ++ (
                      "state" -> instance.getState().getName(),
                      "publicDns" -> instance.getPublicDnsName()
                    )
                    try {
                      awsEc2.running.value(awsMongo.collection.value.findOneByID(o._id).get)
                    } catch {
                      case e: Throwable =>
                      streams.value.log.warn(s"aws.runngin callback failed: ${e.getMessage}")
                    }
                  }
              }
            }
          }
          describeRequest foreach { _ =>
            streams.value.log.info(s"Polling group ${sRequest.name} for running state in ${awsEc2.pollingInterval.value / 1000} seconds.")
            Thread.sleep(awsEc2.pollingInterval.value)
          }
        } while(describeRequest.isDefined)
        streams.value.log.success(s"Group ${sRequest.name} is hot.")
      })),

      NamedAwsAction("status", "Checks on the environment status", (input => awsEc2.spread(input, awsEc2.requests.value) {
        sRequest =>
        val request = awsEc2.groupRequest(sRequest.name, awsMongo.collection.value)
        val formats = awsEc2.client.value.describeInstances(request).getReservations().map(awsEc2.instanceFormat.value)
        streams.value.log.info(JSONArray(formats.toList).toString(awsEc2.jsonFormat.value))
      })),

      NamedAwsAction("terminate", "Terminates the environment", (input =>
      awsEc2.terminateRequest(
        awsEc2.client.value,
        input,
        awsEc2.requests.value,
        awsMongo.collection.value
      ) {
        instance =>
        streams.value.log.success(awsEc2.stateChangeFormat.value(instance))
      }))
    ) },

    awsEc2.listActions := awsEc2.actions.value.foreach {
      action =>
      streams.value.log.info("%s - %s" format (action.name, action.description))
    },

    awsEc2.run := {
      awsParsers.ec2Action.parsed match {
        case (name, input) =>
        awsEc2.actions.value.find(_.name == name).foreach(_.execute(input))
      }
    }
  )

  lazy val awsSshSettings: Seq[Setting[_]] = Seq(
    awsSsh.config := HostFileConfig(),
    awsSsh.scripts := Seq(),
    awsSsh.listScripts := awsSsh.scripts.value.foreach {
      script =>
      streams.value.log.info("%s - %s" format (script.name, script.description))
    },

    awsSsh.upload := {
      awsParsers.sshUpload.parsed match {
        case (group, localPath, remotePath) =>
        val query = MongoDBObject("group" -> group)
        awsMongo.collection.value.find(query).foreach {
          instance =>
          awsSsh.connect(instance, awsSsh.config.value) {
            _.upload(localPath, remotePath)(awsSsh.ConsoleListener(streams.value.log))
          }
        }
      }
    },

    awsSsh.execute := {
      awsParsers.sshExecute.parsed match {
        case (group, command) =>
        val query = MongoDBObject("group" -> group)
        awsMongo.collection.value.find(query).foreach {
          instance =>
          awsSsh.connect(instance, awsSsh.config.value) {
            _.exec(command).right.map(r => println(r.stdOutAsString()))
          }
        }
      }
    },

    awsSsh.run := {
      awsParsers.sshExecute.parsed match {
        case (script, group) =>
        awsSsh.scripts.value.find(_.name == script).foreach {
          script =>
          val query = MongoDBObject("group" -> group)
          awsMongo.collection.value.find(query).foreach {
            instance =>
            awsSsh.retry(delay = awsEc2.pollingInterval.value) {
              awsSsh.connectScript(instance, awsSsh.config.value)(script).right.map {
                _ => streams.value.log.success(s"Finished executing ${script.name} on instance ${instance("instanceId")}.")
              }
            }
          }
        }
      }
    }
  )
}
