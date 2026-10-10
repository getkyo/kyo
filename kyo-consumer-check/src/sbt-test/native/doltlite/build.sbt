import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that depends on kyo-sql-doltlite and nothing else. The DoltLite archive does not travel in
// the artifact, so this build links the shim's stubs.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-sql-doltlite" % sys.props("kyo.version"),
        nativeConfig ~= (_.withBaseName("consumer"))
    )
