package kyo.ffi.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Facade over Node's built-in `fs` module for the [[BufferFactory]] mmap fallback.
  *
  * Imported through the `node:fs` specifier: `@JSImport` compiles to `require(...)` under the CommonJS
  * (js) backend and to `import` under the ESModule (wasm) backend, so the single mmap path serves both
  * platforms. A `js.Dynamic.global.require("fs")` call would not: `require` is not a global in a Node ES
  * module, the module kind the WebAssembly backend mandates. The `node:` scheme is required because under
  * ESModule a namespace import of the bare id ("fs") does not reliably expose the module's named members.
  * Only `readFileSync` is declared; it is the one member the fallback needs.
  */
@js.native
@JSImport("node:fs", JSImport.Namespace)
private[ffi] object NodeFs extends js.Object:
    /** Read the whole file at `path` into a Node `Buffer` (a `Uint8Array` subclass). */
    def readFileSync(path: String): js.Dynamic = js.native
end NodeFs
