import org.scalajs.linker.interface.ModuleKind
import org.scalajs.linker.interface.OutputPatterns
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

// The js/net-natives-plugin fixture under ModuleKind.ESModule, which is the module kind kyo's own Wasm leg uses and
// the one no fixture covered.
//
// It matters because the loader's package lookup and its koffi probe both go through the GLOBAL `require`, which does
// not exist here. Only the per-library KYO_FFI_<ID>_PATH override reaches an ESModule build, so this fixture is what
// keeps that override wired: without it the build stays green and the application falls back to Node's own transport.
lazy val root = (project in file("."))
    .enablePlugins(ScalaJSPlugin, KyoNativesPlugin)
    .settings(
        scalaVersion                      := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        kyoNativesSource                := NativesSource.Jar,
        scalaJSUseMainModuleInitializer := true,
        // `.mjs` so Node reads the output as a module without a package.json beside it declaring the type.
        scalaJSLinkerConfig ~= (
            _.withModuleKind(ModuleKind.ESModule)
                .withOutputPatterns(OutputPatterns.fromJSFile("%s.mjs"))
        ),
        // The environment the plugin contributes, written where the test can read it and source it. `jsEnv` only
        // reaches Node processes sbt itself spawns, and this fixture runs node directly so it can bound the run and
        // so it can run the same binary a second time WITHOUT the overrides. Writing the map out is also what makes
        // the override itself assertable rather than inferred from the run.
        TaskKey[Unit]("writeNodeEnv") := {
            val env = kyoNativesNodeEnv.value
            IO.write(
                baseDirectory.value / "node-env.sh",
                env.toSeq.sortBy(_._1).map { case (k, v) => s"""export $k="$v"""" }.mkString("", "\n", "\n")
            )
        }
    )
