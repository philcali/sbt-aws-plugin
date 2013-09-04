package com.github.philcali.aws
package utils

import com.mongodb.casbah.Imports._

trait Mongo {
  def findIn(collection: MongoCollection)(tupe: (String, Any)) =
    collection.find(MongoDBObject(tupe))
}
