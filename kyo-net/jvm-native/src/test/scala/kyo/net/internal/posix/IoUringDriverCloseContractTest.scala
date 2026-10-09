package kyo.net.internal.posix

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** The connection close contract on [[IoUringDriver]], driven over a REAL io_uring ring and real loopback sockets.
  *
  * Each leaf states one contract clause for a window the public surface cannot reach deterministically: a send parked in the kernel, a
  * submission queue that is full at close, an engine op queued behind a close, a close_notify that arrives in the same recv as the last record.
  * The windows are forced structurally: a peer with shrunk socket buffers that never reads parks the send, a depth-1 ring has exactly one SQE,
  * and ops enqueued from inside one engine op drain in one pass in enqueue order. Every wait is a real-event latch; the only real-clock value
  * is the bound of a latch that detects a hang.
  */
class IoUringDriverCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private val hangBound = 10.seconds

    private val closeFlushGrace = 1.second

    private def productionDepth: Int = math.max(256, kyo.net.ioPoolSize() * 64)

    private lazy val uringRunnable: Boolean =
        try
            UringGate.assumeUring()(using Frame.internal)
            true
        catch case _: kyo.test.TestCancelled => false

    /** The runner reports a cancelled pending leaf as Pending, so a leaf is marked only where io_uring runs, keeping its cancel visible
      * elsewhere.
      */
    extension (name: String)
        private def pendingWhereUringRuns(reason: String): kyo.test.TestBuilder =
            if uringRunnable then name.pendingUntilFixed(reason) else name.tagged()

    /** Records, per send SQE, the descriptor it names and that descriptor's close count when the SQE was prepared, and checks at every
      * submission point whether the descriptor was closed between the prepare and the hand-off to the kernel. Every ring operation delegates to
      * the real ring; nothing is scripted.
      */
    final private class SubmitOrderRecordingUring(real: IoUringBindings, realRing: Buffer[Byte], sockets: RecordingSocketBindings)
        extends RecordingIoUringBindings(real, realRing):

        final private case class PreparedSend(
            sqe: Ffi.Handle[IoUringSqe],
            fd: Int,
            buf: Buffer[Byte],
            len: Long,
            flags: Int,
            closesAtPrep: Int
        )

        private val unsubmitted = new ConcurrentLinkedQueue[PreparedSend]()

        /** Descriptors whose send SQE reached a submission point after the descriptor was closed. */
        val submittedAfterClose: ConcurrentLinkedQueue[Int] = new ConcurrentLinkedQueue[Int]()

        @volatile private var watchedFd                              = -1
        private val firstSubmitAfterClose: Promise.Unsafe[Unit, Any] = Promise.Unsafe.init[Unit, Any]()

        /** Completes at the first submission point reached after `fd` was closed, once that submission's sends have been checked. */
        def submitAfterCloseOf(fd: Int): Promise.Unsafe[Unit, Any] =
            watchedFd = fd
            firstSubmitAfterClose

        private def closeCount(fd: Int): Int = sockets.closeCounts.getOrDefault(fd, 0)

        /** A send found prepared against a since-closed descriptor is recorded, then re-prepared against fd -1 before the hand-off, so it
          * completes with EBADF instead of writing into whatever socket, eventfd or ring of a concurrently running leaf reused the number.
          */
        private def checkSubmission()(using AllowUnsafe): Unit =
            var next = unsubmitted.poll()
            while next != null do
                if closeCount(next.fd) > next.closesAtPrep then
                    discard(submittedAfterClose.add(next.fd))
                    discard(super.kyo_uring_prep_send(next.sqe, -1, next.buf, next.len, next.flags))
                next = unsubmitted.poll()
            end while
            val fd = watchedFd
            if fd >= 0 && closeCount(fd) > 0 then firstSubmitAfterClose.completeDiscard(Result.succeed(()))
        end checkSubmission

        override def kyo_uring_prep_send(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using
            AllowUnsafe
        ): Int =
            discard(unsubmitted.add(PreparedSend(sqe, fd, buf, len, flags, closeCount(fd))))
            super.kyo_uring_prep_send(sqe, fd, buf, len, flags)
        end kyo_uring_prep_send

        override def io_uring_submit(ring: Buffer[Byte])(using AllowUnsafe): Int =
            checkSubmission()
            super.io_uring_submit(ring)

        override def kyo_uring_submit_and_wait_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
            AllowUnsafe
        ): Fiber.Unsafe[Int, Any] =
            checkSubmission()
            super.kyo_uring_submit_and_wait_timeout(ring, cqePtr, timeoutNs)
        end kyo_uring_submit_and_wait_timeout
    end SubmitOrderRecordingUring

    /** The descriptors and handles one leaf opens, each released exactly once however the leaf ends. Leaves run concurrently and a closed
      * descriptor number is reused at once, so a second close of the same number lands on another leaf's socket, wake eventfd or ring.
      */
    final private class LeafResources(driver: IoUringDriver):
        private val peers   = new java.util.concurrent.ConcurrentHashMap[Int, java.util.concurrent.atomic.AtomicBoolean]()
        private val handles = new ConcurrentLinkedQueue[(PosixHandle, java.util.concurrent.atomic.AtomicBoolean)]()
        private val conns   = new ConcurrentLinkedQueue[InternalConnection[PosixHandle]]()

        private def claimPeer(fd: Int): Boolean =
            Maybe(peers.get(fd)).exists(_.compareAndSet(false, true))

        /** A raw peer descriptor the leaf closes itself. */
        def peer(fd: Int): Int =
            discard(peers.putIfAbsent(fd, new java.util.concurrent.atomic.AtomicBoolean(false)))
            fd

        def closePeer(fd: Int)(using AllowUnsafe): Unit = if claimPeer(fd) then discard(sock.close(fd))

        def resetPeer(fd: Int)(using AllowUnsafe): Unit = if claimPeer(fd) then PosixTestSockets.resetPeer(sock, fd)

        /** A handle the driver owns and closes; its descriptor is never closed raw. */
        def handle(h: PosixHandle): PosixHandle =
            discard(handles.add((h, new java.util.concurrent.atomic.AtomicBoolean(false))))
            h

        /** Hands a raw peer descriptor to the driver through `h`, which then owns its close. */
        def adoptPeer(fd: Int, h: PosixHandle): PosixHandle =
            discard(peer(fd))
            if claimPeer(fd) then handle(h) else h

        def closeHandle(h: PosixHandle)(using AllowUnsafe, Frame): Unit =
            handles.forEach(entry => if (entry._1 eq h) && entry._2.compareAndSet(false, true) then driver.closeHandle(h))

        def connection(conn: InternalConnection[PosixHandle]): InternalConnection[PosixHandle] =
            discard(conns.add(conn))
            conn

        /** Peers go first: closing them ends any send parked on a peer that stopped reading, so the driver's closes can complete. */
        def releaseAll()(using AllowUnsafe, Frame): Unit =
            peers.keySet().forEach(fd => closePeer(fd))
            conns.forEach(_.close())
            handles.forEach(entry => closeHandle(entry._1))
        end releaseAll
    end LeafResources

    /** A started driver over a REAL ring of `depth` entries, with recording socket bindings so a leaf can latch on the real `close(fd)`.
      *
      * However the leaf ends, its resources are released, the driver is closed, and the leaf waits for the reap loop to exit before the
      * engines a caller wired into handles are freed.
      */
    private def withDriver[A](depth: Int)(
        body: (IoUringDriver, RecordingSocketBindings, SubmitOrderRecordingUring, LeafResources) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("IoUringDriverCloseContractTest", summon[Frame], s"queue_init failed: rc=$rc")
        val sockets   = new RecordingSocketBindings(sock)
        val uring     = new SubmitOrderRecordingUring(realUring, realRing, sockets)
        val driver    = TestDrivers.forBindings(uring, realRing, sockets)
        val resources = new LeafResources(driver)
        val reapLoop  = driver.start()
        Scope.run {
            Scope.ensure {
                Sync.defer {
                    resources.releaseAll()
                    driver.close()
                }.andThen(within(hangBound)(reapLoop.safe.getResult)).map { exited =>
                    if exited.isEmpty then
                        Abort.panic(new IllegalStateException(s"the reap loop did not exit within ${hangBound.show} of close()"))
                    else Kyo.unit
                }
            }.andThen(body(driver, sockets, uring, resources))
        }
    end withDriver

    /** `Present` with the value when `v` completes within `bound`, `Absent` when the bound expires first. */
    private def within[A](bound: Duration)(v: => A < (Abort[Closed] & Async))(using Frame): Maybe[A] < (Abort[Closed] & Async) =
        Abort.run[Timeout](Async.timeout(bound)(v)).map {
            case Result.Success(a) => Present(a)
            case _                 => Absent
        }

    private def fifoBarrier(driver: IoUringDriver): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    private def readVia(driver: IoUringDriver, handle: PosixHandle)(using Frame): ReadOutcome < (Abort[Closed] & Async) =
        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
        driver.awaitRead(handle, promise)
        promise.safe.get
    end readVia

    /** Every byte the peer receives until its stream ends, through the driver's own recv on a peer handle that takes over the peer's close. */
    private def drainToEnd(driver: IoUringDriver, resources: LeafResources, peerFd: Int)(using
        Frame
    ): Array[Byte] < (Abort[Closed] & Async) =
        val peerH = resources.adoptPeer(peerFd, PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
        Loop(Array.emptyByteArray) { acc =>
            readVia(driver, peerH).map {
                case ReadOutcome.Bytes(span) => Loop.continue(acc ++ span.toArray)
                case _                       => Loop.done(acc)
            }
        }.map { bytes =>
            resources.closeHandle(peerH)
            bytes
        }
    end drainToEnd

    /** Decrypt a peer's received ciphertext, returning the plaintext and whether a close_notify was among the records (`readPlain == -3`). */
    private def decryptToEnd(engine: TlsEngine, cipher: Array[Byte]): (Array[Byte], Boolean) =
        TlsEngineLoopback.feed(engine, cipher)
        val acc         = new java.io.ByteArrayOutputStream
        val chunk       = 16 * 1024 + 512
        var closeNotify = false
        var more        = true
        while more do
            val out = Buffer.alloc[Byte](chunk)
            try
                val n = engine.readPlain(out, chunk)
                if n > 0 then acc.write(Buffer.copyToArray[Byte](out, 0, n))
                else
                    if n == -3 then closeNotify = true
                    more = false
                end if
            finally out.close()
            end try
        end while
        (acc.toByteArray, closeNotify)
    end decryptToEnd

    private def bytesOf(size: Int, seed: Int): Array[Byte] = Array.tabulate[Byte](size)(i => ((i * 31 + seed) % 251).toByte)

    "IoUringDriver close contract" - {

        "C2: a close releases the fd within the close-flush grace while a send is parked on a peer that stopped reading"
            .pendingWhereUringRuns(
                "U1: a send SQE parked on a non-reading peer holds the deferred close forever; sends are never cancelled at close"
            ) in {
            PosixTestSockets.assumeUring()
            withDriver(productionDepth) { (driver, sockets, _, resources) =>
                PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (fd, peerFd) =>
                    discard(resources.peer(peerFd))
                    val handle   = PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    val conn     = resources.connection(InternalConnection.init(handle, driver, 16, closeFlushGrace = closeFlushGrace))
                    val released = sockets.closed(fd)
                    conn.start()
                    conn.outbound.offer(Span.fromUnsafe(bytesOf(256 * 1024, 1))) match
                        case Result.Success(true) => ()
                        case other                => fail(s"the payload offer must be accepted, got $other")
                    conn.close()
                    within(closeFlushGrace + hangBound)(released.safe.get).map { closed =>
                        resources.resetPeer(peerFd)
                        assert(
                            closed.nonEmpty,
                            s"fd $fd was still open ${(closeFlushGrace + hangBound).show} after close(), past the ${closeFlushGrace.show} " +
                                "close-flush grace: the deferred close waits on a send the non-reading peer never completes"
                        )
                    }
                }
            }.map(_ => succeed)
        }

        "C6: a writable wait parked after the close's cancel fails Closed without waiting for the peer"
            .pendingWhereUringRuns(
                "U9: a backpressure promise parked after cancel is failed only in freeResources, which a parked send postpones indefinitely"
            ) in {
            PosixTestSockets.assumeUring()
            withDriver(productionDepth) { (driver, _, _, resources) =>
                PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (fd, peerFd) =>
                    discard(resources.peer(peerFd))
                    val handle = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                    val w      = driver.write(handle, Span.fromUnsafe(bytesOf(2 * 1024 * 1024, 2)), 0)
                    assert(w == WriteResult.Done, s"write result=$w")
                    resources.closeHandle(handle)
                    fifoBarrier(driver).safe.get.map { _ =>
                        assert(
                            handle.unsentTailBytes >= PosixHandle.WriteTailLowWater,
                            s"the tail must still hold the unsent payload for the wait to park, got ${handle.unsentTailBytes}"
                        )
                        val writable = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
                        driver.awaitWritable(handle, writable)
                        within(hangBound)(writable.safe.getResult).map { outcome =>
                            resources.resetPeer(peerFd)
                            outcome match
                                case Present(Result.Failure(_: Closed)) => ()
                                case Absent                             =>
                                    fail(s"the writable wait parked after close was still pending after ${hangBound.show}")
                                case Present(other) => fail(s"the writable wait parked after close must fail Closed, got $other")
                            end match
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "C1: a close sends the plaintext tail that stalled on a full submission queue before the FIN"
            .pendingWhereUringRuns(
                "U2: the SHUT_RD-forced recv EOF reaches closeNow before the SQ-full re-flush, and freeResources drops the stalled tail"
            ) in {
            PosixTestSockets.assumeUring()
            withDriver(1) { (driver, _, _, resources) =>
                PosixTestSockets.loopbackPair().map { case (fd, peerFd) =>
                    discard(resources.peer(peerFd))
                    val handle  = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                    val payload = bytesOf(64, 3)
                    val written = Promise.Unsafe.init[WriteResult, Any]()
                    // One engine op enqueues the recv arm, the write and the close, so they drain in this order in one pass: the recv takes
                    // the only SQE and the write's flush finds the submission queue full.
                    driver.submitEngineOp { () =>
                        driver.awaitRead(handle, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                        written.completeDiscard(Result.succeed(driver.write(handle, Span.fromUnsafe(payload), 0)))
                        resources.closeHandle(handle)
                    }
                    written.safe.get.map { w =>
                        assert(w == WriteResult.Done, s"write result=$w")
                        within(hangBound)(drainToEnd(driver, resources, peerFd)).map { received =>
                            received match
                                case Present(bytes) =>
                                    assert(
                                        bytes.toList == payload.toList,
                                        s"the peer received ${bytes.length} of ${payload.length} accepted bytes before the FIN"
                                    )
                                case Absent => fail(s"the peer's stream did not end within ${hangBound.show}")
                            end match
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "C3: a TLS close sends its close_notify when the submission queue was full at close"
            .pendingWhereUringRuns(
                "U2: the close_notify stalls on SQ-full and the SHUT_RD-forced recv EOF reaches closeNow first, which drops it"
            ) in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeBoringSslReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "the TLS handshake must complete first")
                withDriver(1) { (driver, sockets, _, resources) =>
                    PosixTestSockets.loopbackPair().map { case (fd, peerFd) =>
                        discard(resources.peer(peerFd))
                        val handle = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                        handle.tls = Present(serverEngine)
                        val released = sockets.closed(fd)
                        driver.submitEngineOp { () =>
                            driver.awaitRead(handle, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                            resources.closeHandle(handle)
                        }
                        within(hangBound)(drainToEnd(driver, resources, peerFd)).map { received =>
                            within(hangBound)(released.safe.get).map { _ =>
                                received match
                                    case Present(cipher) =>
                                        val (_, closeNotify) = decryptToEnd(clientEngine, cipher)
                                        assert(
                                            closeNotify,
                                            s"the peer received ${cipher.length} bytes and no close_notify before the FIN"
                                        )
                                    case Absent => fail(s"the peer's stream did not end within ${hangBound.show}")
                                end match
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "C5: no send SQE reaches the kernel against a descriptor that was closed after the send was prepared"
            .pendingWhereUringRuns(
                "U3: a write op queued behind a close preps a send on writeFd, its endWrite closes the fd, then flushSubmits hands the SQE over"
            ) in {
            PosixTestSockets.assumeUring()
            withDriver(productionDepth) { (driver, sockets, uring, resources) =>
                PosixTestSockets.loopbackPair().map { case (fd, peerFd) =>
                    discard(resources.peer(peerFd))
                    val handle    = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                    val submitted = uring.submitAfterCloseOf(fd)
                    val written   = Promise.Unsafe.init[WriteResult, Any]()
                    // The close is enqueued ahead of a write that already passed beginWrite: the order a re-entrant closeFn produces when it
                    // runs between the WritePump's beginWrite and its engine-op enqueue.
                    driver.submitEngineOp { () =>
                        resources.closeHandle(handle)
                        written.completeDiscard(Result.succeed(driver.write(handle, Span.fromUnsafe(bytesOf(64, 4)), 0)))
                    }
                    written.safe.get.map { _ =>
                        within(hangBound)(sockets.closed(fd).safe.get).map { closed =>
                            assert(closed.nonEmpty, s"fd $fd was never closed")
                            within(hangBound)(submitted.safe.get).map { checked =>
                                resources.closePeer(peerFd)
                                assert(checked.nonEmpty, "the reap loop never reached a submission point after the close")
                                import scala.jdk.CollectionConverters.*
                                val late = uring.submittedAfterClose.iterator().asScala.filter(_ == fd).size
                                assert(
                                    late == 0,
                                    s"$late send SQE(s) prepared against fd $fd were handed to the kernel after fd $fd was closed"
                                )
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "E1: a close_notify decrypted with the last data record stays an orderly close when the FIN follows"
            .pendingWhereUringRuns("U4: the recv of the later FIN overwrites PeerCleanClose with PeerEof and delivers PeerFin") in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeBoringSslReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "the TLS handshake must complete first")
                withDriver(productionDepth) { (driver, sockets, _, resources) =>
                    PosixTestSockets.loopbackPair().map { case (peerFd, fd) =>
                        discard(resources.peer(peerFd))
                        val handle   = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                        val plain    = bytesOf(100, 5)
                        val record   = TlsEngineLoopback.encrypt(clientEngine, plain)
                        val shutdown = clientEngine.shutdownStep()
                        val closeNotify = TlsEngineLoopback.drainAll(clientEngine)
                        assert(shutdown != -2 && closeNotify.nonEmpty, s"the peer engine emitted no close_notify ($shutdown)")
                        handle.tls = Present(serverEngine)
                        val wire = record ++ closeNotify
                        val buf  = Buffer.fromArray[Byte](wire)
                        val sent =
                            try sock.sendNow(peerFd, buf, wire.length.toLong, PosixConstants.MSG_NOSIGNAL)
                            finally buf.close()
                        assert(sent.value.toInt == wire.length, s"peer send failed: errno=${sent.errorCode}")
                        PosixTestSockets.halfClose(sock, peerFd)
                        val released = sockets.closed(fd)
                        within(hangBound) {
                            Loop(Array.emptyByteArray) { acc =>
                                readVia(driver, handle).map {
                                    case ReadOutcome.Bytes(span) => Loop.continue(acc ++ span.toArray)
                                    case end                     => Loop.done((acc, end))
                                }
                            }
                        }.map { result =>
                            val atEnd = handle.halfClose
                            resources.closeHandle(handle)
                            resources.closePeer(peerFd)
                            within(hangBound)(released.safe.get).map { _ =>
                                result match
                                    case Present((received, end)) =>
                                        assert(received.toList == plain.toList, s"got ${received.length} of ${plain.length} bytes")
                                        assert(end == ReadOutcome.CleanClose, s"the stream ended with $end")
                                        assert(atEnd == HalfCloseState.PeerCleanClose, s"the orderly close was recorded as $atEnd")
                                    case Absent => fail(s"the stream did not end within ${hangBound.show}")
                                end match
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "E1: a close_notify decrypted with the last data record ends the stream without waiting for the peer's FIN"
            .pendingWhereUringRuns(
                "U4: after delivering the data the next recv waits for a FIN the peer never sends; the close_notify is ignored"
            ) in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeBoringSslReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "the TLS handshake must complete first")
                withDriver(productionDepth) { (driver, sockets, _, resources) =>
                    PosixTestSockets.loopbackPair().map { case (peerFd, fd) =>
                        discard(resources.peer(peerFd))
                        val handle   = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                        val plain    = bytesOf(100, 6)
                        val record   = TlsEngineLoopback.encrypt(clientEngine, plain)
                        val shutdown = clientEngine.shutdownStep()
                        val closeNotify = TlsEngineLoopback.drainAll(clientEngine)
                        assert(shutdown != -2 && closeNotify.nonEmpty, s"the peer engine emitted no close_notify ($shutdown)")
                        handle.tls = Present(serverEngine)
                        val wire = record ++ closeNotify
                        val buf  = Buffer.fromArray[Byte](wire)
                        val sent =
                            try sock.sendNow(peerFd, buf, wire.length.toLong, PosixConstants.MSG_NOSIGNAL)
                            finally buf.close()
                        assert(sent.value.toInt == wire.length, s"peer send failed: errno=${sent.errorCode}")
                        val released = sockets.closed(fd)
                        within(hangBound) {
                            Loop(Array.emptyByteArray) { acc =>
                                readVia(driver, handle).map {
                                    case ReadOutcome.Bytes(span) => Loop.continue(acc ++ span.toArray)
                                    case end                     => Loop.done((acc, end))
                                }
                            }
                        }.map { result =>
                            resources.closeHandle(handle)
                            within(hangBound)(released.safe.get).map { _ =>
                                resources.closePeer(peerFd)
                                result match
                                    case Present((received, end)) =>
                                        assert(received.toList == plain.toList, s"got ${received.length} of ${plain.length} bytes")
                                        assert(end == ReadOutcome.CleanClose, s"the stream ended with $end")
                                    case Absent =>
                                        fail(s"the stream did not end within ${hangBound.show} of a delivered close_notify with no FIN")
                                end match
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "E1: a peer reset on a TLS connection is recorded as neither a local close nor an orderly close"
            .pendingWhereUringRuns("U5: ECONNRESET becomes Failed but halfClose stays Open, so the TLS status reads LocalClose") in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeBoringSslReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "the TLS handshake must complete first")
                withDriver(productionDepth) { (driver, sockets, _, resources) =>
                    PosixTestSockets.loopbackPair().map { case (peerFd, fd) =>
                        discard(resources.peer(peerFd))
                        val handle = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                        handle.tls = Present(serverEngine)
                        resources.resetPeer(peerFd)
                        val released = sockets.closed(fd)
                        within(hangBound)(readVia(driver, handle)).map { outcome =>
                            val atEnd = handle.halfClose
                            resources.closeHandle(handle)
                            within(hangBound)(released.safe.get).map { _ =>
                                outcome match
                                    case Present(ReadOutcome.Failed(_)) => ()
                                    case other => fail(s"a read after the peer's reset must report the reset, got $other")
                                assert(
                                    atEnd != HalfCloseState.Open && atEnd != HalfCloseState.PeerHalfClosePending &&
                                        atEnd != HalfCloseState.PeerCleanClose,
                                    s"a peer reset was recorded as $atEnd, which reports the connection as " +
                                        (if atEnd == HalfCloseState.PeerCleanClose then "CleanClose" else "LocalClose")
                                )
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "W1: a TLS write after a hard send error is refused rather than accepted into a discarded tail"
            .pendingWhereUringRuns("P5: a send CQE error discards the ciphertext tail and every later TLS write still returns Done") in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeBoringSslReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "the TLS handshake must complete first")
                withDriver(productionDepth) { (driver, sockets, uring, resources) =>
                    PosixTestSockets.loopbackPair().map { case (peerFd, fd) =>
                        discard(resources.peer(peerFd))
                        val handle = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                        handle.tls = Present(serverEngine)
                        resources.resetPeer(peerFd)
                        val released = sockets.closed(fd)
                        // The read consumes the reset, so the send below fails in the kernel (EPIPE) rather than racing the RST.
                        readVia(driver, handle).map { readOutcome =>
                            assert(readOutcome.isInstanceOf[ReadOutcome.Failed], s"the reset must surface on the read, got $readOutcome")
                            val sendReaped = uring.awaitReap()
                            val first      = driver.write(handle, Span.fromUnsafe(bytesOf(32, 7)), 0)
                            assert(first == WriteResult.Done, s"first write result=$first")
                            within(hangBound)(sendReaped.safe.get).map { reaped =>
                                assert(reaped.nonEmpty, "the first write's send never reaped")
                                fifoBarrier(driver).safe.get.map { _ =>
                                    val second = driver.write(handle, Span.fromUnsafe(bytesOf(32, 8)), 0)
                                    resources.closeHandle(handle)
                                    within(hangBound)(released.safe.get).map { _ =>
                                        assert(
                                            second == WriteResult.Error,
                                            s"a write after the connection's send failed returned $second; its bytes go nowhere"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "W1: a plaintext write after a hard send error is refused rather than accepted into a discarded tail"
            .pendingWhereUringRuns("P5: a send CQE error discards the plaintext tail and every later write still returns Done") in {
            PosixTestSockets.assumeUring()
            withDriver(productionDepth) { (driver, sockets, uring, resources) =>
                PosixTestSockets.loopbackPair().map { case (peerFd, fd) =>
                    discard(resources.peer(peerFd))
                    val handle = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                    resources.resetPeer(peerFd)
                    val released = sockets.closed(fd)
                    readVia(driver, handle).map { readOutcome =>
                        assert(readOutcome.isInstanceOf[ReadOutcome.Failed], s"the reset must surface on the read, got $readOutcome")
                        val sendReaped = uring.awaitReap()
                        val first      = driver.write(handle, Span.fromUnsafe(bytesOf(32, 9)), 0)
                        assert(first == WriteResult.Done, s"first write result=$first")
                        within(hangBound)(sendReaped.safe.get).map { reaped =>
                            assert(reaped.nonEmpty, "the first write's send never reaped")
                            fifoBarrier(driver).safe.get.map { _ =>
                                val second = driver.write(handle, Span.fromUnsafe(bytesOf(32, 10)), 0)
                                resources.closeHandle(handle)
                                within(hangBound)(released.safe.get).map { _ =>
                                    assert(
                                        second == WriteResult.Error,
                                        s"a write after the connection's send failed returned $second; its bytes go nowhere"
                                    )
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringDriverCloseContractTest
