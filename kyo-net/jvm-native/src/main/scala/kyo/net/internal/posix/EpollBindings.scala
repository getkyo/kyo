package kyo.net.internal.posix

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Linux epoll bindings, productionized from the kyo-ffi-it `EpollBindings`.
  *
  * Every method is part of the unsafe FFI tier and takes a trailing `(using AllowUnsafe)` clause. The `@Ffi.blocking` methods (`epoll_wait`,
  * `close`) return a `Fiber.Unsafe[<value>, Any]`: on JVM/Native the blocking downcall runs synchronously on the calling carrier and the
  * result is wrapped in an already-completed fiber; on JS it is dispatched on a libuv worker and the fiber is genuinely pending. Callers must
  * AWAIT the fiber rather than assume completion.
  *
  * `struct epoll_event` is passed as a `Buffer[Byte]` with a hand-laid, arch-aware layout ([[EpollEvent$]]), mirroring the integration-test
  * version. A codegen struct cannot model it: the kernel struct is `__attribute__((packed))` on x86_64 (12 bytes, `data` at offset 4) but
  * naturally aligned on aarch64 (16 bytes, `data` at offset 8), so no single fixed layout is correct on both architectures. The
  * [[EpollEvent$]] companion computes the offsets and size per host arch and encodes/decodes the event; the bindings marshal the buffer as
  * the raw `struct epoll_event*` pointer.
  *
  * Header-gated on `sys/epoll.h`: on a non-Linux build host the generator emits stubs, so the file compiles everywhere and the
  * backend probe simply reports unavailable off Linux.
  */
private[net] trait EpollBindings extends Ffi:

    /** `int epoll_create1(int flags)`. Returns the epoll fd or -1 with `errno`. */
    def epoll_create1(flags: Int)(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int epoll_ctl(int epfd, int op, int fd, struct epoll_event* event)`. Adds, modifies, or removes interest in `fd`. `event` is a
      * single `struct epoll_event` encoded into a `Buffer[Byte]` via [[EpollEvent.encode]], carrying the interest mask and the user key.
      */
    def epoll_ctl(epfd: Int, op: Int, fd: Int, event: Buffer[Byte])(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int epoll_wait(int epfd, struct epoll_event* events, int maxevents, int timeout)`. Blocks up to `timeout` ms; returns the number of
      * ready events written into the `events` array, 0 on timeout, -1 with `errno`. The array is a `Buffer[Byte]` of
      * `maxevents * EpollEvent.size` bytes; each ready entry is read back via [[EpollEvent.decode]]. Blocking-annotated: the result is a
      * `Fiber.Unsafe` the caller must await.
      */
    @Ffi.blocking
    def epoll_wait(epfd: Int, events: Buffer[Byte], maxevents: Int, timeout: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any]

    /** `int eventfd(unsigned int initval, int flags)`. Creates the poll-loop wakeup eventfd (counter object). Created with `EFD_NONBLOCK |
      * EFD_CLOEXEC` and registered in the epoll set for read interest; a write to it makes a parked `epoll_wait` return so the change FIFO is
      * drained at once rather than waiting for the next I/O event. Returns the fd or -1 with `errno`. Non-blocking downcall (returns the rc inline).
      */
    def eventfd(initval: Int, flags: Int)(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int eventfd_write(int fd, uint64_t value)` (glibc helper). Adds `value` to the eventfd counter, making it read-ready and waking a
      * parked `epoll_wait`. The wake path writes 1. Non-blocking; returns 0 or -1 with `errno`.
      */
    def eventfd_write(fd: Int, value: Long)(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int eventfd_read(int fd, uint64_t* value)` (glibc helper). Reads and clears the eventfd counter into the 8-byte `value` buffer. The
      * poll loop calls it after an eventfd read-readiness to drain the counter (with `EFD_NONBLOCK`, a drained counter returns -1/EAGAIN, which
      * the drain loop treats as "no more wake bytes"). Non-blocking; returns 0 or -1 with `errno`.
      */
    def eventfd_read(fd: Int, value: Buffer[Byte])(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int close(int fd)`. Releases the epoll fd. Blocking-annotated: the result is a `Fiber.Unsafe` the caller must await. */
    @Ffi.blocking
    def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

end EpollBindings

private[net] object EpollBindings extends Ffi.Config(
        library = "c",
        headers = Chunk("sys/epoll.h", "sys/eventfd.h")
    )
