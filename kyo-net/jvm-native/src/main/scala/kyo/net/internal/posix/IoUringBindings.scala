package kyo.net.internal.posix

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.Maybe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Marker for the io_uring ring handle. The ring itself lives in a caller-owned `Buffer[Byte]` of `kyo_uring_sizeof()` bytes (the SQ/CQ
  * mmaps are owned internally by liburing).
  */
final private[net] class IoUring

/** Marker for an SQE pointer. The submission-queue entry is a 64-byte region owned by the ring; user code never reads it field by field, it
  * only fills it through the `kyo_uring_prep_*` helpers. Held as an opaque [[Ffi.Handle]] (the SQE's anonymous same-width unions and
  * `__u8` pad arrays cannot be modeled as a case class).
  */
final private[net] class IoUringSqe

/** liburing surface bound through the `kyo_uring.c` shim.
  *
  * Most of liburing's hot path (`io_uring_get_sqe`, every `io_uring_prep_*`, the `set_data64` / `cqe_get_data64` / `cqe_seen` accessors, and
  * `io_uring_wait_cqe` itself) is `static inline` in `<liburing.h>` with no exported symbol, so it is unreachable by Panama's
  * `SymbolLookup.libraryLookup` on JVM or Scala Native's `@link`. Those calls route through one-line `kyo_uring_*` C wrappers the shim
  * exports. The four real liburing exports are bound directly.
  *
  * The shim and its statically-linked liburing are built by the kyo-net build; this trait binds the contract the C must satisfy. Header-gated on
  * `liburing.h`: on a host without liburing the generator emits stubs and the io_uring backend probe reports unavailable.
  *
  * Every method is part of the unsafe FFI tier and takes a trailing `(using AllowUnsafe)` clause. The one `@Ffi.blocking` method
  * (`kyo_uring_wait_cqe_timeout`) returns a `Fiber.Unsafe[<value>, Any]`: on JVM/Native the bounded wait runs synchronously on the calling
  * carrier and the result is wrapped in an already-completed fiber; on JS it is dispatched on a libuv worker and the fiber is genuinely
  * pending. Callers must AWAIT the fiber rather than assume completion.
  */
