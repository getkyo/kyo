import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

// A Scala.js application on Node that depends on kyo-net and nothing else. No koffi and no native path is set up, so
// kyo-net runs on its Node transport, the floor its README promises works everywhere Node does.
lazy val root = (project in file("."))
    .enablePlugins(ScalaJSPlugin)
    .settings(
        scalaVersion                    := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        scalaJSUseMainModuleInitializer := true,
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
    )
