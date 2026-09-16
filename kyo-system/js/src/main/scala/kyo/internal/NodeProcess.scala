package kyo.internal

import scala.scalajs.js

/** The `process` global for the Node backends of Path and Process, read without the `ReferenceError` a bare `process` throws on a host that
  * does not declare it, such as a browser.
  *
  * A read that has a meaningful answer on such a host gets it: an environment variable is unset and a child inherits nothing. The same holds
  * where the host refuses the read, as Deno does for `process.env` without `--allow-env` by throwing. An operation that has no answer, such as
  * the working directory or the pid a lock records, fails with [[unsupported]], which names the operation and the host.
  *
  * These are the OS environment, not the configuration flags and `System` read: a child inherits the real environment, and a seed has no
  * place in it.
  */
private[kyo] object NodeProcess:

    /** `process.env[name]` when it is a string, and `null` otherwise, including on a host without `process` or one that refuses the read. */
    def env(name: String): String =
        envObject.fold(null: String) { env =>
            try
                val value = env.selectDynamic(name)
                if js.typeOf(value) == "string" then value.asInstanceOf[String] else null
            catch case _: js.JavaScriptException => null
        }

    /** A new object holding a copy of `process.env`, and an empty one on a host without `process` or one that refuses the read. */
    def envCopy(): js.Dynamic =
        envObject.fold(js.Dynamic.literal()) { env =>
            try js.Dynamic.global.Object.assign(js.Dynamic.literal(), env)
            catch case _: js.JavaScriptException => js.Dynamic.literal()
        }
    end envCopy

    /** `process` on a Node-like host, for `operation`; anywhere else, throws [[unsupported]]. */
    def require(operation: String): js.Dynamic =
        PlatformJs.jsGlobal("process").toOption.filter(_ => Platform.isNodeLike).getOrElse(throw unsupported(operation))

    /** The failure of an operation that needs a Node-like host. */
    def unsupported(operation: String): UnsupportedOperationException =
        UnsupportedOperationException(
            s"$operation needs a Node-like host (Node, Bun or Deno) with a process global; this host is ${Platform.host}"
        )

    private def envObject: js.UndefOr[js.Dynamic] =
        PlatformJs.jsGlobal("process").flatMap { process =>
            val env = process.env
            if js.typeOf(env) == "object" && env != null then env else js.undefined
        }

end NodeProcess
