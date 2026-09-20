import kyo.test.sbt.KyoTestJsPlugin
import kyo.test.sbt.KyoTestJsPlugin.autoImport.*
import org.scalajs.jsenv.JSEnv
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.interface.ESVersion
import org.scalajs.linker.interface.ModuleKind
import org.scalajs.linker.interface.StandardConfig
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
  * The browser rows run the same classes in Chrome through kyo-test's browser environment (`kyoTestBrowserEnv`, from sbt-kyo-test):
  * `BrowserTest` links JS as an ES module, `BrowserWasmTest` links WasmGC. kyo wires kyo-test by hand rather than through its sbt plugin,
  * so the rows add the plugin's [[KyoTestJsPlugin.browserSettings]], run kyo-test-browser from this build's own project, and run the
  * Chrome version pinned in `project/chrome-for-testing.version`.
  *
  * {{{
  * sbt kyo-coreJS/test                   # Node, JS output
  * sbt kyo-coreJS/WasmTest/test          # Node, WasmGC output
  * sbt kyo-coreJS/BrowserTest/test       # Chrome, JS output
  * sbt kyo-coreJS/BrowserWasmTest/test   # Chrome, WasmGC output
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
  * built only as JavaScript, such as the website bundle, sets it to false. Its Browser and BrowserWasm platforms run `BrowserTest/test`
  * and `BrowserWasmTest/test` on the projects whose [[autoImport.kyoBrowserRow]] is true, the BrowserWasm one only where the Wasm row
  * runs too.
  */
object KyoJsRows extends AutoPlugin {

    override def requires: Plugins      = ScalaJSPlugin
    override def trigger: PluginTrigger = allRequirements

    object autoImport {
        val WasmTest: Configuration = Configuration.of("WasmTest", "wasmtest").extend(Test)

        val BrowserTest: Configuration = Configuration.of("BrowserTest", "browsertest").extend(Test)

        val BrowserWasmTest: Configuration = Configuration.of("BrowserWasmTest", "browserwasmtest").extend(Test)

        val kyoNodeArgs: SettingKey[Seq[String]] =
            settingKey[Seq[String]]("Node arguments for this module's test rows; WasmTest adds --experimental-wasm-exnref")

        val kyoNodeEnv: SettingKey[Map[String, String]] =
            settingKey[Map[String, String]]("Environment variables for this module's test processes, on every row")

        val kyoWasmRow: SettingKey[Boolean] =
            settingKey[Boolean]("Whether testKyo's Wasm row runs this project's WasmTest configuration")

        val kyoBrowserRow: SettingKey[Boolean] =
            settingKey[Boolean]("Whether testKyo's Browser rows run this project's BrowserTest and BrowserWasmTest configurations")

        /** Whether every Scala.js link frees the linker's state when it finishes (the linker's batch mode), rather than keeping it for a
          * faster next link of the same project and configuration.
          *
          * One sbt session keeps that state for every project and every row it has linked, so a session that tests many modules holds all
          * of it at once, and a large WasmGC link then runs the driver out of heap: 56 modules into a local Wasm row, kyo-ui's WasmTest link
          * did at 12 GB. Batch mode holds one link's state at a time. It is on in CI, and testKyo turns it on for any run that tests more
          * than one JS project; a session iterating on one module keeps the incremental links.
          */
        val kyoJsBatchLink: SettingKey[Boolean] =
            settingKey[Boolean]("Whether every Scala.js link frees the linker's state when it finishes (the linker's batch mode)")

        /** kyo-test-browser's runtime classpath as the session built it before a `++` switch, which the browser rows run while the switch
          * keeps the tool from building.
          *
          * The tool is a Scala 3 JVM program, and some of its dependencies (kyo-config among them) also build for Scala 2.13, so
          * `++2.13.18` moves them and the tool no longer compiles against them. testKyo sets this with [[pinBrowserToolCommand]] before a
          * browser pass leaves the primary Scala version and clears it with [[unpinBrowserToolCommand]] after. The rows use it only while a
          * dependency of the tool is on a Scala version the tool cannot compile against, so a pin left by a run that stopped short cannot
          * stand in for a tool the session can build. The 3.3 LTS line kyo-config returns to after the switch back is not such a version.
          */
        val kyoBrowserToolPin: SettingKey[Option[Seq[File]]] =
            settingKey[Option[Seq[File]]](
                "kyo-test-browser's classpath from before a ++ switch, run while the switch keeps it from building"
            )
    }
    import autoImport.*

    /** The project that builds kyo-test-browser, the JVM program the browser rows run. */
    private val browserToolProject = "kyo-test-browserJVM"

    val pinBrowserToolName: String = "kyoPinBrowserTool"

    /** Builds kyo-test-browser at the session's current Scala versions and records its runtime classpath in
      * [[autoImport.kyoBrowserToolPin]], as a session setting, so it stays through a later `++`.
      */
    def pinBrowserToolCommand: Command = Command.command(pinBrowserToolName) { state =>
        val extracted         = Project.extract(state)
        val (next, classpath) = extracted.runTask(LocalProject(browserToolProject) / Runtime / fullClasspath, state)
        val session           = extracted.session
        val pin               = Global / kyoBrowserToolPin := Some(classpath.files)
        BuiltinCommands.reapply(session.copy(rawAppend = withoutPin(session.rawAppend) :+ pin), extracted.structure, next)
    }

    val unpinBrowserToolName: String = "kyoUnpinBrowserTool"

    /** Removes the classpath [[pinBrowserToolCommand]] recorded, so the browser rows build kyo-test-browser again. */
    def unpinBrowserToolCommand: Command = Command.command(unpinBrowserToolName) { state =>
        val extracted = Project.extract(state)
        val session   = extracted.session
        BuiltinCommands.reapply(session.copy(rawAppend = withoutPin(session.rawAppend)), extracted.structure, state)
    }

    private def withoutPin(settings: Seq[Setting[?]]): Seq[Setting[?]] =
        settings.filterNot(_.key.key == kyoBrowserToolPin.key)

    /** Whether code compiled by Scala `compiler` can depend on code compiled by Scala `built`: the same Scala 2 line, or for Scala 3 a
      * minor no newer than the compiler's, since a Scala 3 compiler reads the TASTy of its own minor and older ones.
      */
    private def compilesAgainst(compiler: String, built: String): Boolean =
        (CrossVersion.partialVersion(compiler), CrossVersion.partialVersion(built)) match {
            case (Some((3, compilerMinor)), Some((3, builtMinor))) => builtMinor <= compilerMinor
            case (Some(compilerLine), Some(builtLine))             => compilerLine == builtLine
            case _                                                 => false
        }

    /** kyo-test-browser's runtime classpath: built from its project, or the pinned one while a `++` has moved one of the tool's dependencies
      * to a Scala version the tool cannot compile against.
      */
    private def browserToolClasspath: Def.Initialize[Task[Seq[File]]] = Def.taskDyn {
        val tool    = ProjectRef(thisProjectRef.value.build, browserToolProject)
        val data    = settingsData.value
        val version = (tool / scalaVersion).get(data).getOrElse("unset")
        val moved   = buildDependencies.value.classpathTransitiveRefs(tool).filterNot { dep =>
            (dep / scalaVersion).get(data).exists(compilesAgainst(version, _))
        }
        val pin = kyoBrowserToolPin.value
        if (moved.isEmpty) Def.task[Seq[File]]((tool / Runtime / fullClasspath).value.files)
        else if (pin.isDefined) Def.task[Seq[File]](pin.get)
        else {
            val names = moved.map(dep => s"${dep.project} (Scala ${(dep / scalaVersion).get(data).getOrElse("unset")})").mkString(", ")
            Def.task[Seq[File]](
                sys.error(
                    s"kyo-test-browser builds on Scala $version, and a ++ switch moved its dependencies $names, which it cannot compile " +
                        s"against. testKyo runs a cross version's browser rows with the tool the primary version built; by hand, run " +
                        s"$pinBrowserToolName before the ++."
                )
            )
        }
    }

    /** The flag Node needs to load a WasmGC module that uses exception handling. */
    val wasmExceptionFlag: String = "--experimental-wasm-exnref"

    private def nodeEnv(args: Seq[String], env: Map[String, String]): NodeJSEnv =
        new NodeJSEnv(NodeJSEnv.Config().withArgs(args.toList).withEnv(env))

    /** The WasmGC link: the backend requires ES2022 and ESModule output. */
    def wasmLinkerConfig(config: StandardConfig): StandardConfig =
        config
            .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
            .withModuleKind(ModuleKind.ESModule)

    override def projectConfigurations: Seq[Configuration] = Seq(WasmTest, BrowserTest, BrowserWasmTest)

    // testKyo reads the row switches when it selects modules, so no task or setting refers to them and sbt's unused-key lint would flag
    // them on every load.
    override def globalSettings: Seq[Setting[?]] = KyoTestJsPlugin.browserGlobalSettings ++ Seq(
        excludeLintKeys ++= Set[Def.KeyedInitialize[?]](kyoWasmRow, kyoBrowserRow),
        kyoJsBatchLink    := insideCI.value,
        kyoBrowserToolPin := None
    )

    /** The chrome-headless-shell version the browser rows run, pinned so a Chrome release cannot change a run; CI's Chrome cache is keyed on
      * the file.
      */
    def chromeVersion(base: File): String = IO.read(base / "project" / "chrome-for-testing.version").trim

    /** The oldest Chrome that runs the WebAssembly backend's output. Scala.js requires a Wasm 3.0 engine and names Chrome 137 as the first
      * (https://www.scala-js.org/doc/project/webassembly.html, which also names Firefox 134 and Safari 26; no row runs those). The README
      * states the same floor.
      */
    val wasmChromeFloor: Int = 137

    /** Refuses a pinned Chrome below [[wasmChromeFloor]] before the WasmGC browser row runs a suite, so a pin moved under the floor fails
      * the row by name rather than as a page that cannot compile the module.
      */
    private def checkWasmChromeFloor(version: String): Unit = {
        val major = version.takeWhile(_ != '.')
        if (!major.forall(_.isDigit) || major.isEmpty || major.toInt < wasmChromeFloor)
            sys.error(
                s"project/chrome-for-testing.version pins Chrome $version, below $wasmChromeFloor, the first Chrome that runs the WebAssembly " +
                    "backend's output (a Wasm 3.0 engine)"
            )
    }

    /** A row over `Test`'s compiled classes and classpath: it differs in its link and where the link runs, never in what it compiles. */
    private def row(config: Configuration, link: StandardConfig => StandardConfig, env: Def.Initialize[Task[JSEnv]]): Seq[Setting[?]] =
        inConfig(config)(Defaults.testSettings ++ ScalaJSPlugin.testConfigSettings) ++ Seq(
            config / compile             := (Test / compile).value,
            config / fullClasspath       := (Test / fullClasspath).value,
            config / scalaJSLinkerConfig := link((Test / scalaJSLinkerConfig).value),
            config / jsEnv               := env.value,
            config / parallelExecution   := (Test / parallelExecution).value,
            config / testFrameworks      := (Test / testFrameworks).value,
            config / testOptions         := (Test / testOptions).value
        )

    override def projectSettings: Seq[Setting[?]] =
        Seq(
            kyoNodeArgs   := Seq("--max_old_space_size=5120"),
            kyoNodeEnv    := Map.empty,
            kyoWasmRow    := true,
            kyoBrowserRow := true,
            jsEnv         := nodeEnv(kyoNodeArgs.value, kyoNodeEnv.value)
        ) ++
            row(WasmTest, wasmLinkerConfig, Def.task(nodeEnv(kyoNodeArgs.value :+ wasmExceptionFlag, kyoNodeEnv.value))) ++
            KyoTestJsPlugin.browserSettings ++ Seq(
                kyoTestBrowserClasspath := browserToolClasspath.value,
                kyoTestChromeVersion    := Some(chromeVersion((LocalRootProject / baseDirectory).value))
            ) ++
            // A page loads an ES module or a classic script, never CommonJS, so the JS browser row links an ES module whatever Test links.
            row(BrowserTest, _.withModuleKind(ModuleKind.ESModule), kyoTestBrowserEnv) ++
            row(BrowserWasmTest, wasmLinkerConfig, kyoTestBrowserEnv) ++ Seq(
                // Each run the test adapter starts is a JVM and a Chrome of its own, so a module's suites share one run at a time.
                BrowserTest / parallelExecution     := false,
                BrowserWasmTest / parallelExecution := false,
                BrowserWasmTest / testOptions += {
                    val version = chromeVersion((LocalRootProject / baseDirectory).value)
                    Tests.Setup(() => checkWasmChromeFloor(version))
                }
            )
}
