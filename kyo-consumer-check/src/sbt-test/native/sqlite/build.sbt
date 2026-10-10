import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// A Scala Native application that depends on kyo-sql-sqlite and nothing else. SQLite's C source travels in the
// artifact, so this build compiles and links the whole engine with no plugin.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-sql-sqlite" % sys.props("kyo.version"),
        nativeConfig ~= (_.withBaseName("consumer"))
    )
