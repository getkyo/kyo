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
        val proc = js.Dynamic.global.process
        if js.typeOf(proc) == "undefined" || js.typeOf(proc.env) == "undefined" then null
        else
            val value = proc.env.selectDynamic(name)
            if js.isUndefined(value) || value == null then null
            else value.asInstanceOf[String]
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
end SystemPlatformSpecific
