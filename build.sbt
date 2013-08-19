sbtPlugin := true

version := "0.0.1"

name := "sbt-aws-plugin"

organization := "com.github.philcali"

libraryDependencies ++= Seq(
  "com.amazonaws" % "aws-java-sdk" % "1.1.7.1",
  "org.mongodb" %% "casbah" % "2.6.2"
)
