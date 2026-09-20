package kyo.ffi.sbt

import sbt._
import sbt.util.Logger

/** Installing koffi into a directory's `node_modules`, which is how a Node process reaches a shared library at all.
  *
  * koffi is a native addon rather than a Scala.js dependency, so it cannot arrive through the classpath and has to be
  * on the filesystem before anything tries to open a library. Both the module building a binding and the application
  * consuming one need it, which is why this is not private to either.
  */
object KoffiBootstrap {

    /** Writes `<base>/package.json` pinning koffi to the supported range and installs it, once.
      *
      * Idempotent on the installed marker and the manifest rather than on a timestamp: `npm install` is slow enough
      * that running it on every compile is felt, while a `clean` and a change to the pinned range both have to reach
      * npm. Without the manifest half a range bump would leave the previous koffi installed and the application would
      * fail at load with a version the loader does not support.
      */
    def install(base: File, packageName: String, log: Logger): Unit = {
        val marker    = base / "node_modules" / "koffi" / "package.json"
        val range     = NpmBundleTemplate.KoffiSupportedRange
        val manifest  = s"""{"name":"$packageName","private":true,"dependencies":{"koffi":"$range"}}"""
        val packageJs = base / "package.json"
        val rewrote   = !packageJs.exists() || IO.read(packageJs) != manifest
        if (rewrote) {
            IO.createDirectory(base)
            IO.write(packageJs, manifest)
        }
        if (rewrote || !marker.exists()) {
            log.info(s"[$packageName] installing koffi@$range into $base ...")
            // npm is npm.cmd on Windows, and CreateProcess resolves only .exe from a bare name.
            val npm = if (sys.props.getOrElse("os.name", "").toLowerCase.contains("win")) "npm.cmd" else "npm"
            val rc  = scala.sys.process.Process(Seq(npm, "install", "--no-audit", "--no-fund", "--silent"), base).!
            if (rc != 0) sys.error(s"npm install koffi failed (exit $rc)")
        }
    }
}
