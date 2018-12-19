import sbt._

object Dependencies {
  lazy val skuber = "io.skuber" %% "skuber" % "2.0.12"
  lazy val catsCore = "org.typelevel" %% "cats-core" % "1.2.0"
  lazy val akkHttpPlayJson = "de.heikoseeberger" %% "akka-http-play-json" % "1.21.0"
  object healthchecks {
    lazy val version = "0.4.0"
    lazy val core = "com.github.everpeace" %% "healthchecks-core" % version
    lazy val probe = "com.github.everpeace" %% "healthchecks-k8s-probes" % version
  }
  lazy val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0"
  object kamon {
    lazy val core = "io.kamon" %% "kamon-core" % "1.1.1"
    lazy val systemMetrics = "io.kamon" %% "kamon-system-metrics" % "1.0.0"
    lazy val akka = "io.kamon" %% "kamon-akka-2.5" % "1.1.2"
    lazy val akkaHttp = "io.kamon" %% "kamon-akka-http-2.5" % "1.0.1"
    lazy val prometheus = "io.kamon" %% "kamon-prometheus" % "1.1.1"
    lazy val logReporter = "io.kamon" %% "kamon-log-reporter" % "0.6.8"
  }
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.5"
  lazy val akkaHttpTestKit = "com.typesafe.akka" %% "akka-http-testkit" % "10.1.4"
}
