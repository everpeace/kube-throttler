import sbt._

object Dependencies {
  lazy val skuber = "io.skuber" %% "skuber" % "2.0.10"
  lazy val catsCore = "org.typelevel" %% "cats-core" % "1.2.0"
  lazy val akkHttpPlayJson = "de.heikoseeberger" %% "akka-http-play-json" % "1.21.0"
  lazy val healthchecks = new {
    val version = "0.4.0"
    val core = "com.github.everpeace" %% "healthchecks-core" % version
    val probe = "com.github.everpeace" %% "healthchecks-k8s-probes" % version
  }
  lazy val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0"

  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.5"
  lazy val akkaHttpTestKit = "com.typesafe.akka" %% "akka-http-testkit" % "10.1.4"
}
