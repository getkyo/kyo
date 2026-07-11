import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.platformDepsCrossVersion
import org.scalajs.linker.interface.ESVersion
import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSCrossVersion
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import sbt.*
import sbt.Keys.crossVersion
import sbtcrossproject.*
import scala.language.implicitConversions

/** Cross-project platform for the experimental Scala.js WebAssembly backend.
  *
  * WebAssembly is not a separate compilation target in the portable-scala model: it is an output mode of the Scala.js linker. So this
  * platform enables the same ScalaJSPlugin as [[scalajscrossproject.JSPlatform]] and only differs by flipping the linker into WebAssembly
  * mode. The backend requires `ESModule` output, an ES2022-or-later target, and the WasmGC + exception-handling runtime features.
  *
  * Sources resolve as `shared/` + `wasm/`, and the `js-wasm/` partially-shared directory (auto-wired by `CrossType.Full`) holds Scala.js
  * code common to the `js` and `wasm` platforms. Keeping the linker config here means no module has to repeat it.
  */
case object WasmPlatform extends Platform {
    def identifier: String = "wasm"
    def sbtSuffix: String  = "Wasm"

    /** Published Maven coordinate suffix for WebAssembly artifacts, parallel to Scala.js `_sjs1` and Scala Native `_native0.5`. A wasm
      * module publishes as e.g. `kyo-core_sjs1-wasm_3`, distinct from the JS `kyo-core_sjs1_3`, so the two never collide on Maven Central.
      */
    val wasmCrossVersion: CrossVersion = CrossVersion.binaryWith("sjs1-wasm_", "")

    def enable(project: Project): Project =
        project.enablePlugins(ScalaJSPlugin).settings(
            scalaJSLinkerConfig ~= {
                // sbt-scalajs 1.22.0 requires ES2022 for the Wasm backend (withUseWebAssembly replaces the deprecated flag).
                _.withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
                    .withModuleKind(ModuleKind.ESModule)
            },
            // Give kyo's own wasm artifacts a distinct coordinate the way the ScalaJS and ScalaNative platforms do for theirs. External
            // `%%%` dependencies stay on `_sjs1`, since no upstream Scala.js library publishes a wasm build yet and the `_sjs1` artifacts
            // link to WasmGC unchanged.
            crossVersion                     := wasmCrossVersion,
            platformDepsCrossVersion         := ScalaJSCrossVersion.binary,
            sbt.Keys.publish / sbt.Keys.skip := true
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
