package sb

import kyo.AllowUnsafe
import kyo.ffi.*

trait SbBindings extends Ffi:
    // Resolves to C symbol `read`, on the blocking allowlist. No @Ffi.blocking.
    def read(fd: Int, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int
end SbBindings

object SbBindings extends Ffi.Config(library = "sblk_lib")
