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

    private lazy val uringRuns: Boolean =
        try
            UringGate.assumeUring()(using Frame.internal)
            true
        catch case _: kyo.test.TestCancelled => false

    /** The runner reports a cancelled pending leaf as Pending, so a leaf is marked only where a production-depth ring initializes (and, for a
      * TLS leaf, BoringSSL is staged), keeping its cancel visible elsewhere.
      */
    extension (name: String)
        private def pendingWhereUringRuns(reason: String): kyo.test.TestBuilder =
            if uringRuns then name.pendingUntilFixed(reason) else name.tagged()
        private def pendingWhereUringTlsRuns(reason: String): kyo.test.TestBuilder =
            if uringRuns && TlsRealEngines.boringSslAvailable() then name.pendingUntilFixed(reason) else name.tagged()
    end extension

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

    /** Runs `enqueue` while the reap carrier is held inside an engine op, so every op it queues drains in one pass before the carrier next
      * submits to the kernel. Without the hold the carrier may submit between two of the ops, which frees the submission queue a leaf needs
      * full, and whether it does depends on the host's scheduling.
      */
    private def inOnePass(driver: IoUringDriver)(enqueue: => Unit)(using Frame): Unit < Async =
        val gate   = new java.util.concurrent.CountDownLatch(1)
        val pinned = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp { () =>
            pinned.completeDiscard(Result.succeed(()))
            gate.await()
        }
        pinned.safe.get.map { _ =>
            try enqueue
            finally gate.countDown()
        }
    end inOnePass

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

        "C2: a close releases the fd within the close-flush grace while a send is parked on a peer that stopped reading" in {
            PosixTestSockets.assumeUring()
            Clock.withTimeControl { control =>
                Clock.get.map { clock =>
                    Clock.let(Clock.live) {
                        withDriver(productionDepth) { (driver, sockets, _, resources) =>
                            PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (fd, peerFd) =>
                                discard(resources.peer(peerFd))
                                val handle = PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                                val conn   = resources.connection(
                                    InternalConnection.init(handle, driver, 16, closeFlushGrace = closeFlushGrace, clock = clock)
                                )
                                val released = sockets.closed(fd)
                                conn.start()
                                conn.outbound.offer(Span.fromUnsafe(bytesOf(256 * 1024, 1))) match
                                    case Result.Success(true) => ()
                                    case other                => fail(s"the payload offer must be accepted, got $other")
                                conn.close()
                                // The peer never reads, so a window can see bytes leave only while the kernel still fills its buffers.
                                Loop(0) { windows =>
                                    if windows >= 32 then Loop.done(windows)
                                    else
                                        releasedOrArmed(
                                            conn.isReleased,
                                            control,
                                            s"close-flush grace (window ${windows + 1})",
                                            closeFlushGrace
                                        )
                                            .map { done =>
                                                if done then Loop.done(windows)
                                                else control.advance(closeFlushGrace).andThen(Loop.continue(windows + 1))
                                            }
                                }.map { windows =>
                                    within(hangBound)(released.safe.get).map { closed =>
                                        resources.resetPeer(peerFd)
                                        assert(conn.isReleased, s"the close was still held after $windows close-flush windows")
                                        assert(
                                            closed.nonEmpty,
                                            s"fd $fd was still open ${hangBound.show} after the grace released the connection: the deferred close " +
                                                "waits on a send the non-reading peer never completes"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "C1: a send completion that empties the tail while the pump is backpressured never drops the held span" in {
            PosixTestSockets.assumeUring()
            withDriver(productionDepth) { (driver, _, _, resources) =>
                PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (fd, peerFd) =>
                    discard(resources.peer(peerFd))
                    val handle  = PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    val conn    = resources.connection(InternalConnection.init(handle, driver, 64))
                    val payload = (0 until 16).map(i => bytesOf(64 * 1024, i))
                    conn.start()
                    payload.foreach(span => discard(conn.outbound.offer(Span.fromUnsafe(span))))
                    // Parked on the tail bound with a span held: the send completions that drain the tail below the bound release the pump,
                    // and the close in progress must let it write the span it holds rather than release on an empty tail.
                    Loop(0) { polls =>
                        conn.writeState match
                            case kyo.net.internal.transport.WriteState.Backpressured(_, _) => Loop.done(true)
                            case _ if polls >= 10000                                       => Loop.done(false)
                            case _ => Async.sleep(1.millis).andThen(Loop.continue(polls + 1))
                    }.map { parked =>
                        assert(parked, s"setup: the pump never parked on the tail bound, state ${conn.writeState}")
                        conn.close()
                        drainToEnd(driver, resources, peerFd).map { received =>
                            val want = payload.toArray.flatten
                            assert(received.sameElements(want), s"the peer received ${received.length} of ${want.length} accepted bytes")
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "C6: a writable wait parked after the close's cancel fails Closed without waiting for the peer" in {
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
                        // The close took the release claim before this park, so the park fails it before returning.
                        driver.awaitWritable(handle, writable)
                        val outcome = writable.poll()
                        resources.resetPeer(peerFd)
                        outcome match
                            case Present(Result.Failure(_: Closed)) => ()
                            case Absent                             => fail("the writable wait parked after close was left pending")
                            case Present(other) => fail(s"the writable wait parked after close must fail Closed, got $other")
                        end match
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
                    // The recv arm is enqueued ahead of one engine op that writes and closes, and both drain in one pass: the recv takes the
                    // only SQE before the close claims the handle, and the write's flush finds the submission queue full.
                    inOnePass(driver) {
                        driver.awaitRead(handle, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                        driver.submitEngineOp { () =>
                            written.completeDiscard(Result.succeed(driver.write(handle, Span.fromUnsafe(payload), 0)))
                            resources.closeHandle(handle)
                        }
                    }.andThen(written.safe.get).map { w =>
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
            .pendingWhereUringTlsRuns(
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
                        // The recv arm drains ahead of the close, in the same pass, and takes the only SQE before the close claims the handle.
                        inOnePass(driver) {
                            driver.awaitRead(handle, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                            driver.submitEngineOp(() => resources.closeHandle(handle))
                        }.andThen(within(hangBound)(drainToEnd(driver, resources, peerFd))).map { received =>
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

        "C5: no send SQE reaches the kernel against a descriptor that was closed after the send was prepared" in {
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
                        sockets.closed(fd).safe.get.map { _ =>
                            submitted.safe.get.map { _ =>
                                resources.closePeer(peerFd)
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
            .pendingWhereUringTlsRuns("U4: the recv of the later FIN overwrites PeerCleanClose with PeerEof and delivers PeerFin") in {
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
            .pendingWhereUringTlsRuns(
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
            .pendingWhereUringTlsRuns("U5: ECONNRESET becomes Failed but halfClose stays Open, so the TLS status reads LocalClose") in {
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

        "W1: a TLS write after a hard send error is refused rather than accepted into a discarded tail" in {
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

        "W1: a plaintext write after a hard send error is refused rather than accepted into a discarded tail" in {
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

        "fd release > a fatal TLS record releases the descriptor exactly once, through the connection's release" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeBoringSslReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "the TLS handshake must complete first")
                withDriver(productionDepth) { (driver, sockets, _, resources) =>
                    PosixTestSockets.loopbackPair().map { case (peerFd, fd) =>
                        discard(resources.peer(peerFd))
                        val handle = resources.handle(PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal))
                        handle.tls = Present(serverEngine)
                        val good = TlsEngineLoopback.encrypt(clientEngine, "good".getBytes("UTF-8"))
                        val bad  = TlsEngineLoopback.encrypt(clientEngine, "tampered".getBytes("UTF-8"))
                        bad(bad.length - 1) = (bad(bad.length - 1) ^ 0xff).toByte
                        val wire = good ++ bad
                        val buf  = Buffer.fromArray[Byte](wire)
                        val sent =
                            try sock.sendNow(peerFd, buf, wire.length.toLong, PosixConstants.MSG_NOSIGNAL)
                            finally buf.close()
                        assert(sent.value.toInt == wire.length, s"peer send failed: errno=${sent.errorCode}")
                        val released = sockets.closed(fd)
                        for
                            outcome <- Abort.run[Closed](readVia(driver, handle))
                            _       <- fifoBarrier(driver).safe.get
                            closesBeforeRelease = sockets.closeCounts.getOrDefault(fd, 0)
                            _ <- Sync.defer(resources.closeHandle(handle))
                            _ <- released.safe.get
                            _ <- fifoBarrier(driver).safe.get
                        yield
                            resources.closePeer(peerFd)
                            assert(outcome.isSuccess, s"the fatal record did not complete the read: $outcome")
                            assert(
                                closesBeforeRelease == 0,
                                s"the fatal record closed fd $fd itself, before the connection released it ($closesBeforeRelease closes)"
                            )
                            assert(
                                sockets.closeCounts.getOrDefault(fd, 0) == 1,
                                s"fd $fd must be closed exactly once, got ${sockets.closeCounts.getOrDefault(fd, 0)}"
                            )
                        end for
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringDriverCloseContractTest
