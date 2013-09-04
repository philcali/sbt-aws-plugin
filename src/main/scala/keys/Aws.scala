package com.github.philcali.aws
package keys

import sbt._
import com.amazonaws.auth.AWSCredentials

trait Aws {
  lazy val key = SettingKey[String]("aws-key", "The AWS key")
  lazy val secret = SettingKey[String]("aws-secret", "The AWS secret")
  lazy val credentials = SettingKey[AWSCredentials]("aws-credentials", "The AWS credentials")
}
