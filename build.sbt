sbtPlugin := true

version := "0.0.1"

name := "sbt-aws-plugin"

organization := "com.github.philcali"

resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies ++= Seq(
  "com.amazonaws" % "aws-java-sdk" % "1.1.7.1",
  "org.mongodb" %% "casbah" % "2.6.2",
  "com.decodified" %% "scala-ssh" % "0.6.3" cross CrossVersion.full,
  "ch.qos.logback" % "logback-classic" % "1.0.13"
)
