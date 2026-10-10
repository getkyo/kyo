import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// Both embedded SQLite engines in one Native binary, with the delivery active. They compile the same shim, and
// Scala Native unpacks each jar's sources into its own directory, so both translation units reach the link.
//
// Delivering DoltLite is what makes this worth a fixture: the delivered engine compiles this artifact's copy of
// the shim to nothing, which would leave one definition of each entry point and a link that succeeds, and every
// doltlite:// call would then reach kyo-sql-sqlite's plain engine. The shim keeps one definition outside that gate
// so the duplicate is still there.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        // Jar rather than Auto: under Auto a release carrying no engine for this host delivers nothing and says
        // nothing, both shims then compile in full, and the link below fails on the ordinary wrapper duplicates
        // instead of on the sentinel. This fixture would pass having never reached its subject.
        kyoNativesSource := NativesSource.Jar,
        libraryDependencies ++= Seq(
            "io.getkyo" %%% "kyo-sql-sqlite"   % sys.props("kyo.version"),
            "io.getkyo" %%% "kyo-sql-doltlite" % sys.props("kyo.version")
        ),
        nativeConfig ~= (_.withBaseName("consumer"))
    )
