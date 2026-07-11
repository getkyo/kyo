package kyo.internal

import kyo.Maybe
import kyo.Present
import scala.scalajs.js

/** Browser-safe access to Node's `process` global.
  *
  * Scala.js emits `js.Dynamic.global.process` as a bare `process` identifier read. Under Node that global is declared, but in a browser it is
  * undeclared, so an unconditional read throws `ReferenceError: process is not defined` and crashes the module the moment it loads. A `typeof`
  * on a bare identifier is the one form that does not throw when the identifier is undeclared (it yields `"undefined"`), so every access to the
  * `process` global routes through this guard. In a browser the process is absent and callers degrade gracefully; under Node the real object
  * comes through unchanged, giving results identical to a direct read.
  */
private[kyo] object NodeGlobal:

    /** The Node `process` global when it is declared, or `Absent` in a browser where it is not. */
    def process: Maybe[js.Dynamic] =
        if js.typeOf(js.Dynamic.global.process) == "undefined" then Maybe.empty
        else Maybe(js.Dynamic.global.process)

    /** `process.env` when the process global is declared and exposes it, or an empty object otherwise.
      *
      * Returning an empty object lets callers read variables and spread the environment uniformly: an absent variable reads back as
      * `undefined` and a spread contributes nothing, matching the browser fallback without a per-call guard.
      */
    def env: js.Dynamic =
        process match
            case Present(proc) if js.typeOf(proc.env) != "undefined" => proc.env
            case _                                                   => js.Dynamic.literal()

end NodeGlobal
