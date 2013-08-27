package com.github.philcali.aws

import com.amazonaws.services.ec2.model.{
  Filter,
  DescribeImagesRequest => ImageRequest
}

import util.parsing.json.{
  JSON,
  JSONFormat,
  JSONObject,
  JSONArray,
  JSONType
}

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
          val filters = list map {
            case filter: String =>
              new Filter("name").withValues(filter)
            case JSONObject(filter) =>
              new Filter(filter("name").toString).withValues(filter("value").toString)
          }
          (req /: filters)(_.withFilters(_))
        case (req, filters: String) =>
          req.withFilters((new Filter("name") /: filters.split(","))(_.withValues(_)))
      })
  }

  def execute = {
    (request => {
      val json = JSON.parseRaw(source)
      (request /: json)(flattenJson(_, _))
    })
  }
}
