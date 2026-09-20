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

    /** The number of logical processors available to this runtime.
      *
      * Scala.js's `Runtime.getRuntime.availableProcessors()` is a stub that answers 1 on every host, so every per-core normalisation built
      * on it silently divided by the wrong number. Two sources are tried in order, each reporting the count available to THIS process (the
      * container-aware figure the JVM also reports) rather than the machine's raw socket count:
      *
      *   - `node:os`, through `availableParallelism()`, falling back to `cpus().length` on a Node older than 18.14.
      *   - `navigator.hardwareConcurrency`, the same count under the name a browser, Deno, and Node 21 and later put on the global.
      *
      * Both are reached as properties of `globalThis` rather than as bare identifiers, so a page falls through to the second after the first
      * finds no `process`, instead of failing on the read itself. A host with neither answers through the Java stub, which is 1.
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

    /** Node's own count through `node:os`, or 0 on a host with no such module. */
    private def nodeProcessors(): Int =
        PlatformJs.nodeBuiltin("node:os").fold(0) { os =>
            try
                if js.typeOf(os.availableParallelism) == "function" then
                    val n = os.availableParallelism()
                    if js.typeOf(n) == "number" then n.asInstanceOf[Int] else 0
                else
                    val cpus = os.cpus()
                    if js.isUndefined(cpus) || cpus == null then 0
                    else cpus.asInstanceOf[js.Array[js.Dynamic]].length
                end if
            catch case _: js.JavaScriptException => 0
        }
    end nodeProcessors

    /** `navigator.hardwareConcurrency`, or 0 where there is no such global. */
    private def navigatorProcessors(): Int =
        PlatformJs.jsGlobal("navigator").fold(0) { nav =>
            val n = nav.selectDynamic("hardwareConcurrency")
            if js.typeOf(n) != "number" then 0 else n.asInstanceOf[Int]
        }
    end navigatorProcessors

end SystemPlatformSpecific
