package kyo.ffi.internal

import scala.scalajs.js

/** Node's `require`, however the running module kind exposes it.
  *
  * A CommonJS bundle has `require` as a global. An ESModule bundle has none, and has to build one from
  * `node:module`.createRequire; every Wasm axis and kyo-ffi's own js axis link that way, as does any consumer
  * that sets `ModuleKind.ESModule`. Both koffi loading and native package resolution go through here, because a
  * lookup that reached only for the global would answer "absent" on an ESModule build and every caller reads
  * that answer as the package being missing from the machine, degrading to a slower tier in silence.
  */
private[ffi] object NodeRequire:

    /** The require function, or `None` when this is not Node. Callers use it as `js.Dynamic` so that both
      * `req("pkg")` and `req.resolve("pkg")` are reachable, which the global and the constructed one both support.
      */
    def find(): Option[js.Dynamic] =
        fromGlobal().orElse(fromNodeModule())

    /** The `js.typeOf` guard is load-bearing, not defensive: reading a global that was never declared throws a
      * ReferenceError, and an ESModule bundle is exactly where `require` is undeclared. `js.typeOf` compiles to a
      * bare `typeof`, which is the one read of an undeclared name that is allowed to answer instead of throwing.
      */
    private def fromGlobal(): Option[js.Dynamic] =
        if js.typeOf(js.Dynamic.global.selectDynamic("require")) == "undefined" then None
        else
            val req = js.Dynamic.global.selectDynamic("require")
            if req == null then None else Some(req)
    end fromGlobal

    /** Anchored at the working directory with a trailing separator, so createRequire treats it as a directory and
      * NODE_PATH governs the search the same way it does for the global.
      *
      * Reachable from tests because it is the branch an ESModule bundle depends on and the only one a test can
      * pin: a runner that happens to expose a global `require` would otherwise satisfy [[find]] through
      * [[fromGlobal]] and leave this path unexercised.
      */
    private[ffi] def fromNodeModule(): Option[js.Dynamic] =
        try
            val proc = js.Dynamic.global.selectDynamic("process")
            if js.isUndefined(proc) || proc == null then None
            else
                val nodeModule = proc.applyDynamic("getBuiltinModule")("node:module")
                if js.isUndefined(nodeModule) || nodeModule == null then None
                else
                    val cwd = proc.applyDynamic("cwd")().asInstanceOf[String]
                    val req = nodeModule.applyDynamic("createRequire")((cwd + "/").asInstanceOf[js.Any])
                    if js.isUndefined(req) || req == null then None else Some(req)
                end if
            end if
        catch case _: Throwable => None
    end fromNodeModule

end NodeRequire
