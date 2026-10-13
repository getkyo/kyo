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

        // The re-arm is the first accept prep after the injected EMFILE reap. Under a controlled clock the backoff can fire before the reap
        // loop starts its next cycle, so the witness of deferral is that no re-arm precedes the backoff firing, not a reap-cycle count.
        @volatile var emfileInjected = false
        val reArmSeen                = Promise.Unsafe.init[Unit, Abort[Closed]]()

        /** Arm the one-shot: the next accept CQE reports `-errno` instead of its real fd. */
        def armAcceptErrno(errno: Int): Unit =
            injectedErrno = errno
            injectPending.set(true)

        override def kyo_uring_prep_accept(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int], flags: Int)(using
            AllowUnsafe
        ): Unit =
            nextSqeIsAccept.set(true)
            if emfileInjected then
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
                emfileInjected = true
                -injectedErrno
            else realRes
            end if
        end kyo_uring_cqe_res
    end AcceptErrnoInjectingUring

    /** Runs `body` over a driver on the injecting ring, then closes the driver and waits for its reap loop to exit, so every close the
      * driver owes has been issued by the time the closes are counted. `sockets` records each close; the test closes its own fds through it
      * too, so a descriptor closed by both the driver and the test shows a count of 2.
      */
    private def withInjectingDriver[A](
        body: (IoUringDriver, AcceptErrnoInjectingUring, RecordingSocketBindings) => A < (Abort[Closed] & Async)
    )(using Frame): (A, RecordingSocketBindings) < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.ioPoolSize() * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("AcceptErrnoInjectingUring", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = new AcceptErrnoInjectingUring(realUring, realRing)
        val sockets   = RecordingSocketBindings(Ffi.load[SocketBindings])
        val driver    = TestDrivers.forBindings(recording, realRing, sockets)
        val reapLoop  = driver.start()
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording, sockets)).map { a =>
            reapLoop.safe.get.andThen((a, sockets))
        }
    end withInjectingDriver

    /** Bind + listen a fresh loopback server fd; returns (serverFd, port). The caller wraps serverFd in a handle whose `closeHandle` closes it. */
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
            withInjectingDriver { (drv, recording, sockets) =>
                val (serverFd, port) = listenSocket()
                val listenH          = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                val promise          = Promise.Unsafe.init[Int, Abort[Closed]]()
                // Arm the one-shot transient errno for the first accept CQE, then arm the accept and drive a connection.
                recording.armAcceptErrno(PosixConstants.EMFILE)
                Clock.withTimeControl { tc =>
                    Clock.get.map { clock =>
                        // The re-arm after the errno waits out the resource backoff on the listen handle's clock.
                        listenH.clock = clock
                        drv.awaitAccept(listenH, promise.asInstanceOf[Promise.Unsafe[Int, Abort[Closed | NetException]]])
                        connectClient(port).map { client1 =>
                            // The first accept CQE reports -EMFILE (injected). The driver re-arms after the backoff; connect a second client so
                            // the re-armed accept has a real connection to accept. An accept neither failed nor retried hangs to the leaf cap.
                            connectClient(port).map { client2 =>
                                armedThenAdvance(tc, "accept resource backoff", kyo.net.acceptResourceBackoff().millis)
                                    .andThen(Abort.run[Closed](promise.safe.get))
                                    .map(outcome => (client1, client2, outcome))
                            }
                        }
                    }
                }.map { (client1, client2, outcome) =>
                    drv.closeHandle(listenH)
                    discard(sockets.close(client1))
                    discard(sockets.close(client2))
                    outcome match
                        case Result.Success(fd) =>
                            assert(fd >= 0, s"the re-armed accept must deliver a valid fd; got $fd")
                            discard(sockets.close(fd)) // close the re-armed accept's connection so it does not outlive the test
                        case Result.Failure(c: Closed) =>
                            fail(
                                s"the accept promise was failed Closed (\"$c\") on a TRANSIENT accept errno; the io_uring accept " +
                                    "path did not classify it as transient, so the transport accept loop would stop and wedge the listener"
                            )
                        case other => fail(s"unexpected accept outcome: $other")
                    end match
                    serverFd
                }
            }.map { case (serverFd, sockets) => assertClosedOnce(sockets, serverFd) }
        }

        "an EMFILE accept errno re-arms after a resource backoff, not immediately (no reap-carrier busy-spin)" in {
            PosixTestSockets.assumeUring()
            withInjectingDriver { (drv, recording, sockets) =>
                val (serverFd, port) = listenSocket()
                val listenH          = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                val promise          = Promise.Unsafe.init[Int, Abort[Closed]]()
                // Arm the one-shot EMFILE for the first accept CQE, then drive one connection so that CQE is produced and injected.
                recording.armAcceptErrno(PosixConstants.EMFILE)
                Clock.withTimeControl { tc =>
                    Clock.get.map { clock =>
                        listenH.clock = clock
                        drv.awaitAccept(listenH, promise.asInstanceOf[Promise.Unsafe[Int, Abort[Closed | NetException]]])
                        connectClient(port).map { client1 =>
                            // The first accept CQE reports -EMFILE (injected). On EMFILE the kernel does NOT dequeue the pending connection,
                            // so an IMMEDIATE re-arm reaps -EMFILE again and the shared reap carrier busy-spins (libuv #690, asyncio Tulip
                            // #78). The poller defers re-arming these two errnos by a resource backoff, and this asserts io_uring does the
                            // same: with the backoff armed on the listen handle's clock and not yet fired, no re-arm has happened; firing it
                            // re-arms. A never-re-armed listener hangs to the leaf cap.
                            val backoff = kyo.net.acceptResourceBackoff().millis
                            awaitTimer(tc, "accept resource backoff", backoff)
                                .andThen(Sync.defer(recording.reArmSeen.done()))
                                .map(early => tc.advance(backoff).andThen(recording.reArmSeen.safe.get).andThen((client1, early)))
                        }
                    }
                }.map { (client1, reArmedEarly) =>
                    drv.closeHandle(listenH)
                    discard(sockets.close(client1))
                    assert(
                        !reArmedEarly,
                        "an EMFILE accept re-arm must wait out the resource backoff so the reap carrier does not busy-spin; " +
                            "the accept was re-armed while the backoff was still pending"
                    )
                    serverFd
                }
            }.map { case (serverFd, sockets) => assertClosedOnce(sockets, serverFd) }
        }
    }

    // The handle owns serverFd once closeHandle runs, so the driver's close must be the only one: a second close of the same number lands on
    // whatever reused it (a later suite's socket, or the class loader's open .class file in one CI run).
    private def assertClosedOnce(sockets: RecordingSocketBindings, serverFd: Int)(using Frame, kyo.test.AssertScope): Unit =
        val closes = sockets.closeCounts.getOrDefault(serverFd, 0)
        assert(closes == 1, s"the listener fd $serverFd must be closed exactly once, by the driver; it was closed $closes times")

end IoUringDriverAcceptTransientErrnoTest
