package kyo.internal

import kyo.AllowUnsafe
import scala.scalajs.js

/** Scala.js parts of the default `System.live` implementation.
  *
  * Scala.js sets no `user.name` property, so the user name is a `user.name` property only when the application sets or seeds one. Otherwise
  * it is the OS user on a Node-like host, through `node:os`, and `""` on a host that has none, such as a browser, or that refuses the lookup,
  * such as Deno without `--allow-sys`.
  */
private[kyo] object SystemPlatformSpecific:

    def userName()(using AllowUnsafe): String =
        val property = HostConfig.property("user.name")
        if property != null then property
        else
            PlatformJs.nodeBuiltin("node:os").fold("") { os =>
                try
                    val name = os.userInfo().username
                    if js.typeOf(name) == "string" then name.asInstanceOf[String] else ""
                catch case _: js.JavaScriptException => ""
            }
        end if
    end userName

end SystemPlatformSpecific
