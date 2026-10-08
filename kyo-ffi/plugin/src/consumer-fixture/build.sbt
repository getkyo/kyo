import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

// Exactly what kyo-net/README.md "Scala Native builds" tells a consumer to write; this fixture exists to prove that wiring links.
lazy val readmeWiring = Seq(
    nativeConfig := {
        val base = nativeConfig.value
        base
            .withLinkingOptions(base.linkingOptions ++ ffiNativeDependencyLinkingOptions.value)
            .withCompileOptions(base.compileOptions ++ ffiNativeDependencyCompileOptions.value)
    }
)

lazy val runReport = taskKey[Unit]("nativeLink this app, run the binary, and fail when it exits non-zero.")

lazy val fixtureSettings = readmeWiring ++ Seq(
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "report",
    runReport := {
        val bin  = (Compile / nativeLink).value
        val exit = scala.sys.process.Process(bin.getAbsolutePath).!
        if (exit != 0) sys.error(s"${bin.getAbsolutePath} exited with $exit")
    }
)

lazy val netApp = project
    .in(file("net-app"))
    .enablePlugins(ScalaNativePlugin, kyo.ffi.sbt.KyoFfiPlugin)
    .settings(fixtureSettings, libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"))

lazy val httpApp = project
    .in(file("http-app"))
    .enablePlugins(ScalaNativePlugin, kyo.ffi.sbt.KyoFfiPlugin)
    .settings(fixtureSettings, libraryDependencies += "io.getkyo" %%% "kyo-http" % sys.props("kyo.version"))
