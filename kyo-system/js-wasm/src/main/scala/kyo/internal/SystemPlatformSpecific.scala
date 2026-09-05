package kyo.internal

import kyo.AllowUnsafe
import scala.scalajs.js

/** JS-specific `os.name` detection.
  *
  * Scala.js's `java.lang.System.getProperty("os.name")` returns `null`. Fall back to Node's `process.platform`, which gives one of
  * `"darwin" | "linux" | "win32" | "freebsd" | ...`. We translate these into the same tokens that Java's `os.name` would produce (e.g.
  * `"Mac OS X"`, `"Linux"`) so downstream `String.contains("mac")` checks just work.
  */
private[kyo] object SystemPlatformSpecific:
    def env(name: String)(using AllowUnsafe): String =
        // The `typeof` guard must stay INLINE on the global selection: binding `js.Dynamic.global.process`
        // to a val first emits a bare `process` read, which throws ReferenceError in browsers.
        if js.typeOf(js.Dynamic.global.process) == "undefined" then null
        else
            val proc = js.Dynamic.global.process
            if js.typeOf(proc.env) == "undefined" then null
            else
                val value = proc.env.selectDynamic(name)
                if js.isUndefined(value) || value == null then null
                else value.asInstanceOf[String]
            end if
        end if
    end env

    def property(name: String)(using AllowUnsafe): String =
        java.lang.System.getProperty(name)

    def osName()(using AllowUnsafe): String =
        val javaProp = java.lang.System.getProperty("os.name", "")
        if javaProp.nonEmpty then javaProp
        else if js.typeOf(js.Dynamic.global.process) != "undefined"
            && js.typeOf(js.Dynamic.global.process.platform) != "undefined"
        then
            js.Dynamic.global.process.platform.asInstanceOf[String] match
                case "darwin"  => "Mac OS X"
                case "linux"   => "Linux"
                case "win32"   => "Windows"
                case "freebsd" => "FreeBSD"
                case "openbsd" => "OpenBSD"
                case "sunos"   => "SunOS"
                case "aix"     => "AIX"
                case other     => other
        else ""
        end if
    end osName

    /** Returns the CPU architecture. Falls back to Node's `process.arch` when Java's `os.arch` is unavailable (Scala.js returns null),
      * normalised to Java-style tokens so callers can match on `"aarch64"`, `"x86_64"`, etc.
      */
    def osArch()(using AllowUnsafe): String =
        val javaProp = java.lang.System.getProperty("os.arch", "")
        if javaProp.nonEmpty then javaProp
        else if js.typeOf(js.Dynamic.global.process) != "undefined"
            && js.typeOf(js.Dynamic.global.process.arch) != "undefined"
        then
            js.Dynamic.global.process.arch.asInstanceOf[String] match
                case "x64"   => "x86_64"
                case "arm64" => "aarch64"
                case "ia32"  => "x86"
                case "arm"   => "arm"
                case other   => other
        else ""
        end if
    end osArch

    /** The number of logical processors available to this runtime.
      *
      * Scala.js's `Runtime.getRuntime.availableProcessors()` is a stub that answers 1 on every host, so every
      * per-core normalisation built on it silently divided by the wrong number. Three sources are tried in
      * order, each reporting the count available to THIS process (the container-aware figure the JVM also
      * reports) rather than the machine's raw socket count:
      *
      *   - Node's `os.availableParallelism()`, falling back to `os.cpus().length` on a Node older than 18.14.
      *     Reached through `require`, which the CommonJS backend has.
      *   - `navigator.hardwareConcurrency`, the same value under a name browsers, Deno and Node 21 and later
      *     expose as a global, which is what the ESModule backend the Wasm target mandates can reach.
      *   - the Java stub, so a runtime with neither still yields 1 instead of an error.
      */
    def availableProcessors()(using AllowUnsafe): Int =
        val fromNode = nodeProcessors()
        if fromNode > 0 then fromNode
        else
            val fromNavigator = navigatorProcessors()
            if fromNavigator > 0 then fromNavigator
            else Runtime.getRuntime.availableProcessors()
        end if
    end availableProcessors

    /** Node's own count through `require("os")`, or 0 when `require` or the module is unavailable. */
    private def nodeProcessors(): Int =
        try
            val require = js.Dynamic.global.selectDynamic("require")
            if js.typeOf(require) == "undefined" || require == null then 0
            else
                val os = require.asInstanceOf[js.Function1[String, js.Dynamic]]("os")
                if js.isUndefined(os) || os == null then 0
                else if js.typeOf(os.selectDynamic("availableParallelism")) == "function" then
                    val n = os.applyDynamic("availableParallelism")()
                    if js.isUndefined(n) || n == null then 0 else n.asInstanceOf[Int]
                else
                    val cpus = os.applyDynamic("cpus")()
                    if js.isUndefined(cpus) || cpus == null then 0
                    else cpus.asInstanceOf[js.Array[js.Dynamic]].length
                end if
            end if
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => 0
    end nodeProcessors

    /** `navigator.hardwareConcurrency`, or 0 when there is no such global. */
    private def navigatorProcessors(): Int =
        try
            val nav = js.Dynamic.global.selectDynamic("navigator")
            if js.typeOf(nav) == "undefined" || nav == null then 0
            else
                val n = nav.selectDynamic("hardwareConcurrency")
                if js.typeOf(n) != "number" then 0 else n.asInstanceOf[Int]
            end if
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => 0
    end navigatorProcessors

end SystemPlatformSpecific
