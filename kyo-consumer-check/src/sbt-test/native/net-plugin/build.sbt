import kyo.ffi.sbt.KyoFfiPlugin
import kyo.ffi.sbt.KyoFfiPlugin.autoImport._
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that reaches kyo-net's system OpenSSL through kyo-ffi-plugin and a hand-written
// nativeConfig, which is the escape hatch for a build that does not take kyo-natives-plugin. The
// net-natives-plugin fixture covers the plugin path; this one keeps the manual one working, since
// ffiNativeDependencyLinkingOptions and ffiNativeDependencyCompileOptions remain a supported surface.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoFfiPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        nativeConfig := {
            val base         = nativeConfig.value
            val (dirs, rest) = ffiNativeDependencyLinkingOptions.value.partition(_.startsWith("-L"))
            base
                .withBaseName("consumer")
                .withLinkingOptions(dirs ++ base.linkingOptions ++ rest)
                .withCompileOptions(ffiNativeDependencyCompileOptions.value ++ base.compileOptions)
        }
    )
