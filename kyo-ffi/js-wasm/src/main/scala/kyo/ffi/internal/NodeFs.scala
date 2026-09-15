package kyo.ffi.internal

import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.internal.Platform
import kyo.internal.PlatformJs
import scala.scalajs.js

/** The members of Node's built-in `fs` module that kyo-ffi uses: `readFileSync` backs the [[BufferFactory]] mmap fallback, `existsSync`
  * the loader's check that a `KYO_FFI_<id>_PATH` override names a real file.
  *
  * Reached through `process.getBuiltinModule` ([[NodeFs.module]]) at the call, not a static `@JSImport`: a static import of `node:fs` is
  * hoisted and resolved when the bundle loads, so a browser page that merely links kyo-ffi would fail to load at all.
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
        PlatformJs.nodeBuiltin("node:fs").fold(Absent: Maybe[NodeFs])(fs => Present(fs.asInstanceOf[NodeFs]))

    /** The failure of `operation` on a host without `node:fs`. */
    def unsupported(operation: String): UnsupportedOperationException =
        new UnsupportedOperationException(s"$operation needs Node's fs module (Node, Bun or Deno); this host is ${Platform.host}")

end NodeFs
