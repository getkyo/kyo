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
  * The default `jsEnv` gains `NODE_PATH` pointing at the same directory. An ESModule build resolves through
  * `node:module`'s `createRequire`, which anchors on the working directory rather than the module, and reaches the
  * package only through `NODE_PATH`. A project that sets its own `jsEnv` keeps it, and folds in
  * [[KyoNativesJSPlugin.autoImport.kyoNativesNodeEnv]] to get the same resolution.
  */
object KyoNativesJSPlugin extends AutoPlugin {

    override def trigger  = allRequirements
    override def requires = KyoNativesPlugin && ScalaJSPlugin

    import KyoNativesPlugin.autoImport._
    import KyoNativesPlugin.kyoNativesFetched

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
        kyoNativesNodeEnv     := Map("NODE_PATH" -> (target.value / "node_modules").getAbsolutePath),
        jsEnv                 := new NodeJSEnv(NodeJSEnv.Config().withEnv(kyoNativesNodeEnv.value)),
        // Hooked on linking rather than on `run` and `test` separately, so anything downstream of a linked output
        // (a bundler, a packaged application) finds the libraries too.
        Compile / fastLinkJS := (Compile / fastLinkJS).dependsOn(kyoNativesMaterialize).value,
        Compile / fullLinkJS := (Compile / fullLinkJS).dependsOn(kyoNativesMaterialize).value,
        Test / fastLinkJS    := (Test / fastLinkJS).dependsOn(kyoNativesMaterialize).value,
        Test / fullLinkJS    := (Test / fullLinkJS).dependsOn(kyoNativesMaterialize).value
    )

    private def materializeTask: Def.Initialize[Task[File]] = Def.task {
        val fetched = kyoNativesFetched.value
        val base    = target.value
        if (kyoNativesKoffi.value && fetched.nonEmpty)
            KoffiBootstrap.install(base, name.value, streams.value.log)
        val root = base / "node_modules" / "@kyo" / "ffi-native"
        // The name has to be the one the runtime resolves, and `private` keeps an accidental `npm publish` from
        // pushing a directory of someone else's binaries.
        val manifest = """{"name":"@kyo/ffi-native","version":"0.0.0","private":true}"""
        val pkg      = root / "package.json"
        IO.createDirectory(root)
        if (!pkg.exists() || IO.read(pkg) != manifest) IO.write(pkg, manifest)
        fetched.foreach { case (osArch, f) =>
            val dest = root / "native" / osArch / f.library.getName
            IO.copyFile(f.library, dest, preserveLastModified = true)
        }
        root
    }
}
