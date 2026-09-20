package kyo.natives.sbt

import kyo.ffi.sbt.KoffiBootstrap
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import sbt.Keys._
import sbt._

/** The Scala.js half of [[KyoNativesPlugin]]: put the libraries where koffi looks for them.
  *
  * koffi opens a file by path and never sees a classpath, so on Node the libraries are written into a package named
  * exactly as the runtime resolves it, `@kyo/ffi-native/native/<os>-<arch>/lib<id>.<ext>`, under `target/node_modules`.
  * Node's own resolution walks up from the linked output and reaches that directory, which is also where the koffi
  * bootstrap installs.
  *
  * `jsEnv` is replaced with a `NodeJSEnv` carrying `NODE_PATH` for that directory, for a project that delivers
  * something and does not set `jsEnv` itself. An ESModule build resolves through `node:module`'s `createRequire`,
  * which anchors on the working directory rather than the module, and reaches the package only through `NODE_PATH`.
  * A project that sets its own `jsEnv` keeps it, and folds in
  * [[KyoNativesJSPlugin.autoImport.kyoNativesNodeEnv]] to get the same resolution.
  */
object KyoNativesJSPlugin extends AutoPlugin {

    override def trigger  = allRequirements
    override def requires = KyoNativesPlugin && ScalaJSPlugin

    import KyoNativesPlugin.autoImport._
    import KyoNativesPlugin.kyoNativesFetched
    import KyoNativesPlugin.kyoNativesRequests

    object autoImport {

        val kyoNativesMaterialize = taskKey[File]("Write the libraries into target/node_modules as the package koffi resolves.")

        val kyoNativesKoffi = settingKey[Boolean](
            "Whether to install koffi into target/node_modules. On by default: koffi is a native Node addon, so it " +
                "cannot arrive through the classpath, and without it a delivered library cannot be opened at all."
        )

        val kyoNativesNodeEnv = taskKey[Map[String, String]](
            "The environment a Node process needs to resolve the materialized package, for a project that sets its own jsEnv."
        )
    }

    import autoImport._

    override def projectSettings: Seq[Setting[_]] = Seq(
        kyoNativesKoffi       := true,
        kyoNativesMaterialize := materializeTask.value,
        kyoNativesNodeEnv     := nodeEnvTask.value,
        // Only for a project whose dependencies declare a library. A project that declares none runs on whatever
        // `jsEnv` it already had, which is the one thing this must not take away from a build that enabled the
        // plugin on a whole crossProject for the sake of one leg.
        //
        // Where it does apply it REPLACES rather than extends: `NodeJSEnv` exposes no accessor for its `Config`, so
        // there is nothing to read out of the value being replaced. A project's own `jsEnv` in `.settings` survives,
        // because a project's settings apply after an auto-plugin's; a `jsEnv` from another plugin applied earlier
        // does not, and such a build sets `jsEnv` itself and folds in `kyoNativesNodeEnv`.
        jsEnv := {
            val env  = kyoNativesNodeEnv.value
            val base = jsEnv.value
            if (env.isEmpty) base else new NodeJSEnv(NodeJSEnv.Config().withEnv(env))
        },
        // Hooked on linking rather than on `run` and `test` separately, so anything downstream of a linked output
        // (a bundler, a packaged application) finds the libraries too.
        Compile / fastLinkJS := (Compile / fastLinkJS).dependsOn(kyoNativesMaterialize).value,
        Compile / fullLinkJS := (Compile / fullLinkJS).dependsOn(kyoNativesMaterialize).value,
        Test / fastLinkJS    := (Test / fastLinkJS).dependsOn(kyoNativesMaterialize).value,
        Test / fullLinkJS    := (Test / fullLinkJS).dependsOn(kyoNativesMaterialize).value
    )

    /** The `NODE_PATH` a Node process needs to resolve the materialized package, or an empty map when this project
      * delivers nothing.
      *
      * The directory is PREPENDED to the inherited `NODE_PATH` rather than written over it. `ExternalJSRun` overlays
      * this map on the environment the Node process inherits, so a bare assignment takes away whatever the build or
      * the developer's shell had pointed it at.
      */
    private def nodeEnvTask: Def.Initialize[Task[Map[String, String]]] = Def.task {
        if (kyoNativesRequests.value.isEmpty) Map.empty[String, String]
        else {
            val dir       = (target.value / "node_modules").getAbsolutePath
            val inherited = sys.env.getOrElse("NODE_PATH", "")
            Map("NODE_PATH" -> (if (inherited.isEmpty) dir else dir + java.io.File.pathSeparator + inherited))
        }
    }

    private def materializeTask: Def.Initialize[Task[File]] = Def.task {
        val fetched    = kyoNativesFetched.value
        val base       = target.value
        val log        = streams.value.log
        val moduleName = name.value
        val root       = base / "node_modules" / "@kyo" / "ffi-native"
        if (fetched.nonEmpty) {
            if (kyoNativesKoffi.value) KoffiBootstrap.install(base, moduleName, log)
            // The name has to be the one the runtime resolves, and `private` keeps an accidental `npm publish` from
            // pushing a directory of someone else's binaries.
            val manifest = """{"name":"@kyo/ffi-native","version":"0.0.0","private":true}"""
            val pkg      = root / "package.json"
            IO.createDirectory(root)
            if (!pkg.exists() || IO.read(pkg) != manifest) IO.write(pkg, manifest)
        }
        // The package is rewritten to hold exactly what was fetched, not added to. koffi resolves by path and never
        // consults the classpath, so a library left by an earlier build keeps answering `require.resolve` after the
        // module that delivered it stopped, and the run then exercises a library this build never produced. A
        // project that now delivers nothing loses the package itself, so "delivers nothing" leaves nothing.
        val wanted     = fetched.map { case (osArch, f) => root / "native" / osArch / f.library.getName }.toSet
        val nativeRoot = root / "native"
        if (fetched.isEmpty) IO.delete(root)
        else if (nativeRoot.isDirectory) {
            (nativeRoot ** "*").get.filter(f => f.isFile && !wanted.contains(f)).foreach(IO.delete)
            (nativeRoot * "*").get.filter(d => d.isDirectory && IO.listFiles(d).isEmpty).foreach(IO.delete)
        }
        fetched.foreach { case (osArch, f) =>
            IO.copyFile(f.library, root / "native" / osArch / f.library.getName, preserveLastModified = true)
        }
        root
    }
}
