import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.interface.ESVersion
import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbt.*
import sbt.Keys.*

/** The test rows every Scala.js project runs, over the one set of compiled test classes.
  *
  * WebAssembly is an output mode of the Scala.js linker, not a separate compilation, so a module's Wasm row is a configuration of its JS
  * project: `WasmTest` extends `Test`, reuses `Test`'s compiled classes and classpath, and changes only the link (WasmGC, ES2022, ESModule)
  * and the Node flag that loads it. Users link the one `_sjs1` artifact either way, so the row tests exactly the IR they link.
  *
  * {{{
  * sbt kyo-coreJS/test            # Node, JS output
  * sbt kyo-coreJS/WasmTest/test   # Node, WasmGC output
  * }}}
  *
  * Node's configuration is two settings, so a module states it once for both rows: [[autoImport.kyoNodeArgs]] and
  * [[autoImport.kyoNodeEnv]]. `Test / jsEnv` runs them as given; `WasmTest / jsEnv` adds `--experimental-wasm-exnref`, which Node needs for
  * the exception-handling opcodes the WebAssembly backend emits. A module setting `jsEnv` itself would bypass the Wasm flag, so modules set
  * the keys instead.
  *
  * The link is set in `WasmTest` explicitly rather than inherited: many modules link `Test` as CommonJS, and the WebAssembly backend
  * accepts only ESModule output.
  *
  * `testKyo`'s Wasm platform runs `WasmTest/test` on every JS project whose [[autoImport.kyoWasmRow]] is true (the default); a project
  * built only as JavaScript, such as the website bundle, sets it to false.
  */
object KyoJsRows extends AutoPlugin {

    override def requires: Plugins      = ScalaJSPlugin
    override def trigger: PluginTrigger = allRequirements

    object autoImport {
        val WasmTest: Configuration = Configuration.of("WasmTest", "wasmtest").extend(Test)

        val kyoNodeArgs: SettingKey[Seq[String]] =
            settingKey[Seq[String]]("Node arguments for this module's test rows; WasmTest adds --experimental-wasm-exnref")

        val kyoNodeEnv: SettingKey[Map[String, String]] =
            settingKey[Map[String, String]]("Environment variables for this module's test processes, on every row")

        val kyoWasmRow: SettingKey[Boolean] =
            settingKey[Boolean]("Whether testKyo's Wasm row runs this project's WasmTest configuration")
    }
    import autoImport.*

    /** The flag Node needs to load a WasmGC module that uses exception handling. */
    val wasmExceptionFlag: String = "--experimental-wasm-exnref"

    private def nodeEnv(args: Seq[String], env: Map[String, String]): NodeJSEnv =
        new NodeJSEnv(NodeJSEnv.Config().withArgs(args.toList).withEnv(env))

    /** The WasmGC link: the backend requires ES2022 and ESModule output. */
    def wasmLinkerConfig(config: org.scalajs.linker.interface.StandardConfig): org.scalajs.linker.interface.StandardConfig =
        config
            .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
            .withModuleKind(ModuleKind.ESModule)

    override def projectConfigurations: Seq[Configuration] = Seq(WasmTest)

    override def projectSettings: Seq[Setting[?]] =
        inConfig(WasmTest)(Defaults.testSettings ++ ScalaJSPlugin.testConfigSettings) ++ Seq(
            kyoNodeArgs := Seq("--max_old_space_size=5120"),
            kyoNodeEnv  := Map.empty,
            kyoWasmRow  := true,
            jsEnv       := nodeEnv(kyoNodeArgs.value, kyoNodeEnv.value),
            // Same compiled classes and classpath as Test: the row differs in its link, never in what it compiles.
            WasmTest / compile             := (Test / compile).value,
            WasmTest / fullClasspath       := (Test / fullClasspath).value,
            WasmTest / scalaJSLinkerConfig := wasmLinkerConfig((Test / scalaJSLinkerConfig).value),
            WasmTest / jsEnv               := nodeEnv(kyoNodeArgs.value :+ wasmExceptionFlag, kyoNodeEnv.value),
            WasmTest / parallelExecution   := (Test / parallelExecution).value,
            WasmTest / testFrameworks      := (Test / testFrameworks).value,
            WasmTest / testOptions         := (Test / testOptions).value
        )
}
