package kyo.natives.sbt

import kyo.ffi.sbt.KoffiBootstrap
import kyo.ffi.sbt.NativeTargets
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import sbt._
import sbt.Keys._

/** The Scala.js half of [[KyoNativesPlugin]]: put the libraries where koffi looks for them.
  *
  * koffi opens a file by path and never sees a classpath, so on Node the libraries are written into a package named
  * exactly as the runtime resolves it, `@kyo/ffi-native/native/<os>-<arch>/lib<id>.<ext>`, under `target/node_modules`.
  * Node's own resolution walks up from the linked output and reaches that directory, which is also where the koffi
  * bootstrap installs.
  *
  * `jsEnv` is replaced with a `NodeJSEnv` carrying that environment, for a project that delivers something and does
  * not set `jsEnv` itself. A project that sets its own `jsEnv` keeps it, and folds in
  * [[KyoNativesJSPlugin.autoImport.kyoNativesNodeEnv]] to get the same resolution.
  *
  * Two resolution paths, because the module kind decides which one exists. Under `ModuleKind.CommonJSModule` the
  * loader finds the package through the global `require`, which `NODE_PATH` points at the materialized directory.
  * Under `ModuleKind.ESModule` there is no global `require` at all, so that path is inert and `NODE_PATH` helps only
  * `createRequire` find koffi itself; the per-library `KYO_FFI_<ID>_PATH` override is what carries an ESModule build,
  * and it is the loader's first candidate on every module kind.
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

    override def projectSettings: Seq[Setting[?]] = Seq(
        kyoNativesKoffi       := true,
        kyoNativesMaterialize := materializeTask(Compile).value,
        kyoNativesNodeEnv     := nodeEnvTask(Compile).value,
        // After the production one: Node resolves a test run's libraries through the production package first, so a
        // copy there as old as the last production link would shadow the one the Test package just received.
        Test / kyoNativesMaterialize := materializeTask(Test).dependsOn(kyoNativesMaterialize).value,
        Test / kyoNativesNodeEnv     := nodeEnvTask(Test).value,
        // Only for a project whose dependencies declare a library. A project that declares none runs on whatever
        // `jsEnv` it already had, which is the one thing this must not take away from a build that enabled the
        // plugin on a whole crossProject for the sake of one leg.
        //
        // Where it does apply it REPLACES rather than extends: `NodeJSEnv` exposes no accessor for its `Config`, so
        // there is nothing to read out of the value being replaced. A project's own `jsEnv` in `.settings` survives,
        // because a project's settings apply after an auto-plugin's; a `jsEnv` from another plugin applied earlier
        // does not, and such a build sets `jsEnv` itself and folds in `kyoNativesNodeEnv`.
        //
        // The Test environment, because one `jsEnv` serves both `run` and `test` and a project's own `jsEnv` is set at
        // that one scope. The Test set is the production set plus the test-only libraries, and a variable naming a
        // library no production module loads is never read.
        jsEnv := {
            val env  = (Test / kyoNativesNodeEnv).value
            val base = jsEnv.value
            if (env.isEmpty) base else new NodeJSEnv(NodeJSEnv.Config().withEnv(env))
        },
        // Hooked on linking rather than on `run` and `test` separately, so anything downstream of a linked output
        // (a bundler, a packaged application) finds the libraries too.
        Compile / fastLinkJS := (Compile / fastLinkJS).dependsOn(kyoNativesMaterialize).value,
        Compile / fullLinkJS := (Compile / fullLinkJS).dependsOn(kyoNativesMaterialize).value,
        Test / fastLinkJS    := (Test / fastLinkJS).dependsOn(Test / kyoNativesMaterialize).value,
        Test / fullLinkJS    := (Test / fullLinkJS).dependsOn(Test / kyoNativesMaterialize).value
    )

    /** What a Node process needs to find the delivered libraries, or an empty map when this project delivers nothing.
      *
      * `NODE_PATH` resolves the materialized package, and the directory is PREPENDED to the inherited value rather
      * than written over it. `ExternalJSRun` overlays this map on the environment the Node process inherits, so a bare
      * assignment takes away whatever the build or the developer's shell had pointed it at.
      *
      * `KYO_FFI_<ID>_PATH` names each library outright, and is what makes an ESModule build work. The loader's package
      * lookup and its koffi probe both go through the GLOBAL `require`, which exists under `ModuleKind.CommonJSModule`
      * and not under `ModuleKind.ESModule`; `NODE_PATH` does not rescue that, because it only helps `createRequire`
      * find koffi itself. This override is the first candidate the loader tries on every module kind, so it is the one
      * path that does not depend on which one the application linked with.
      *
      * Only the host's own target is named. The variable holds one path per library and the process runs on this
      * machine, so a build that resolved several targets, or one target that is not this machine's, falls back to the
      * package lookup rather than pointing Node at a library it cannot load.
      */
    private def nodeEnvTask(configuration: Configuration): Def.Initialize[Task[Map[String, String]]] = Def.task {
        val log = streams.value.log
        // Read outside the branch, because a regular task evaluates every `.value` whatever the branch decides. With
        // no requests the fetch is empty anyway, so this costs nothing and does not pretend to be a guard.
        val fetched = (configuration / kyoNativesFetched).value
        if ((configuration / kyoNativesRequests).value.isEmpty) Map.empty[String, String]
        else {
            // Node consults the node_modules directories above the linked file before any NODE_PATH entry, and
            // target/node_modules is one of them, so a test run looks in the production package first and reaches the
            // Test one only for a file the production package lacks, which is why the Test materialize depends on the
            // production one.
            val dirs = (nodeRoot(target.value, configuration) +: (if (configuration == Test) Seq(nodeRoot(target.value, Compile)) else Nil))
                .map(r => (r / "node_modules").getAbsolutePath).distinct
            val inherited = sys.env.get("NODE_PATH").filter(_.nonEmpty).toSeq
            val nodePath  = Map("NODE_PATH" -> (dirs ++ inherited).mkString(java.io.File.pathSeparator))
            val host      = NativeTargets.host
            val libraries = fetched.collect {
                case (osArch, f) if osArch == host =>
                    s"KYO_FFI_${f.libId.toUpperCase.replace('-', '_')}_PATH" -> f.library.getAbsolutePath
            }.toMap
            // Saying so matters most on an ESModule build, where the override is the only candidate that resolves and
            // its absence is a green build that runs on the floor.
            if (fetched.nonEmpty && libraries.isEmpty)
                log.info(
                    s"[kyo-natives] nothing was delivered for $host, so no KYO_FFI_<ID>_PATH is set and this process " +
                        s"resolves libraries only through the package lookup (targets: ${fetched.map(_._1).distinct.mkString(", ")})"
                )
            nodePath ++ libraries
        }
    }

    /** Where `configuration`'s package lives: `target` itself for production, which is what a packager reads and what
      * Node's own walk up from the linked output finds, and a directory of its own for the Test configuration. One
      * directory written by both would hold the test-only libraries after `test` until the next production link, and two
      * links in one command would prune and copy the same files at once.
      */
    private def nodeRoot(target: File, configuration: Configuration): File =
        if (configuration == Test) target / "kyo-natives-test-node" else target

    private def materializeTask(configuration: Configuration): Def.Initialize[Task[File]] = Def.task {
        val fetched    = (configuration / kyoNativesFetched).value
        val base       = nodeRoot(target.value, configuration)
        val log        = streams.value.log
        val moduleName = name.value
        val root       = base / "node_modules" / "@kyo" / "ffi-native"
        if (fetched.nonEmpty) {
            // Before the copy below, which must follow it: npm 7+ prunes from node_modules every package its
            // package.json does not list, @kyo/ffi-native included.
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
