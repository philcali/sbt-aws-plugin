package com.github.philcali.aws
package utils

import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{
  Image,
  Filter,
  RunInstancesRequest,
  DescribeImagesRequest => ImageRequest,
  DescribeInstancesRequest
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
   * Helper method to perform some task on potential
   * instances
   *
   * @param group String
   * @param requests Seq[NamedRequest]
   * @param body (String, DescribeImagesRequest) =&gt; Unit
   */
  def createRequest(group: String, requests: Seq[NamedRequest])(body: (String, ImageRequest) => Unit) = group match {
    case "*" => requests.foreach(r => body(r.name, r.execute(new ImageRequest())))
    case input => requests
      .find(_.name == group)
      .orElse(Some(Plugin.NamedAwsRequest("", (_.withFilters(new Filter("name", List(group)))))))
      .foreach(r => body(r.name, r.execute(new ImageRequest())))
  }
}