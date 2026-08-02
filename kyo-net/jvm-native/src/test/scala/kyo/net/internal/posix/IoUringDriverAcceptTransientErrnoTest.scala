package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Reproduction + regression guard (D5): [[IoUringDriver]]'s SQE-based accept path fails the accept promise on ANY
  * negative accept CQE result, including a transient `EMFILE`/`ENFILE`/`ECONNABORTED`. The transport accept loop
  * (`PosixTransport.scheduleNextAccept`) stops on ANY accept-promise `Failure`, so one transient accept errno
  * PERMANENTLY wedges the io_uring listener: it never accepts another connection.
  *
  * The poller path does not have this bug: its inline `acceptAll` drain classifies `EMFILE`/`ENFILE` (backoff re-arm)
  * and `ECONNABORTED`/`EINTR` (bounded retry) and keeps the loop alive (PosixTransport.scala:1094-1103). The io_uring
  * SQE-accept completion has no such classification: `IoUringDriver.scala` completes the promise with
  * `Closed("accept errno=…")` for every negative `res`.
  *
  * A real transient accept errno is not deterministically reachable from Scala (fd exhaustion is process-global and
  * fragile), so, exactly as [[IoUringDriverReapTransientErrnoTest]] does for a transient `-ENOMEM`, it is reproduced at
  * the bindings seam over a REAL io_uring ring: [[AcceptErrnoInjectingUring]] forces the FIRST accept CQE to report a
  * transient errno instead of its real accepted fd (capturing and closing that real fd so nothing leaks), then lets the
  * retry's accept CQE pass through to the real ring. With the transient-retry fix (the driver re-arms the accept SQE on
  * a transient errno instead of failing the promise), a subsequent connection is accepted and the promise succeeds;
  * without it, the promise fails `Closed` and, at the transport, the listener is wedged. This is the driver-level
  * manifestation the fix targets; the wedge is the transport's response to that failure.
  *
  * RED until the driver classifies transient accept errnos.
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

        /** Arm the one-shot: the next accept CQE reports `-errno` instead of its real fd. */
        def armAcceptErrno(errno: Int): Unit =
            injectedErrno = errno
            injectPending.set(true)

        override def kyo_uring_prep_accept(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int], flags: Int)(using
            AllowUnsafe
        ): Unit =
            nextSqeIsAccept.set(true)
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
                val listenH          = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent)
                val promise          = Promise.Unsafe.init[Int, Abort[Closed]]()
                // Arm the one-shot transient errno for the first accept CQE, then arm the accept and drive a connection.
                recording.armAcceptErrno(PosixConstants.EMFILE)
                drv.awaitAccept(listenH, promise)
                connectClient(port).map { client1 =>
                    // The first accept CQE reports -EMFILE (injected). The fix re-arms; connect a second client so the
                    // re-armed accept has a real connection to accept. Without the fix, the promise already failed Closed.
                    connectClient(port).map { client2 =>
                        Abort.run[Timeout | Closed](Async.timeout(5.seconds)(promise.safe.get)).map { outcome =>
                            drv.closeHandle(listenH)
                            discard(sock.close(client1))
                            discard(sock.close(client2))
                            discard(sock.close(serverFd))
                            outcome match
                                case Result.Success(fd) =>
                                    assert(fd >= 0, s"the re-armed accept must deliver a valid fd; got $fd")
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
    }

end IoUringDriverAcceptTransientErrnoTest
