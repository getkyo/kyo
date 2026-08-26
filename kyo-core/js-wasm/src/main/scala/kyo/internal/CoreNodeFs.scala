package kyo.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Facade over Node's built-in `fs` module for [[NodeLineReader]], the standard-input read behind `Console.readLine`.
  *
  * Imported through the `node:fs` specifier: `@JSImport` compiles to `require(...)` under the CommonJS (js) backend and to `import` under
  * the ESModule (wasm) backend, so one source serves both. A `js.Dynamic.global.require("fs")` call would not: `require` is not a global in
  * a Node ES module, the module kind the WebAssembly backend mandates, and reaching for it there fails with
  * `ReferenceError: require is not defined`. The `node:` scheme is required because under ESModule a namespace import of the bare id
  * ("fs") does not reliably expose the module's named members.
  *
  * The name carries the module prefix so it does not clash with the similar facades in kyo-system's and kyo-http's `kyo.internal`, which
  * are visible here through the same `private[kyo]` scope.
  */
@js.native
@JSImport("node:fs", JSImport.Namespace)
private[kyo] object CoreNodeFs extends js.Object:

    /** `fs.readSync(fd, buffer, offset, length, position)`. Reads up to `length` bytes into `buffer` at `offset` and returns the count, 0 at
      * end of input. A `null` `position` reads from the descriptor's current position, which is what a stream needs.
      */
    def readSync(fd: Int, buffer: js.Dynamic, offset: Int, length: Int, position: js.Any): Int = js.native

end CoreNodeFs
