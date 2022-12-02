val scala3Version = "3.2.0"

fork in run := true

scalacOptions ++= Seq(
  "-encoding",
  "utf8",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-explain",
  "-language:implicitConversions",
)

scalafmtOnCompile := true
version := "0.1.0-SNAPSHOT"

lazy val kyo = (project in file("."))
  .aggregate(`kyo-core`, `kyo-bench`, `kyo-zio`)
  .settings(
    name := "kyo",
    scalaVersion := scala3Version,
    publishArtifact := false
  )

lazy val `kyo-core` = project
  .in(file("kyo-core"))
  .settings(
    name := "kyo-core",
    scalaVersion := scala3Version,
    
    libraryDependencies += "dev.zio" %% "izumi-reflect" % "2.2.2",

    libraryDependencies += "dev.zio" %% "zio-test" % "2.0.3" % Test,
    libraryDependencies += "dev.zio" %% "zio-test-magnolia" % "2.0.3" % Test,
    libraryDependencies += "dev.zio" %% "zio-test-sbt" % "2.0.3" % Test,
    libraryDependencies += "dev.zio" %% "zio-prelude" % "1.0.0-RC16" % Test,
    libraryDependencies += "dev.zio" %% "zio-laws-laws" % "1.0.0-RC16" % Test,
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.11" % Test
  )

lazy val `kyo-bench` = project
  .in(file("kyo-bench"))
  .enablePlugins(JmhPlugin)
  .dependsOn(`kyo-core`)
  .settings(
    name := "kyo-bench",
    scalaVersion := scala3Version,
    
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.3.12",
    libraryDependencies += "dev.zio" %% "zio" % "2.0.3",
  )

lazy val `kyo-zio` = project
  .in(file("kyo-zio"))
  .dependsOn(`kyo-core`)
  .settings(
    name := "kyo-zio",
    scalaVersion := scala3Version,
    
    libraryDependencies += "dev.zio" %% "zio" % "2.0.3",
  )  

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
