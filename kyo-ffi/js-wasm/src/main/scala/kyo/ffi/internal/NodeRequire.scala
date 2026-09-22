package kyo.ffi.internal

import scala.scalajs.js

/** Node's `require`, however the running module kind exposes it.
  *
  * A CommonJS bundle has `require` as a global. An ESModule bundle, which is every Scala.js Wasm build, has none
  * and has to build one from `node:module`.createRequire. Every lookup of koffi or of a native package goes
  * through here, because one that reached only for the global would answer "absent" on an ESModule build and its
  * caller reads that as the package missing from the machine, degrading to a slower tier in silence.
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

    /** Anchored at the entry script, falling back to the working directory with a trailing separator when there is
      * no entry script to name (a REPL, `node -e`).
      *
      * The anchor is what createRequire resolves `node_modules` upward from, and the global `require` it stands in
      * for is anchored at the importing file. Anchoring at the working directory instead makes resolution depend
      * on where the process was STARTED: an application launched from anywhere outside its own tree, which is what
      * a service manager with its own WorkingDirectory or a container WORKDIR does, then fails to find a package
      * sitting next to its bundle. Nothing reports that, because the caller reads an unresolvable package as one
      * the machine does not have.
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
                    val req = nodeModule.applyDynamic("createRequire")(anchor(proc).asInstanceOf[js.Any])
                    if js.isUndefined(req) || req == null then None else Some(req)
                end if
            end if
        catch case _: Throwable => None
    end fromNodeModule

    /** Reachable from tests so the choice of anchor can be asserted on its own: every anchor resolves a builtin,
      * so a test that only loads one cannot tell the entry script from the working directory.
      */
    private[ffi] def anchor(proc: js.Dynamic): String =
        val argv  = proc.selectDynamic("argv")
        val entry =
            if js.isUndefined(argv) || argv == null then null
            else
                val a = argv.asInstanceOf[js.Array[String]]
                if a.length > 1 && a(1) != null && a(1).nonEmpty then a(1) else null
        if entry != null then entry
        else proc.applyDynamic("cwd")().asInstanceOf[String] + "/"
    end anchor

end NodeRequire
