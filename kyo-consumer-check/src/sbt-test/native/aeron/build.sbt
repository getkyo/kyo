import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that depends on kyo-aeron and nothing else. The Aeron archive does not travel in the
// artifact, so without the plugin this build links kyo-aeron's shim stubs.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-aeron" % sys.props("kyo.version"),
        nativeConfig ~= (_.withBaseName("consumer"))
    )
