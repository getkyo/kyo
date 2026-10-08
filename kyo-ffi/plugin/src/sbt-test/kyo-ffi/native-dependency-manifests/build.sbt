import scala.scalanative.sbtplugin.ScalaNativePlugin

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")
ThisBuild / organization := "kyo.ffi.scripted"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// The consumer is a separate build (changes/consumer.sbt) so the producer can only reach it as a published jar: a dependsOn, or a
// producer still in the build, puts the producer's class directory on the consumer's classpath and hides the published-artifact path.
lazy val producer = (project in file("producer"))
    .enablePlugins(KyoFfiPlugin, ScalaNativePlugin)
    .settings(
        name := "producer",
        ffiLibraries := Seq(
            FfiLibrary(
                id = "prodm",
                cSources = Seq(baseDirectory.value / "src" / "main" / "c" / "prodm.c"),
                linkLibs = Seq("m")
            )
        )
    )