private[net] trait IoUringBindings extends Ffi:

    // --- real liburing exports, bound directly (no shim) ---

    /** `int io_uring_queue_init(unsigned entries, struct io_uring* ring, unsigned flags)`. Sets up the ring in the caller's `Buffer[Byte]`.
      * Returns 0 on success, `-errno` on failure. Raw signed rc, not clamped to -1.
      */
    def io_uring_queue_init(entries: Int, ring: Buffer[Byte], flags: Int)(using AllowUnsafe): Int

    /** `void io_uring_queue_exit(struct io_uring* ring)`. Tears the ring down. */
    def io_uring_queue_exit(ring: Buffer[Byte])(using AllowUnsafe): Unit

    /** `int io_uring_submit(struct io_uring* ring)`. Submits all prepared SQEs. Returns the number submitted, `-errno` on failure.
      * Raw signed rc, not clamped to -1.
      */
    def io_uring_submit(ring: Buffer[Byte])(using AllowUnsafe): Int

    // --- kyo_uring.c shim wrappers over the static-inline liburing helpers ---

    /** `sizeof(struct io_uring)`: the byte size to allocate for the ring buffer. */
    def kyo_uring_sizeof()(using AllowUnsafe): Long

    /** `io_uring_get_sqe`. Returns the next free SQE, or `Absent` when the submission queue is full. */
    def kyo_uring_get_sqe(ring: Buffer[Byte])(using AllowUnsafe): Maybe[Ffi.Handle[IoUringSqe]]

    /** `io_uring_prep_read(sqe, fd, buf, nbytes, offset)`. Returns 0 when the SQE was prepared, -1 when `nbytes` was negative and the SQE was
      * NOT prepared (the C trust-boundary non-negativity guard; a negative length would wrap to a huge unsigned at the C cast and become an
      * out-of-bounds kernel read). The caller maps a non-zero return to an observable failure (no silent SQE drop).
      */
    def kyo_uring_prep_read(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], nbytes: Int, offset: Long)(using AllowUnsafe): Int

    /** `io_uring_prep_write(sqe, fd, buf, nbytes, offset)`. Returns 0 when the SQE was prepared, -1 when `nbytes` was negative and the SQE was
      * NOT prepared (the same C trust-boundary non-negativity guard as [[kyo_uring_prep_read]]).
      */
    def kyo_uring_prep_write(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], nbytes: Int, offset: Long)(using AllowUnsafe): Int

    /** `io_uring_prep_recv(sqe, fd, buf, len, flags)`. Returns 0 when the SQE was prepared, -1 when `len` was negative and the SQE was NOT
      * prepared (the C trust-boundary non-negativity guard; a negative `len` would wrap to a huge `size_t` at the C cast and become an
      * out-of-bounds kernel read). The caller maps a non-zero return to an observable failure (fails the read promise, no silent SQE drop).
      */
    def kyo_uring_prep_recv(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Int

    /** `io_uring_prep_send(sqe, fd, buf, len, flags)`. Returns 0 when the SQE was prepared, -1 when `len` was negative and the SQE was NOT
      * prepared (the same C trust-boundary non-negativity guard as [[kyo_uring_prep_recv]]).
      */
    def kyo_uring_prep_send(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Int

    /** `io_uring_prep_accept(sqe, fd, addr, addrlen, flags)`. `addr`/`addrlen` are a [[SockAddr]] buffer and its `socklen_t*` length. */
    def kyo_uring_prep_accept(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int], flags: Int)(using
        AllowUnsafe
    ): Unit

    /** `io_uring_prep_connect(sqe, fd, addr, addrlen)`. */
    def kyo_uring_prep_connect(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Unit

    /** Multishot poll: `IORING_OP_POLL_ADD | IORING_POLL_ADD_MULTI`. One submission re-fires a CQE every time `fd` becomes ready for
      * `pollMask` (e.g. `POLLIN`), staying armed across completions (each carries `IORING_CQE_F_MORE`). Armed on the driver's wake eventfd so a
      * cross-carrier `kyo_uring_eventfd_write` returns the parked reap wait promptly instead of leaving the reap loop parked indefinitely.
      */
    def kyo_uring_prep_poll_multishot(sqe: Ffi.Handle[IoUringSqe], fd: Int, pollMask: Int)(using AllowUnsafe): Unit

    /** `io_uring_sqe_set_data64(sqe, data)`. Stores the per-op key the completion is matched against. */
    def kyo_uring_sqe_set_data64(sqe: Ffi.Handle[IoUringSqe], data: Long)(using AllowUnsafe): Unit

    /** Bounded wait: wraps `io_uring_wait_cqes` with a `__kernel_timespec` so the carrier is returned each `timeoutNs` cycle. The
      * ready CQE pointer is written into the 1-element `cqePtr` buffer. Returns 0 on a ready CQE, `-ETIME` on timeout, `-errno` otherwise.
      * Raw signed rc, not clamped to -1 (the raw `-ETIME` must reach `isTimeout` unchanged so the reap loop treats empty turns as non-fatal).
      * Blocking-annotated: the result is a `Fiber.Unsafe` the caller must await.
      */
    @Ffi.blocking
    def kyo_uring_wait_cqe_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
        AllowUnsafe
    ): Fiber.Unsafe[Int, Any]

    /** Fused submit-and-wait: one `io_uring_enter` both submits prepared SQEs and waits for the next CQE. The ready CQE pointer
      * is written into the 1-element `cqePtr` buffer. Returns 0 on a ready CQE, `-ETIME` on timeout, `-errno` otherwise.
      * Raw signed rc, not clamped to -1. Blocking-annotated: the result is a `Fiber.Unsafe` the caller must await.
      */
    @Ffi.blocking
    def kyo_uring_submit_and_wait_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
        AllowUnsafe
    ): Fiber.Unsafe[Int, Any]

    /** Kernel release as a packed `major*1000+minor` int (e.g. 6.1 -> 6001), via `uname(2)` in the C shim. Returns 0 on non-Linux or
      * `uname` failure. Used by the SETUP-flag tier selector to pick the flag set that is safe on the running kernel.
      */
    def kyo_uring_kernel_version()(using AllowUnsafe): Int

    /** Read the kernel feature bits from an already-initialized ring (`ring->features`, set by `io_uring_queue_init`).
      * `IORING_FEAT_NODROP` (bit 1, kernel >= 5.5): the kernel never drops a CQE; it applies backpressure on submit
      * instead. When set, the wake eventfd's multishot CQE cannot be lost under any load, making the indefinite park
      * safe by kernel contract.
      */
    def kyo_uring_get_features(ring: Buffer[Byte])(using AllowUnsafe): Int

    /** Multishot accept: `IORING_OP_ACCEPT | IORING_ACCEPT_MULTISHOT`. One submission re-fires an accept CQE for each incoming connection
      * until a completion arrives without `IORING_CQE_F_MORE`, at which point the key is removed and re-armed.
      */
    def kyo_uring_prep_multishot_accept(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int], flags: Int)(using
        AllowUnsafe
    ): Unit

    /** CQE flags reader: the `IORING_CQE_F_MORE` bit (= 2) signals that more completions are coming on this key without re-arming.
      * Returns 0 when the `cqe` pointer is 0.
      */
    def kyo_uring_cqe_get_flags(cqe: Long)(using AllowUnsafe): Int

    /** `IORING_RECV_MULTISHOT` flag value. Reserved for a future multishot recv; not currently used. The recv path is single-shot, because a
      * multishot recv must receive into a registered provided-buffer ring rather than a single per-op buffer, which is not yet implemented.
      */
    def kyo_uring_recv_multishot_flag()(using AllowUnsafe): Int

    /** `io_uring_peek_cqe`. Drains the next already-ready CQE into `cqePtr` without blocking. Returns 0 when one was placed, nonzero when the
      * completion queue is empty.
      */
    def kyo_uring_peek_cqe(ring: Buffer[Byte], cqePtr: Buffer[Long])(using AllowUnsafe): Int

    /** `io_uring_cqe_get_data64(cqe)`. Reads back the key set with `kyo_uring_sqe_set_data64`. `cqe` is the pointer drained into `cqePtr`. */
    def kyo_uring_cqe_get_data64(cqe: Long)(using AllowUnsafe): Long

    /** Result field of the CQE: `>= 0` is the byte count or accepted fd, `< 0` is `-errno`. */
    def kyo_uring_cqe_res(cqe: Long)(using AllowUnsafe): Int

    /** `io_uring_cqe_seen(ring, cqe)`. Advances the completion queue past `cqe`. */
    def kyo_uring_cqe_seen(ring: Buffer[Byte], cqe: Long)(using AllowUnsafe): Unit

    /** `eventfd(initval, flags)` (via the shim). Creates the reap-loop wakeup counter; the driver passes `EFD_NONBLOCK | EFD_CLOEXEC`.
      * Returns the fd, or `-1` on failure.
      */
    def kyo_uring_eventfd_create(initval: Int, flags: Int)(using AllowUnsafe): Int

    /** `eventfd_write(fd, 1)` (via the shim). Adds 1 to the counter, making the eventfd readable so the armed multishot poll fires and the
      * parked reap wait returns. Atomic and safe from any carrier without touching the SQ. Returns 0, or `-1` on failure.
      */
    def kyo_uring_eventfd_write(fd: Int)(using AllowUnsafe): Int

    /** `eventfd_read(fd, &v)` (via the shim). Drains the counter to 0 after a wake so the level-readable eventfd does not immediately re-fire
      * the poll. Returns 0, or `-1`/`EAGAIN` when already drained.
      */
    def kyo_uring_eventfd_read(fd: Int)(using AllowUnsafe): Int

    /** `close(fd)` (via the shim). Closes the wake eventfd at ring teardown, guarded against any in-flight `kyo_uring_eventfd_write`. Returns 0
      * or `-1`/`errno`.
      */
    def kyo_uring_eventfd_close(fd: Int)(using AllowUnsafe): Int

    /** One-shot availability probe: `io_uring_queue_init(depth, ...)` then `io_uring_queue_exit`. The io_uring backend's `isAvailable` calls this
      * with the production depth (`max(256, ioPoolSize * 64)`), so the probe exercises the same queue size the driver actually builds: a sandbox
      * that initializes a token ring but rejects the production-depth ring is then reported unavailable instead of selected and failing at build.
      */
    def kyo_uring_probe_available(depth: Int)(using AllowUnsafe): Boolean

end IoUringBindings

private[net] object IoUringBindings extends Ffi.Config(
        library = "kyonet_posix_uring",
        headers = Chunk("liburing.h"),
        // The io_uring shim (kyo_uring.c) is compiled INTO the Scala Native binary (copied under
        // `resources/scala-native` by KyoFfiPlugin), so the generated Native binding must NOT emit
        // `@link("kyonet_posix_uring")` (there is no such shared library to find a `-l` for). The
        // shim's `io_uring_*` symbols resolve against `-luring` surfaced via nativeConfig.linkingOptions
        // (Linux only). JVM/JS still load the plugin-compiled shared library at runtime.
        nativeBundled = true
    )
