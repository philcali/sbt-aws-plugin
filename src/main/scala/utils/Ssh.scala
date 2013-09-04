package com.github.philcali.aws
package utils

import com.mongodb.casbah.Imports._

import com.decodified.scalassh.SSH
import com.decodified.scalassh.SshClient
import com.decodified.scalassh.HostConfigProvider

trait Ssh {
  /**
   * Retries some body for a defined limit and delay
   *
   * @param limit Int (Optional)
   * @param delat Int (Optional)
   * @param body =&gt; Either[String,Any]
   */
  def retry(limit: Int = 5, delay: Int = 10000)(body: => Either[String,Any]) {
    body.left.foreach {
      case str if str.contains("java.net.ConnectException") && limit >= 1 =>
      Thread.sleep(delay)
      retry(limit - 1, delay)(body)
      case str =>
      throw new Exception(s"SSH retry exceedes retry limit: ${str}")
    }
  }

  /**
   * Connects to some EC2 instance, with a specified config
   *
   * @param instance MongoDBObject
   * @param config HostConfigProvider
   * @param body (SshClient =&gt; Either[String,Any])
   * @return Either[String,Any]
   */
  def connect(instance: MongoDBObject, config: HostConfigProvider)(body: SshClient => Either[String,Any]) = {
    SSH(instance.as[String]("publicDns"), config)(s => body(s))
  }

  /**
   * Connects to some EC2 instance, ans runs SSH script
   *
   * @param instance MongoDBObject
   * @param config HostConfigProvider
   * @param script NamedSshScript
   * @return Either[String, Any]
   */
  def connectScript(instance: MongoDBObject, config: HostConfigProvider)(script: Plugin.NamedSshScript) =
    connect(instance, config)(script.execute)
}
