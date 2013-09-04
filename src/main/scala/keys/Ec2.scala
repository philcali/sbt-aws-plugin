package com.github.philcali.aws
package keys

import sbt._

import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{
  Reservation,
  Image,
  Filter,
  RunInstancesRequest,
  DescribeImagesRequest => ImageRequest,
  DescribeInstancesRequest,
  TerminateInstancesRequest
}

import util.parsing.json.JSONFormat

trait Ec2 {
  lazy val client = SettingKey[AmazonEC2Client]("aws-ec2-client", "The AWS EC2 client")
  lazy val requestDir = SettingKey[File]("aws-ec2-request-dir")
  lazy val requests = SettingKey[Seq[NamedRequest]]("aws-ec2-requests")
  lazy val configuredInstance = SettingKey[Plugin.ConfiguredRun]("aws-ec2-configured-instance")
  lazy val jsonFormat = SettingKey[JSONFormat.ValueFormatter]("aws-ec2-json-format")
  lazy val instanceFormat = SettingKey[Plugin.InstanceFormat]("aws-ec2-instance-format")
  lazy val pollingInterval = SettingKey[Int]("aws-ec2-polling-interval")
  lazy val actions = TaskKey[Seq[Plugin.NamedAwsAction]]("aws-ec2-actions")
  lazy val created = TaskKey[Plugin.InstanceCallback]("aws-ec2-created")
  lazy val finished = TaskKey[Plugin.InstanceCallback]("aws-ec2-finished")
  lazy val listActions = TaskKey[Unit]("aws-ec2-list-actions")
  lazy val run = InputKey[Unit]("aws-ec2-run", "Run a configured AWS action")
}
