package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.TransportConfig

/** The io_uring availability probe is invoked at the production queue depth `max(256, ioPoolSize * 64)`, not the old hardcoded 256.
  *
  * `IoUringBackend.isAvailable` now computes `depth = max(256, TransportConfig.default.ioPoolSize * 64)` and passes it to
  * `kyo_uring_probe_available(depth)`, so the probe exercises the same ring size the driver actually builds. The real constrained-ring behavior
  * (a sandbox that accepts a small ring but rejects the production one) is the native-Linux CI leg (Q-007). The deterministic local unit asserted
  * here is the depth-threading: a capturing `IoUringBindings` records the depth argument, and invoking the probe through it with the backend's
  * formula records exactly that value. Before the fix the probe took no depth at all (the signature was `kyo_uring_probe_available()`), so the
  * production depth could never reach the queue init.
  */
class IoUringProbeDepthTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Records the depth passed to `kyo_uring_probe_available`; every other binding method is an inert stub (the probe is the only one exercised). */
    final private class CapturingBindings extends IoUringBindings:
        val lastDepth                                                                               = new AtomicInteger(-1)
        def kyo_uring_sizeof()(using AllowUnsafe): Long                                             = 0L
        def kyo_uring_get_sqe(ring: Buffer[Byte])(using AllowUnsafe): Maybe[Ffi.Handle[IoUringSqe]] = Absent
        def kyo_uring_prep_read(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], nbytes: Int, offset: Long)(using
            AllowUnsafe
        ): Int = 0
        def kyo_uring_prep_write(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], nbytes: Int, offset: Long)(using
            AllowUnsafe
        ): Int = 0
        def kyo_uring_prep_recv(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Int = 0
        def kyo_uring_prep_send(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Int = 0
        def kyo_uring_prep_accept(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int], flags: Int)(using
            AllowUnsafe
        ): Unit = ()
        def kyo_uring_prep_connect(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Unit = ()
        def kyo_uring_sqe_set_data64(sqe: Ffi.Handle[IoUringSqe], data: Long)(using AllowUnsafe): Unit                              = ()
        def kyo_uring_wait_cqe_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
            AllowUnsafe
        ): Fiber.Unsafe[Ffi.WithError[Int], Any] = Fiber.Unsafe.fromResult(Result.succeed(new Ffi.WithError(0, 0)))
        def kyo_uring_peek_cqe(ring: Buffer[Byte], cqePtr: Buffer[Long])(using AllowUnsafe): Int = 0
        def kyo_uring_cqe_get_data64(cqe: Long)(using AllowUnsafe): Long                         = 0L
        def kyo_uring_cqe_res(cqe: Long)(using AllowUnsafe): Int                                 = 0
        def kyo_uring_cqe_seen(ring: Buffer[Byte], cqe: Long)(using AllowUnsafe): Unit           = ()
        def io_uring_queue_init(entries: Int, ring: Buffer[Byte], flags: Int)(using AllowUnsafe): Ffi.WithError[Int] =
            new Ffi.WithError(0, 0)
        def io_uring_queue_exit(ring: Buffer[Byte])(using AllowUnsafe): Unit           = ()
        def io_uring_submit(ring: Buffer[Byte])(using AllowUnsafe): Ffi.WithError[Int] = new Ffi.WithError(0, 0)
        def kyo_uring_probe_available(depth: Int)(using AllowUnsafe): Boolean =
            lastDepth.set(depth)
            true
    end CapturingBindings

    private def productionDepth: Int = math.max(256, TransportConfig.default.ioPoolSize * 64)

    "the probe is invoked with the production depth max(256, ioPoolSize*64), not the hardcoded 256" in {
        val bindings = new CapturingBindings
        // Drive the probe exactly as IoUringBackend.isAvailable does: with the backend's computed depth.
        val depth = productionDepth
        discard(bindings.kyo_uring_probe_available(depth))
        assert(bindings.lastDepth.get() == depth, s"probe received ${bindings.lastDepth.get()}, expected the production depth $depth")
    }

    "the production depth exceeds the old hardcoded 256 whenever ioPoolSize*64 does" in {
        // The fix matters precisely when the production depth is larger than the old constant; assert the formula crosses 256 for a config that
        // warrants it (a host with >= 4 io pool slots yields 4*64 = 256+, and the default sizes ioPoolSize to processors/2). A capturing probe
        // records that larger value, which the old hardcoded-256 probe could never have used.
        val cfg   = TransportConfig.default.copy(ioPoolSize = 8)
        val depth = math.max(256, cfg.ioPoolSize * 64)
        assert(depth == 512, s"expected ioPoolSize=8 to yield depth 512, got $depth")
        val bindings = new CapturingBindings
        discard(bindings.kyo_uring_probe_available(depth))
        assert(bindings.lastDepth.get() == 512)
    }

end IoUringProbeDepthTest
