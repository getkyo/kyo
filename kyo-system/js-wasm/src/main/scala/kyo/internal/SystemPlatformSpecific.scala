package kyo.internal

import kyo.AllowUnsafe
import scala.scalajs.js

/** Scala.js accessors for the default `System.live` implementation.
  *
  * The environment comes from `process.env` where the host defines it and is empty elsewhere, such as in a browser. Operating system,
  * architecture, and line separator come from [[Platform]].
  */
private[kyo] object SystemPlatformSpecific:
    def env(name: String)(using AllowUnsafe): String =
        // Asked as a capability, so any host that defines `process.env` is read; once `process` is known to be defined, reading it cannot
        // throw a ReferenceError.
        if PlatformJs.jsGlobal("process").isEmpty then null
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
end SystemPlatformSpecific
