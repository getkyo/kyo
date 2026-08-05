package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.Test

/** [[IoUringDriver]]'s SQE-based accept path must classify a transient accept errno (`EMFILE`/`ENFILE`/`ECONNABORTED`/
  * `EINTR`) and re-arm the accept rather than failing the accept promise. The transport accept loop
  * (`PosixTransport.scheduleNextAccept`) stops on ANY accept-promise `Failure`, so failing the promise on a transient
  * errno would PERMANENTLY wedge the io_uring listener: it would never accept another connection. The poller path
  * classifies the same errnos in its inline `acceptAll` drain (`EMFILE`/`ENFILE` backoff re-arm, `ECONNABORTED`/`EINTR`
  * bounded retry) and keeps the loop alive; io_uring matches that behavior at the SQE-accept completion.
  *
  * A real transient accept errno is not deterministically reachable from Scala (fd exhaustion is process-global and
  * fragile), so, as [[IoUringDriverReapTransientErrnoTest]] does for a transient `-ENOMEM`, it is driven at the bindings
  * seam over a REAL io_uring ring: [[AcceptErrnoInjectingUring]] forces the FIRST accept CQE to report a transient errno
  * instead of its real accepted fd (capturing and closing that real fd so nothing leaks), then lets the retry's accept
  * CQE pass through to the real ring. The driver re-arms on the transient errno, so a subsequent connection is accepted
  * and the promise succeeds; a `Closed` failure instead would wedge the listener at the transport.
  */
class IoUringDriverAcceptTransientErrnoTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Forces exactly one accept CQE to report the armed transient errno. The real accept fd (if the kernel produced
      * one) is closed here so the injected `EMFILE` does not leak an established connection; the pending client stays in
      * the backlog for the driver's retry to accept for real.
      */
    final private class AcceptErrnoInjectingUring(real: IoUringBindings, realRing: Buffer[Byte])
        extends RecordingIoUringBindings(real, realRing):
        import AllowUnsafe.embrace.danger
        private val injectPending           = new AtomicBoolean(false)
        @volatile private var injectedErrno = 0
        private val nextSqeIsAccept         = new AtomicBoolean(false)
        private val acceptKey               = new AtomicLong(Long.MinValue)

        // Reap-cycle instrumentation for the backoff assertion. waitCount (inherited) is the reap loop's wait counter, one increment per reap
        // cycle. We record the cycle in which the injected EMFILE CQE was reaped and the cycle in which the driver next re-armed the accept (its
        // next kyo_uring_prep_accept). An IMMEDIATE re-arm happens inside the SAME reap cycle as the EMFILE reap (equal counts). The resource
        // backoff defers the re-arm to a LATER cycle (strictly greater), after the loop parks and the backoff wakes it. This is independent of
        // the backoff duration, so there is no timing threshold. reArmSeen is completed once the re-arm prep is observed so a test awaits it
        // instead of polling.
        @volatile var emfileInjected        = false
        @volatile var waitCountAtEmfileReap = -1
        @volatile var waitCountAtReArm      = -1
        val reArmSeen                       = Promise.Unsafe.init[Unit, Abort[Closed]]()

        /** Arm the one-shot: the next accept CQE reports `-errno` instead of its real fd. */
        def armAcceptErrno(errno: Int): Unit =
            injectedErrno = errno
            injectPending.set(true)

        override def kyo_uring_prep_accept(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int], flags: Int)(using
            AllowUnsafe
        ): Unit =
            nextSqeIsAccept.set(true)
            // The first accept prep AFTER the injected EMFILE reap is the driver's re-arm: record its reap cycle and release the await latch.
            if emfileInjected && waitCountAtReArm < 0 then
                waitCountAtReArm = waitCount.get()
                discard(reArmSeen.complete(Result.succeed(())))
            super.kyo_uring_prep_accept(sqe, fd, addr, addrlen, flags)
        end kyo_uring_prep_accept

        override def kyo_uring_sqe_set_data64(sqe: Ffi.Handle[IoUringSqe], data: Long)(using AllowUnsafe): Unit =
            if nextSqeIsAccept.compareAndSet(true, false) then acceptKey.set(data)
            super.kyo_uring_sqe_set_data64(sqe, data)

        override def kyo_uring_cqe_res(cqe: Long)(using AllowUnsafe): Int =
            val key     = super.kyo_uring_cqe_get_data64(cqe)
            val realRes = super.kyo_uring_cqe_res(cqe)
            if key == acceptKey.get() && injectPending.compareAndSet(true, false) then
                // close the real accepted fd synchronously (sock.close returns an async fiber) so the injected errno leaks nothing
                if realRes >= 0 then discard(Ffi.load[PosixShimBindings].kyo_posix_close(realRes))
                waitCountAtEmfileReap = waitCount.get()
                emfileInjected = true
                -injectedErrno
            else realRes
            end if
        end kyo_uring_cqe_res
    end AcceptErrnoInjectingUring

    private def withInjectingDriver[A](
        body: (IoUringDriver, AcceptErrnoInjectingUring) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.ioPoolSize() * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("AcceptErrnoInjectingUring", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = new AcceptErrnoInjectingUring(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withInjectingDriver

    /** Bind + listen a fresh loopback server fd; returns (serverFd, port). Caller closes serverFd. */
    private def listenSocket()(using Frame): (Int, Int) =
        val s      = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(???)
        try
            require(sock.bind(s, a, l).value == 0, "bind failed")
            require(sock.listen(s, 8).value == 0, "listen failed")
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            try
                require(sock.getsockname(s, out, ol).value == 0, "getsockname failed")
                val port = ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                (s, port)
            finally
                out.close(); ol.close()
            end try
        finally a.close()
        end try
    end listenSocket

    private def connectClient(port: Int)(using Frame): Int < Async =
        val c        = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(???)
        Sync.ensure(Sync.defer(ca.close()))(sock.connect(c, ca, cl).safe.get.map(r => require(r.value == 0, "connect failed")).andThen(c))
    end connectClient

    "IoUringDriver accept transient-errno classification" - {
        "a transient accept errno does not fail the accept promise; a subsequent connection is still accepted" in {
            PosixTestSockets.assumeUring()
            withInjectingDriver { (drv, recording) =>
                val (serverFd, port) = listenSocket()
                val listenH          = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                val promise          = Promise.Unsafe.init[Int, Abort[Closed]]()
                // Arm the one-shot transient errno for the first accept CQE, then arm the accept and drive a connection.
                recording.armAcceptErrno(PosixConstants.EMFILE)
                drv.awaitAccept(listenH, promise.asInstanceOf[Promise.Unsafe[Int, Abort[Closed | NetException]]])
                connectClient(port).map { client1 =>
                    // The first accept CQE reports -EMFILE (injected). The driver re-arms on the transient errno; connect
                    // a second client so the re-armed accept has a real connection to accept.
                    connectClient(port).map { client2 =>
                        Abort.run[Timeout | Closed](Async.timeout(5.seconds)(promise.safe.get)).map { outcome =>
                            drv.closeHandle(listenH)
                            discard(sock.close(client1))
                            discard(sock.close(client2))
                            discard(sock.close(serverFd))
                            outcome match
                                case Result.Success(fd) =>
                                    assert(fd >= 0, s"the re-armed accept must deliver a valid fd; got $fd")
                                    discard(sock.close(fd)) // close the re-armed accept's connection so it does not outlive the test
                                case Result.Failure(_: Timeout) =>
                                    fail("accept hung: a transient errno was neither failed nor retried")
                                case Result.Failure(c: Closed) =>
                                    fail(
                                        s"the accept promise was failed Closed (\"$c\") on a TRANSIENT accept errno; the io_uring accept " +
                                            "path did not classify it as transient, so the transport accept loop would stop and wedge the listener"
                                    )
                                case other => fail(s"unexpected accept outcome: $other")
                            end match
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "an EMFILE accept errno re-arms after a resource backoff, not immediately (no reap-carrier busy-spin)" in {
            PosixTestSockets.assumeUring()
            withInjectingDriver { (drv, recording) =>
                val (serverFd, port) = listenSocket()
                val listenH          = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                val promise          = Promise.Unsafe.init[Int, Abort[Closed]]()
                // Arm the one-shot EMFILE for the first accept CQE, then drive one connection so that CQE is produced and injected.
                recording.armAcceptErrno(PosixConstants.EMFILE)
                drv.awaitAccept(listenH, promise.asInstanceOf[Promise.Unsafe[Int, Abort[Closed | NetException]]])
                connectClient(port).map { client1 =>
                    // The first accept CQE reports -EMFILE (injected). Await the driver's re-arm (its next accept prep), then compare the reap
                    // cycle of the EMFILE reap against the reap cycle of the re-arm. On EMFILE the kernel does NOT dequeue the pending
                    // connection, so an IMMEDIATE re-arm reaps -EMFILE again in the same cycle and the shared reap carrier busy-spins (libuv
                    // #690, asyncio Tulip #78). The poller defers re-arming these two errnos by a resource backoff, and this asserts io_uring
                    // does the same by re-arming in a strictly later cycle. The 5s ceiling turns a never-re-armed regression (a wedged listener)
                    // into a failed test rather than a hang.
                    Abort.run[Timeout | Closed](Async.timeout(5.seconds)(recording.reArmSeen.safe.get)).map { seen =>
                        drv.closeHandle(listenH)
                        discard(sock.close(client1))
                        discard(sock.close(serverFd))
                        seen match
                            case Result.Success(_) =>
                                val reapCycle  = recording.waitCountAtEmfileReap
                                val reArmCycle = recording.waitCountAtReArm
                                assert(
                                    reArmCycle > reapCycle,
                                    s"an EMFILE accept re-arm must be deferred to a later reap cycle so the reap carrier does not busy-spin. " +
                                        s"The EMFILE was reaped in cycle $reapCycle and the accept was re-armed in cycle $reArmCycle. " +
                                        "A same-cycle re-arm is the immediate re-arm (the spin)."
                                )
                            case Result.Failure(_: Timeout) =>
                                fail("the accept was never re-armed after the EMFILE errno; the listener would be wedged")
                            case other => fail(s"unexpected accept re-arm outcome: $other")
                        end match
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringDriverAcceptTransientErrnoTest
