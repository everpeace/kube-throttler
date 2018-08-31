import Dependencies._

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, AshScriptPlugin, DockerPlugin, AutomateHeaderPlugin)
  .settings(
    name := "kube-throttler",
    inThisBuild(
      List(
        organization := "com.github.everpeace",
        scalaVersion := "2.12.6"
      )),
    //
    // compile options
    //
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-feature",
      "-language:_",
      "-encoding",
      "UTF-8",
      "-Ywarn-unused-import",
      "-Ypartial-unification"
    ),
    //
    // dependencies
    //
    resolvers ++= Seq(
      Resolver.bintrayRepo("everpeace", "maven"),
      Resolver.bintrayRepo("hseeberger", "maven")
    ),
    libraryDependencies ++= Seq(
      skuber,
      catsCore,
      akkHttpPlayJson,
      healthchecks.core,
      healthchecks.probe,
      logback,
      scalaLogging
    ) ++ Seq(
      scalaTest       % Test,
      akkaHttpTestKit % Test
    ),
    //
    // sbt-header
    //
    licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    organizationName := "Shingo Omura <https://github.com/everpeace>",
    startYear := Some(2018),
    headerLicense := Some(
      HeaderLicense.ALv2("2018", "Shingo Omura <https://github.com/everpeace>")),
    homepage := Some(url("https://github.com/everpeace/kube-throttler")),
    //
    // Scalafmt setting
    //
    scalafmtOnCompile := true,
    scalafmtTestOnCompile := true,
    //
    // pom
    //
    pomIncludeRepository := (_ => false),
    pomExtra := <scm>
      <url>https://github.com/everpeace/kube-throttler</url>
      <connection>scm:git:git@github.com:everpeace/kube-throttler</connection>
    </scm>
      <developers>
        <developer>
          <id>everpeace</id>
          <name>Shingo Omura</name>
          <url>https://github.com/everpeace/</url>
        </developer>
      </developers>,
    //
    // sbt-native-packager docker plugin
    //
    dockerUsername := Some("everpeace"),
    packageName in Docker := "kube-throttler",
    maintainer in Docker := "Shingo Omura <https://github.com/everpeace>",
    dockerBaseImage := "openjdk:8-jdk-alpine",
    dockerExposedPorts := Seq(4321, 5005 /* for jvm debug */ ),
    dockerUpdateLatest := true,
    bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/application.conf"""",
    //
    // sbt-release (release step is defined at release.sbt)
    //
    releaseVersionBump := sbtrelease.Version.Bump.Next
  )
