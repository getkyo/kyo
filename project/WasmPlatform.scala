import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import sbt.*
import sbtcrossproject.*
import scala.language.implicitConversions

/** Cross-project platform for the experimental Scala.js WebAssembly backend.
  *
  * WebAssembly is not a separate compilation target in the portable-scala model: it is an output mode of the Scala.js linker. So this
  * platform enables the same ScalaJSPlugin as [[scalajscrossproject.JSPlatform]] and only differs by flipping the linker into WebAssembly
  * mode. The backend requires `ESModule` output and the WasmGC + exception-handling runtime features.
  *
  * Sources resolve as `shared/` + `wasm/`, and the `js-wasm/` partially-shared directory (auto-wired by `CrossType.Full`) holds Scala.js
  * code common to the `js` and `wasm` platforms. Keeping the linker config here means no module has to repeat it.
  */
case object WasmPlatform extends Platform {
    def identifier: String = "wasm"
    def sbtSuffix: String  = "Wasm"
    def enable(project: Project): Project =
        project.enablePlugins(ScalaJSPlugin).settings(
            scalaJSLinkerConfig ~= {
                _.withExperimentalUseWebAssembly(true)
                    .withModuleKind(ModuleKind.ESModule)
            }
        )
}

/** Cross-project operations for [[WasmPlatform]], mirroring `scalajscrossproject.ScalaJSCrossPlugin`'s `.js` helpers. Import
  * `WasmCrossProject.*` in build.sbt to use `.wasm`, `.wasmSettings`, `.wasmConfigure`, and `.wasmEnablePlugins`.
  */
object WasmCrossProject {
    implicit class WasmCrossProjectOps(private val project: CrossProject) extends AnyVal {
        def wasm: Project = project.projects(WasmPlatform)

        def wasmSettings(ss: Def.SettingsDefinition*): CrossProject =
            wasmConfigure(_.settings(ss*))

        def wasmEnablePlugins(plugins: Plugins*): CrossProject =
            wasmConfigure(_.enablePlugins(plugins*))

        def wasmConfigure(transformer: Project => Project): CrossProject =
            project.configurePlatform(WasmPlatform)(transformer)
    }
}
