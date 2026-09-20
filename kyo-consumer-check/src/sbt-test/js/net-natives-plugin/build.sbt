import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

// The js/net application with kyo-natives-plugin enabled. koffi reads the filesystem and never sees a classpath, so
// the plugin writes the libraries into target/node_modules under the package name the runtime resolves, and installs
// koffi beside them.
lazy val root = (project in file("."))
    .enablePlugins(ScalaJSPlugin, KyoNativesPlugin)
    .settings(
        scalaVersion                      := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        scalaJSUseMainModuleInitializer   := true,
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
    )
