import kyo.ffi.sbt.KyoFfiPlugin
import kyo.ffi.sbt.KyoFfiPlugin.autoImport._
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that depends on kyo-net and wires kyo-ffi-plugin exactly as kyo-net's README says, so
// the plugin resolves on this machine the system OpenSSL that kyo-net's artifact declares and enables its TLS shim.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoFfiPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        nativeConfig := {
            val base = nativeConfig.value
            base
                .withBaseName("consumer")
                .withLinkingOptions(base.linkingOptions ++ ffiNativeDependencyLinkingOptions.value)
                .withCompileOptions(base.compileOptions ++ ffiNativeDependencyCompileOptions.value)
        }
    )
