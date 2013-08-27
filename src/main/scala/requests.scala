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
