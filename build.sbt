name := "akka"
organization := "com.streese"

version := "0.0.0"
scalaVersion := "2.13.3"

lazy val akkaVersion = "2.6.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed"  % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion
)

enablePlugins(BuildInfoPlugin)

buildInfoPackage := "com.streese"
buildInfoKeys    := Seq[BuildInfoKey](name)
