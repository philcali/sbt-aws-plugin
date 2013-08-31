sbtPlugin := true

version := "0.0.1"

name := "sbt-aws-plugin"

organization := "com.github.philcali"

scalacOptions += "-deprecation"

resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies ++= Seq(
  "com.amazonaws" % "aws-java-sdk" % "1.1.7.1",
  "org.mongodb" %% "casbah" % "2.6.2",
  "com.decodified" % "scala-ssh_2.10" % "0.6.4",
  "ch.qos.logback" % "logback-classic" % "1.0.13",
  "org.bouncycastle" % "bcprov-jdk16" % "1.46",
  "com.jcraft" % "jzlib" % "1.1.2"
)
