package com.github.philcali.aws
package utils

import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{
  Filter,
  Image,
  InstanceStateChange,
  RunInstancesRequest,
  DescribeImagesRequest => ImageRequest,
  DescribeInstancesRequest,
  StartInstancesRequest,
  StopInstancesRequest,
  TerminateInstancesRequest
}

import com.mongodb.casbah.Imports._

import collection.JavaConversions._

trait Ec2 {

  /**
   * Utility method to create EC2 instances
   *
   * @param client AmazonEC2Client
   * @param collection MongoCollection
   * @param image Image
   * @param configure ConfiguredRun
   * @param input String
   * @return Seq[MongoDBObject]
   */
  def createInstance(
    client: AmazonEC2Client,
    collection: MongoCollection,
    image: Image,
    configure: Plugin.ConfiguredRun,
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

  /**
   * Helper method to generate a DescribeInstancesRequest from
   * an instance collection group
   *
   * @param group String
   * @param collection MongoCollection
   * @param fields (String, Any)*
   * @return DescribeInstancesRequest
   */
  def groupRequest(group: String, collection: MongoCollection, fields: (String, Any)*) = {
    val query = (MongoDBObject("group" -> group) /: fields)(_ + _)
    (new DescribeInstancesRequest() /: collection.find(query))(
      (r, o) => r.withInstanceIds(o.as[String]("instanceId"))
    )
  }

  /**
   * Helper method to generate a RunInstancesRequest with
   * sane defaults
   *
   * @param image Image
   * @param instanceType String (Defaults "t1.micro")
   * @return RunInstancesRequest
   */
  def defaultRunRequest(image: Image, instanceType: String = "t1.micro") = {
    new RunInstancesRequest()
      .withImageId(image.getImageId())
      .withInstanceType(instanceType)
  }

  /**
   * Helper method to perform some task on potential instances
   *
   * @param group String
   * @param requests Seq[NamedRequest]
   * @param body (NamedRequest =&gt; Unit)
   */
  def spread(group: String, requests: Seq[NamedRequest])(body: NamedRequest => Unit) = group match {
    case "*" => requests.par.foreach(body)
    case input => requests.find(_.name == group).foreach(body)
  }

  /**
   * Helper method to perform some task on potential
   * instances
   *
   * @param group String
   * @param requests Seq[NamedRequest]
   * @param body (String, DescribeImagesRequest) =&gt; Unit
   */
  def createRequest(group: String, requests: Seq[NamedRequest])(body: (String, ImageRequest) => Unit) = {
    spread(group, requests)(r => body(r.name, r.execute(new ImageRequest())))
  }

  /**
   * Starts an instance environment
   *
   * @param client AmazonEC2Client
   * @param group String
   * @param requests Seq[NamedRequest]
   * @param collection MongoCollection
   */
  def startRequest(
    client: AmazonEC2Client,
    group: String,
    requests: Seq[NamedRequest],
    collection: MongoCollection
  )(body: InstanceStateChange => Unit) {
    spread(group, requests) {
      request =>
      val query = MongoDBObject("group" -> request.name, "state" -> "stopped")
      val startRequest = new StartInstancesRequest(collection.find(query).map(_.as[String]("instanceId")).toList)
      startRequest.getInstanceIds.isEmpty match {
        case true =>
        println(s"No instances found for group ${request.name}.")
        case false =>
        client
          .startInstances(startRequest)
          .getStartingInstances()
          .foreach(body)
      }
    }
  }

  /**
   * Stops an instance environment
   *
   * @param client AmazonEC2Client
   * @param group String
   * @param requests Seq[NamedRequest]
   * @param collection MongoCollection
   */
  def stopRequest(
    client: AmazonEC2Client,
    group: String,
    requests: Seq[NamedRequest],
    collection: MongoCollection
  )(body: InstanceStateChange => Unit) {
    spread(group, requests) {
      request =>
      val query = MongoDBObject("group" -> request.name, "state" -> "running")
      val stopRequest = new StopInstancesRequest(collection.find(query).map(_.as[String]("instanceId")).toList)
      stopRequest.getInstanceIds.isEmpty match {
        case true =>
        println(s"No instances found for group ${request.name}.")
        case false =>
        client
          .stopInstances(stopRequest)
          .getStoppingInstances()
          .foreach(body)
      }
    }
  }

  /**
   * Terminates an instance environment
   *
   * @param client AmazonEC2Client
   * @param group String
   * @param requests Seq[NamedRequest]
   * @param collection MongoCollection
   */
  def terminateRequest(
    client: AmazonEC2Client,
    group: String,
    requests: Seq[NamedRequest],
    collection: MongoCollection
  )(body: InstanceStateChange => Unit) {
    spread(group, requests) {
      request =>
      val query = MongoDBObject("group" -> request.name)
      val terminateRequest = new TerminateInstancesRequest(collection.find(query).map(_.as[String]("instanceId")).toList)
      terminateRequest.getInstanceIds.isEmpty match {
        case true =>
        println(s"No instances found for group ${request.name}.")
        case false =>
        client
          .terminateInstances(terminateRequest)
          .getTerminatingInstances()
          .foreach(body)
        collection.find(query).foreach {
          obj => collection -= obj
        }
      }
    }
  }
}
