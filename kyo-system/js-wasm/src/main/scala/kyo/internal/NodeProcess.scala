package kyo.internal

import kyo.discard
import scala.scalajs.js

/** The `process` global for the Node backends of Path and Process, read without the `ReferenceError` a bare `process` throws on a host that
  * does not declare it, such as a browser.
  *
  * A read that has a meaningful answer on such a host gets it: an environment variable is unset and a child inherits nothing. An operation
  * that has none, such as the working directory or the pid a lock records, fails with [[unsupported]], which names the operation and the
  * host.
  */
private[kyo] object NodeProcess:

    /** `process.env[name]` when it is a string, and `null` otherwise, including on a host without `process`. */
    def env(name: String): String =
        envObject.fold(null: String) { env =>
            val value = env.selectDynamic(name)
            if js.typeOf(value) == "string" then value.asInstanceOf[String] else null
        }

    /** A new object holding a copy of `process.env`, and an empty one on a host without `process`. */
    def envCopy(): js.Dynamic =
        val copy = js.Dynamic.literal()
        envObject.foreach(env => discard(js.Dynamic.global.Object.assign(copy, env)))
        copy
    end envCopy

    /** `process` on a Node-like host, for `operation`; anywhere else, throws [[unsupported]]. */
    def require(operation: String): js.Dynamic =
        if Platform.isNodeLike then js.Dynamic.global.process
        else throw unsupported(operation)

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
