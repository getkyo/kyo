package kyo.net.internal.posix

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** macOS/BSD kqueue bindings, productionized from the kyo-ffi-it `KqueueBindings`.
  *
  * Every method is part of the unsafe FFI tier and takes a trailing `(using AllowUnsafe)` clause. The `@Ffi.blocking` methods (`kevent`,
  * `close`) return a `Fiber.Unsafe[<value>, Any]`: on JVM/Native the blocking downcall runs synchronously on the calling carrier and the
  * result is wrapped in an already-completed fiber; on JS it is dispatched on a libuv worker and the fiber is genuinely pending. Callers must
  * AWAIT the fiber rather than assume completion.
  *
  * `struct kevent` and `struct timespec` are now codegen case classes ([[KEvent]], [[Timespec]]) instead of hand-laid `Buffer[Byte]`: the
  * codegen derives the flat 32-byte / 16-byte layouts and self-checks them at impl class init. `kevent` takes a `Buffer[KEvent]` changelist
  * and a `Buffer[KEvent]` eventlist (the standard register-and-poll pattern) plus a single [[Timespec]] timeout passed by reference.
  *
  * Header-gated on `sys/event.h`: on a non-macOS/BSD build host the generator emits stubs.
  */
private[net] trait KqueueBindings extends Ffi:

    /** `int kqueue(void)`. Returns the kqueue fd or -1 with `errno`. */
    def kqueue()(using AllowUnsafe): Ffi.WithError[Int]

    /** `int kevent(int kq, const struct kevent* changelist, int nchanges, struct kevent* eventlist, int nevents, const struct timespec*
      * timeout)`. Submits `nchanges` interest changes and collects up to `nevents` ready events into `eventlist`, blocking up to `timeout`.
      * Returns the number of events placed in `eventlist`, 0 on timeout, -1 with `errno`. Blocking-annotated: the result is a `Fiber.Unsafe`
      * the caller must await.
      */
    @Ffi.blocking
    def kevent(
        kq: Int,
        changelist: Buffer[KEvent],
        nchanges: Int,
        eventlist: Buffer[KEvent],
        nevents: Int,
        timeout: Timespec
    )(using AllowUnsafe): Fiber.Unsafe[Ffi.WithError[Int], Any]

    /** Non-blocking synchronous companion of [[kevent]] for interest REGISTRATION only. A register-only `kevent` (a one-element changelist
      * with a zero `timeout`) never blocks: it applies the change and returns immediately. This overload omits `@Ffi.blocking`, so it returns
      * the rc inline (no `Fiber.Unsafe`) and an interest change is a single non-blocking syscall, not a carrier-parking blocking call that the
      * serial change worker would drain one await at a time (which collapses registration throughput under load). Bound to the same libc
      * `kevent` symbol via the `symbols` remap. MUST be called only with `timeout == 0`: a non-zero timeout would block, and on JS would freeze
      * the event loop. The polling path (a non-zero timeout that genuinely waits for events) uses the `@Ffi.blocking` [[kevent]].
      */
    def keventNow(
        kq: Int,
        changelist: Buffer[KEvent],
        nchanges: Int,
        eventlist: Buffer[KEvent],
        nevents: Int,
        timeout: Timespec
    )(using AllowUnsafe): Ffi.WithError[Int]

    /** `int close(int fd)`. Releases the kqueue fd. Blocking-annotated: the result is a `Fiber.Unsafe` the caller must await. */
    @Ffi.blocking
    def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

end KqueueBindings

private[net] object KqueueBindings extends Ffi.Config(
        library = "c",
        headers = Chunk("sys/event.h"),
        // keventNow is the non-blocking synchronous companion of kevent (register-only, timeout 0); it binds the same libc `kevent` symbol.
        symbols = Map(("keventNow", "kevent"))
    )
