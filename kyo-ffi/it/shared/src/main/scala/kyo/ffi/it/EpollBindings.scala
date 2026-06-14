package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Linux epoll bindings for integration testing.
  *
  * Binds `epoll_create1`, `epoll_ctl`, and `epoll_wait` plus the POSIX I/O helpers needed to exercise them (`pipe`, `write`, `read`,
  * `close`). All symbols come from libc.
  *
  * `struct epoll_event` is passed as `Buffer[Byte]` with manual byte-level layout to avoid struct codegen dependency.
  *
  * Every method is part of the unsafe FFI tier and takes a trailing `(using AllowUnsafe)`. The `@Ffi.blocking` syscalls (`epoll_wait`,
  * `write`, `read`, `close`) return a `Fiber.Unsafe[<value>, Any]` the caller awaits (`.safe.get`); on JVM/Native the downcall runs inline
  * (already-completed fiber), on JS it is dispatched to a libuv worker (genuinely pending). The non-blocking calls return plain values.
  */
trait EpollBindings extends Ffi:
    def epoll_create1(flags: Int)(using AllowUnsafe): Int
    def epoll_ctl(epfd: Int, op: Int, fd: Int, event: Buffer[Byte])(using AllowUnsafe): Int

    @Ffi.blocking
    def epoll_wait(epfd: Int, events: Buffer[Byte], maxevents: Int, timeout: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    def pipe(pipefd: Buffer[Int])(using AllowUnsafe): Int

    @Ffi.blocking
    def write(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Long, Any]

    @Ffi.blocking
    def read(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Long, Any]

    @Ffi.blocking
    def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]
end EpollBindings

object EpollBindings extends Ffi.Config(library = "c", headers = Chunk("sys/epoll.h"))
