import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

// A Node application that gets SQLite from the library kyo-sql-sqlite's main artifact carries, and a test that gets
// DoltLite from a test-only dependency. The production package and the test run read different configurations, so
// the engine the tests need must reach the test run and stay out of what `fastLinkJS` ships.
lazy val root = (project in file("."))
    .enablePlugins(ScalaJSPlugin, KyoNativesPlugin)
    .settings(
        scalaVersion                         := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo"   %%% "kyo-sql-sqlite"   % sys.props("kyo.version"),
        libraryDependencies += "io.getkyo"   %%% "kyo-sql-doltlite" % sys.props("kyo.version") % Test,
        libraryDependencies += "org.scalameta" %%% "munit"          % "1.2.1"                  % Test,
        // The artifacts have to carry both engines for this target, so a hole in the release fails the build here.
        kyoNativesSource                := NativesSource.Jar,
        scalaJSUseMainModuleInitializer := true,
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
    )
