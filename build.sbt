name := "akka"
organization := "com.streese"

version := "0.0.0"
scalaVersion := "2.13.3"

scalacOptions ++= List(
  "-Ywarn-unused"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed"  % "2.6.8",
  "com.typesafe.akka" %% "akka-stream-typed" % "2.6.8",
  "com.typesafe.akka" %% "akka-http"         % "10.1.12"
)

enablePlugins(BuildInfoPlugin)

buildInfoPackage := "com.streese"
buildInfoKeys    := Seq[BuildInfoKey](name)
