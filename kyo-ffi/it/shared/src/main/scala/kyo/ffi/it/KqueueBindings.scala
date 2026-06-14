package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** macOS/BSD kqueue bindings for integration testing.
  *
  * Binds `kqueue` and `kevent` plus the POSIX I/O helpers needed to exercise them (`pipe`, `write`, `read`, `close`). All symbols come from
  * libc.
  *
  * `struct kevent` and `struct timespec` are passed as `Buffer[Byte]` with manual byte-level layout to avoid struct codegen dependency.
  *
  * Every method is part of the unsafe FFI tier and takes a trailing `(using AllowUnsafe)`. The `@Ffi.blocking` syscalls (`kevent`, `write`,
  * `read`, `close`) return a `Fiber.Unsafe[<value>, Any]` the caller awaits (`.safe.get`); on JVM/Native the downcall runs inline
  * (already-completed fiber), on JS it is dispatched to a libuv worker (genuinely pending). The non-blocking calls return plain values.
  */
trait KqueueBindings extends Ffi:
    def kqueue()(using AllowUnsafe): Int

    @Ffi.blocking
    def kevent(kq: Int, changelist: Buffer[Byte], nchanges: Int, eventlist: Buffer[Byte], nevents: Int, timeout: Buffer[Byte])(using
        AllowUnsafe
    ): Fiber.Unsafe[Int, Any]

    def pipe(pipefd: Buffer[Int])(using AllowUnsafe): Int

    @Ffi.blocking
    def write(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Long, Any]

    @Ffi.blocking
    def read(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Long, Any]

    @Ffi.blocking
    def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]
end KqueueBindings

object KqueueBindings extends Ffi.Config(library = "c", headers = Chunk("sys/event.h"))
