sbtPlugin := true

name := "sbt-aws-plugin"

organization := "com.github.philcali"

version := "0.1.1"

scalacOptions += "-deprecation"

resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies ++= Seq(
  "org.slf4j" % "jcl-over-slf4j" % "1.7.5",
  "com.amazonaws" % "aws-java-sdk" % "1.7.6",
  "org.mongodb" %% "casbah" % "2.6.2",
  "com.decodified" % "scala-ssh_2.10" % "0.6.4",
  "ch.qos.logback" % "logback-classic" % "1.0.13",
  "org.bouncycastle" % "bcprov-jdk16" % "1.46",
  "com.jcraft" % "jzlib" % "1.1.2"
)

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (version.value.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { x => false }

pomExtra := (
  <url>https://github.com/philcali/sbt-aws-plugin</url>
  <licenses>
    <license>
      <name>The MIT License</name>
      <url>http://www.opensource.org/licenses/mit-license.php</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:philcali/sbt-aws-plugin.git</url>
    <connection>scm:git:git@github.com:philcali/sbt-aws-plugin.git</connection>
  </scm>
  <developers>
    <developer>
      <id>philcali</id>
      <name>Philip Cali</name>
      <url>http://philcalicode.blogspot.com/</url>
    </developer>
  </developers>
)
