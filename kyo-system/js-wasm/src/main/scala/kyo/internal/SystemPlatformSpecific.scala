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

    /** Node's own count through the `os` builtin, or 0 when it cannot be reached.
      *
      * Reachable from tests because the fallbacks below it hide its failure: `navigator` answers the same number
      * on Node 21 and later, so an assertion on `availableProcessors` alone stays green while this route is dead
      * and every older Node sizes its pools off the stub.
      */
    private[kyo] def nodeProcessors(): Int =
        try
            val os = nodeOs()
            if os == null then 0
            else if js.typeOf(os.selectDynamic("availableParallelism")) == "function" then
                val n = os.applyDynamic("availableParallelism")()
                if js.isUndefined(n) || n == null then 0 else n.asInstanceOf[Int]
            else
                val cpus = os.applyDynamic("cpus")()
                if js.isUndefined(cpus) || cpus == null then 0
                else cpus.asInstanceOf[js.Array[js.Dynamic]].length
            end if
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => 0
    end nodeProcessors

    /** The `os` builtin, or `null` where neither route reaches it.
      *
      * Two routes because the module kind decides which one exists: a CommonJS bundle carries the `require`
      * global, an ESModule bundle carries none and reaches builtins through `process.getBuiltinModule`. Asking
      * only for the global leaves every ESModule bundle on the Java stub below, which answers 1, so a machine's
      * pools are sized for a single CPU with nothing reporting it.
      *
      * Each `typeof` guard stays INLINE on its global selection, for the reason recorded on `env`.
      */
    private def nodeOs(): js.Dynamic =
        if js.typeOf(js.Dynamic.global.selectDynamic("require")) == "function" then
            val os = js.Dynamic.global.selectDynamic("require").asInstanceOf[js.Function1[String, js.Dynamic]]("os")
            if js.isUndefined(os) || os == null then null else os
        else if js.typeOf(js.Dynamic.global.process) == "undefined" then null
        else if js.typeOf(js.Dynamic.global.process.selectDynamic("getBuiltinModule")) != "function" then null
        else
            val os = js.Dynamic.global.process.applyDynamic("getBuiltinModule")("node:os")
            if js.isUndefined(os) || os == null then null else os
    end nodeOs

    /** `navigator.hardwareConcurrency`, or 0 when there is no such global. The guard stays INLINE for the reason
      * recorded on `env`: Node grew `navigator` in 21, and on anything older a bound read throws before a check on
      * the binding can answer.
      */
    private def navigatorProcessors(): Int =
        try
            if js.typeOf(js.Dynamic.global.selectDynamic("navigator")) == "undefined" then 0
            else
                val n = js.Dynamic.global.selectDynamic("navigator").selectDynamic("hardwareConcurrency")
                if js.typeOf(n) != "number" then 0 else n.asInstanceOf[Int]
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => 0
    end navigatorProcessors

end SystemPlatformSpecific
