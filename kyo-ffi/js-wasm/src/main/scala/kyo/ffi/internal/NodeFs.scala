package kyo.ffi.internal

import kyo.Absent
import kyo.Maybe
import kyo.Present
import scala.scalajs.js

/** The members of Node's built-in `fs` module that kyo-ffi uses: `readFileSync` backs the [[BufferFactory]] mmap fallback, `existsSync`
  * the loader's check that a `KYO_FFI_<id>_PATH` override names a real file.
  *
  * Reached through `process.getBuiltinModule` ([[NodeFs.module]]) at the call, not a static `@JSImport`. A static import makes every
  * program that links the loader declare a module kind, and it is hoisted and resolved when the bundle loads, so a browser page that
  * merely links kyo-ffi would fail to load at all. `getBuiltinModule` needs Node 20.16 or 22.3, Deno 2.1, or Bun 1.2.6.
  */
@js.native
private[ffi] trait NodeFs extends js.Object:
    /** Read the whole file at `path` into a Node `Buffer` (a `Uint8Array` subclass). */
    def readFileSync(path: String): js.Dynamic = js.native

    /** `true` when `path` exists on the filesystem. */
    def existsSync(path: String): Boolean = js.native
end NodeFs

private[ffi] object NodeFs:

    /** `node:fs` on a host that provides it through `process.getBuiltinModule`, and `Absent` elsewhere, such as in a browser. */
    def module: Maybe[NodeFs] =
        // Must stay inline on the global selection: only then does Scala.js emit `typeof process`, safe on an undeclared identifier.
        if js.typeOf(js.Dynamic.global.process) == "undefined" then Absent
        else if js.typeOf(js.Dynamic.global.process.getBuiltinModule) != "function" then Absent
        else
            val fs = js.Dynamic.global.process.getBuiltinModule("node:fs")
            if js.isUndefined(fs) || fs == null then Absent else Present(fs.asInstanceOf[NodeFs])

    /** The failure of `operation` on a host without `node:fs`. */
    def unsupported(operation: String): UnsupportedOperationException =
        new UnsupportedOperationException(s"$operation needs Node's fs module (Node, Bun or Deno), which this host does not provide")

end NodeFs
