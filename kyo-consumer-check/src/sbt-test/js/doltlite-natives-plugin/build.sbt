import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

// A Node application that gets the DoltLite engine from the library the published artifact carries. koffi reads the
// filesystem and never sees a classpath, so the plugin writes the library into the package the loader resolves and
// installs koffi beside it.
lazy val root = (project in file("."))
    .enablePlugins(ScalaJSPlugin, KyoNativesPlugin)
    .settings(
        scalaVersion                      := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-sql-doltlite" % sys.props("kyo.version"),
        // The artifact has to carry the engine for this target, so a hole in the release fails the build here.
        kyoNativesSource                := NativesSource.Jar,
        scalaJSUseMainModuleInitializer := true,
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
    )
