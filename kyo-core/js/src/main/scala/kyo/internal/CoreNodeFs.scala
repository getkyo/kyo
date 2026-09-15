package kyo.internal

import kyo.Absent
import kyo.Maybe
import kyo.Present
import scala.scalajs.js

/** The members of Node's built-in `fs` module that [[NodeLineReader]] uses, the standard-input read behind `Console.readLine`.
  *
  * Reached through `process.getBuiltinModule` ([[CoreNodeFs.module]]), not a static `@JSImport`. A static import of `node:fs` is hoisted
  * and resolved when the bundle loads, so a browser page that merely links `Console` would fail to load at all. A lookup at the call finds
  * nothing on such a host, and the read fails on the channel `Console.readLine` declares.
  *
  * The name carries the module prefix so it does not clash with the similar facades in kyo-system's `kyo.internal`, which are visible here
  * through the same `private[kyo]` scope.
  */
@js.native
private[kyo] trait CoreNodeFs extends js.Object:

    /** `fs.readSync(fd, buffer, offset, length, position)`. Reads up to `length` bytes into `buffer` at `offset` and returns the count, 0 at
      * end of input. A `null` `position` reads from the descriptor's current position, which is what a stream needs.
      */
    def readSync(fd: Int, buffer: js.Dynamic, offset: Int, length: Int, position: js.Any): Int = js.native

end CoreNodeFs

private[kyo] object CoreNodeFs:

    /** `node:fs` on a host that provides it through `process.getBuiltinModule` (Node 22.3 or 20.16, Deno 2.1, Bun 1.2.6, and later), and
      * `Absent` elsewhere, such as in a browser.
      */
    def module: Maybe[CoreNodeFs] =
        PlatformJs.nodeBuiltin("node:fs").fold(Absent: Maybe[CoreNodeFs])(fs => Present(fs.asInstanceOf[CoreNodeFs]))

end CoreNodeFs
