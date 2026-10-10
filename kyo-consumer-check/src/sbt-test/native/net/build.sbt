import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that depends on kyo-net and nothing else: no kyo-ffi-plugin, no link or compile
// options. kyo-net's C shims compile in this build, so this is the link a user without the plugin gets.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        nativeConfig ~= (_.withBaseName("consumer"))
    )
