import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

// The js/net fixture's round trips, with the plugin delivering kyo-net's natives to Node. koffi then finds the
// transport and BoringSSL libraries on the filesystem, so the application selects the posix transport rather than
// Node's own, and the test asserts it did.
//
// The artifact has to carry both libraries for this target, so a hole in the release fails the build here rather than
// leaving the run on the floor transport and the assertion to explain why.
lazy val root = (project in file("."))
    .enablePlugins(ScalaJSPlugin, KyoNativesPlugin)
    .settings(
        scalaVersion                      := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        kyoNativesSource                := NativesSource.Jar,
        scalaJSUseMainModuleInitializer := true,
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
    )
