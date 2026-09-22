package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Cancels a test on a host where io_uring is not usable, for every platform that has a suite touching the ring.
  *
  * Shared rather than per-platform because the probe has to match [[IoUringDriver]]'s own init exactly, and a second
  * copy is a second thing to keep matching.
  */
object UringGate:

    /** Cancel the test if io_uring is unavailable at the PRODUCTION ring depth.
      *
      * Probes at max(256, ioPoolSize*64), the formula `IoUringDriver` uses, not depth 2, so the gate never reports
      * available where `IoUringDriver.init` would fail. A container-level cgroup `io_uring.max` caps the entries a
      * process may have in flight: a depth-2 ring stays under it and a production-depth ring returns ENOENT. The
      * `--privileged` flag does not lift that cap, it only relaxes seccomp. The probe ring is closed immediately.
      */
    def assumeUring()(using Frame): Unit =
        if !PosixConstants.isLinux then throw new kyo.test.TestCancelled("io_uring is Linux-only")
        // Any failure reads as unavailable: a host without liburing throws from the load rather than returning.
        val available =
            try probeRing()
            catch case _: Throwable => false
        if !available then
            throw new kyo.test.TestCancelled("io_uring unavailable at production depth on this kernel/runtime (needs Linux >= 5.6)")
    end assumeUring

    /** Whether a ring initializes at the production depth, releasing the probe buffer on every exit including a throw
      * from the init, which the caller's catch would otherwise swallow along with the buffer.
      */
    private def probeRing()(using Frame): Boolean =
        import AllowUnsafe.embrace.danger
        val uring = Ffi.load[IoUringBindings]
        val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
        try
            // liburing returns -errno directly rather than setting the global one, so a captured errno left by an
            // earlier syscall would report unavailable on a ring that initialized. The return value is the reading.
            val rc = uring.io_uring_queue_init(math.max(256, kyo.net.ioPoolSize() * 64), ring, 0)
            if rc == 0 then uring.io_uring_queue_exit(ring)
            rc == 0
        finally ring.close()
        end try
    end probeRing

end UringGate
