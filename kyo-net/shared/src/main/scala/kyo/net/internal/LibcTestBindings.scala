package kyo.net.internal

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.ffi.Ffi

/** Binding to ISO C library calls that a test uses to leave the calling thread's `errno` non-zero.
  *
  * It has no production caller. It binds only ISO C symbols because those resolve on every host libc, the Windows CRT (`ucrtbase.dll`)
  * included, where [[kyo.net.internal.posix.SocketBindings]] fails to load for want of the POSIX socket symbols.
  */
private[net] trait LibcTestBindings extends Ffi:

    /** `int remove(const char* path)`. Returns `-1` with `errno` set (`ENOENT` for a missing path). */
    def remove(path: String)(using AllowUnsafe): Ffi.Outcome[Int]

end LibcTestBindings

private[net] object LibcTestBindings extends Ffi.Config(
        library = "c",
        headers = Chunk("stdio.h")
    )
