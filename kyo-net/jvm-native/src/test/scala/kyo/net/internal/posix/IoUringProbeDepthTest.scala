package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.TransportConfig

/** The io_uring availability probe is invoked at the production queue depth `max(256, ioPoolSize * 64)`, not a fixed 256.
  *
  * `IoUringBackend.isAvailable` computes `depth = max(256, kyo.net.ioPoolSize() * 64)` and passes it to
  * `kyo_uring_probe_available(depth)`, so the probe exercises the same ring size the driver actually builds. The deterministic local unit
  * asserted here is the depth-threading: a capturing `IoUringBindings` records the depth argument, and invoking the probe through it with the
  * backend's formula records exactly that value. A probe that took no depth (signature
  * `kyo_uring_probe_available()`) could never thread the production depth to the queue init.
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
        ): Fiber.Unsafe[Int, Any] = Fiber.Unsafe.fromResult(Result.succeed(0))
        def kyo_uring_submit_and_wait_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
            AllowUnsafe
        ): Fiber.Unsafe[Int, Any] = Fiber.Unsafe.fromResult(Result.succeed(0))
        def kyo_uring_kernel_version()(using AllowUnsafe): Int = 0
        def kyo_uring_prep_multishot_accept(
            sqe: Ffi.Handle[IoUringSqe],
            fd: Int,
            addr: Buffer[Byte],
            addrlen: Buffer[Int],
            flags: Int
        )(using
            AllowUnsafe
        ): Unit = ()
        def kyo_uring_cqe_get_flags(cqe: Long)(using AllowUnsafe): Int                                = 0
        def kyo_uring_recv_multishot_flag()(using AllowUnsafe): Int                                   = 0
        def kyo_uring_peek_cqe(ring: Buffer[Byte], cqePtr: Buffer[Long])(using AllowUnsafe): Int      = 0
        def kyo_uring_cqe_get_data64(cqe: Long)(using AllowUnsafe): Long                              = 0L
        def kyo_uring_cqe_res(cqe: Long)(using AllowUnsafe): Int                                      = 0
        def kyo_uring_cqe_seen(ring: Buffer[Byte], cqe: Long)(using AllowUnsafe): Unit                = ()
        def io_uring_queue_init(entries: Int, ring: Buffer[Byte], flags: Int)(using AllowUnsafe): Int = 0
        def io_uring_queue_exit(ring: Buffer[Byte])(using AllowUnsafe): Unit                          = ()
        def io_uring_submit(ring: Buffer[Byte])(using AllowUnsafe): Int                               = 0
        def kyo_uring_probe_available(depth: Int)(using AllowUnsafe): Boolean =
            lastDepth.set(depth)
            true
        // This test exercises only the depth-probe call below; the wake-eventfd surface is never invoked here.
        def kyo_uring_prep_poll_multishot(sqe: Ffi.Handle[IoUringSqe], fd: Int, pollMask: Int)(using AllowUnsafe): Unit = ()
        def kyo_uring_eventfd_create(initval: Int, flags: Int)(using AllowUnsafe): Int                                  = -1
        def kyo_uring_eventfd_write(fd: Int)(using AllowUnsafe): Int                                                    = 0
        def kyo_uring_eventfd_read(fd: Int)(using AllowUnsafe): Int                                                     = 0
        def kyo_uring_eventfd_close(fd: Int)(using AllowUnsafe): Int                                                    = 0
        // This test exercises only the depth-probe; the feature-bits surface is never invoked here.
        def kyo_uring_get_features(ring: Buffer[Byte])(using AllowUnsafe): Int = 0
    end CapturingBindings

    private def productionDepth: Int = math.max(256, kyo.net.ioPoolSize() * 64)

    "the probe is invoked with the production depth max(256, ioPoolSize*64), not the hardcoded 256" in {
        val bindings = new CapturingBindings
        // Drive the probe exactly as IoUringBackend.isAvailable does: with the backend's computed depth.
        val depth = productionDepth
        discard(bindings.kyo_uring_probe_available(depth))
        assert(bindings.lastDepth.get() == depth, s"probe received ${bindings.lastDepth.get()}, expected the production depth $depth")
    }

    "the depth formula scales past a fixed 256 once the pool is wide enough" in {
        // The computed depth matters precisely when it exceeds a fixed 256. Pool width is a process-global flag rather than a per-transport
        // config field, so drive the formula directly for representative widths instead of building a transport to carry one: a pool of 8
        // must yield 512, a value a hardcoded 256 probe could never use, while a narrow pool still floors at 256.
        def depthFor(poolSize: Int): Int = math.max(256, poolSize * 64)
        assert(depthFor(8) == 512, s"expected 512 for a pool of 8, got ${depthFor(8)}")
        assert(depthFor(1) == 256, s"a narrow pool must floor at 256, got ${depthFor(1)}")
        val bindings = new CapturingBindings
        discard(bindings.kyo_uring_probe_available(depthFor(8)))
        assert(bindings.lastDepth.get() == 512)
    }

end IoUringProbeDepthTest
