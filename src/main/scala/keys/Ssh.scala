package com.github.philcali.aws
package keys

import sbt._
import com.decodified.scalassh.HostConfigProvider

trait Ssh {
  lazy val config = SettingKey[HostConfigProvider]("aws-ssh-config", "The configure an SSH client")
  lazy val scripts = SettingKey[Seq[Plugin.NamedSshScript]]("aws-ssh-scripts", "Some post run execution scripts")
  lazy val listScripts = TaskKey[Unit]("aws-ssh-list-scripts", "Lists the SSH scripts.")
  lazy val upload = InputKey[Unit]("aws-ssh-upload", "Uploads a file to an AWS instance group")
  lazy val execute = InputKey[Unit]("aws-ssh-execute", "Executes some ssh command on an AWS instance group")
  lazy val run = InputKey[Unit]("aws-ssh-run", "Execute some ssh script on a AWS instance group")
}
