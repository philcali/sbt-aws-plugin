package com.github.philcali.aws
package keys

import sbt._
import com.mongodb.casbah.Imports._

trait Mongo {
  lazy val url = SettingKey[MongoClientURI]("aws-mongo-uri", "Configured Mongo client URI")
  lazy val addresses = SettingKey[List[ServerAddress]]("aws-mongo-addresses", "Mongo connection Server Addresses")
  lazy val client = SettingKey[MongoClient]("aws-mongo-client", "Configured Mongo Client")
  lazy val db = SettingKey[String]("aws-mongo-db", "Mongo DB Name")
  lazy val collectionName = SettingKey[String]("aws-mongo-collection-name")
  lazy val collection = SettingKey[MongoCollection]("aws-mongo-collection")
}
