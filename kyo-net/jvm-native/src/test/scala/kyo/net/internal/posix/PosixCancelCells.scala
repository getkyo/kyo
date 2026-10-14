package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.transport.CancelCell
import kyo.net.internal.transport.IoDriver

/** The posix drivers' [[CancelCell]]s: the readiness poller, io_uring, and the blocking-reader fallback. Every driver closes descriptors
  * through a [[RecordingSocketBindings]], so each cell counts closes. Real sockets and real kernel interfaces throughout.
  */
object PosixCancelCells:

    import CancelCell.Kind
    import CancelCell.Op

    private def sock(using AllowUnsafe) = Ffi.load[SocketBindings]

    val all: Seq[CancelCell] = Seq(Poller, IoUring, BlockingReader)

    /** A subject over `driver`, whose loop fiber is `loop`, on a fresh handle of `kind`. A connected handle the leaf never released is
      * released when the scope closes; the peer and a listener's descriptor are closed there too.
      */
    private def subject(driver: IoDriver[PosixHandle], loop: Fiber.Unsafe[Unit, Any], sockets: RecordingSocketBindings, kind: Kind)(using
        Frame,
        AllowUnsafe
    ): CancelCell.Subject < (Async & Scope) =
        final class Subject(handle: PosixHandle, peer: Maybe[Int]) extends CancelCell.DriverSubject[PosixHandle](driver, handle):
            def peerSend(bytes: Array[Byte])(using AllowUnsafe, Frame): Unit < Async =
                val fd  = peer.getOrElse(throw new IllegalStateException("this subject has no peer"))
                val buf = Buffer.fromArray[Byte](bytes)
                Sync.ensure(Sync.defer(buf.close()))(sock.send(fd, buf, bytes.length.toLong, PosixConstants.MSG_NOSIGNAL).safe.get).unit
            end peerSend
            override def parkUpgradeWaiter(p: Promise.Unsafe[Span[Byte], Abort[kyo.net.NetException]])(using AllowUnsafe, Frame): Unit =
                handle.upgradeActive = true
                handle.upgrading = true
                assert(handle.parkUpgradeWaiter(PosixHandle.UpgradeHandoff.Waiter(p, summon[Frame])), "the handoff slot was not Idle")
            end parkUpgradeWaiter
            override def armUpgradeProducer(p: Promise.Unsafe[kyo.net.internal.transport.ReadOutcome, Abort[Closed]])(using
                AllowUnsafe,
                Frame
            ): Unit =
                handle.upgradeActive = true
                handle.upgrading = true
                this.driver match
                    case d: PollerIoDriver => d.armUpgradeProducerRead(handle, p)
                    case d: IoUringDriver  => d.armUpgradeProducerRead(handle, p)
                    case other             => throw new IllegalStateException(s"no upgrade producer on $other")
                end match
            end armUpgradeProducer
            def closeListener()(using AllowUnsafe, Frame): Unit =
                this.driver.closeListener(handle, () => discard(sockets.close(handle.readFd)))
            def descriptorClosed(using AllowUnsafe, Frame): Unit < Async = sockets.closed(handle.readFd).safe.get
            def descriptorCloses(using AllowUnsafe): Int                 = sockets.closeCounts.getOrDefault(handle.readFd, 0)
            def stopDriver()(using AllowUnsafe, Frame): Unit < Async     =
                Sync.defer(this.driver.close()).andThen(loop.safe.get)
        end Subject

        def opened(fd: Int, peer: Maybe[Int], connectTarget: Maybe[(Buffer[Byte], Int)] = Absent): CancelCell.Subject < (Async & Scope) =
            val handle = PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, connectTarget, Frame.internal)
            Scope.ensure(Sync.defer {
                kind match
                    // Once closeListener took the claim the driver owns the close, which it may still be deferring behind the fd's
                    // withdrawal. Closing here as well would close the number again after another leaf has reused it.
                    case Kind.Listening => if handle.life.armable then discard(sock.close(fd))
                    case _              => if !handle.isClosing() then driver.closeHandle(handle)
                end match
                peer.foreach(p => discard(sock.close(p)))
                // The driver marshals the target when it preps the connect on its own carrier, so the buffer is freed behind that prep.
                connectTarget.foreach((addr, _) => driver.submitEngineOp(() => addr.close()))
            }).andThen(new Subject(handle, peer))
        end opened

        kind match
            case Kind.Connected => PosixTestSockets.loopbackPair(sockets).map((fd, peer) => opened(fd, Present(peer)))
            case Kind.Connecting if driver.isInstanceOf[IoUringDriver] =>
                PosixTestSockets.unconnectedToBlackHole().map((fd, addr, len) => opened(fd, Absent, Present((addr, len))))
            case Kind.Connecting => PosixTestSockets.connectInFlight().map(c => opened(c.targetFd, Absent))
            case Kind.Listening  => Sync.defer(PosixTestSockets.listening()).map((fd, _) => opened(fd, Absent))
            // A connect in flight to the black hole is never readable or writable. io_uring's writable wait parks on its own write tail
            // rather than on the socket, so that tail is filled first; its send waits on the connect and never drains it.
            case Kind.Stalled =>
                PosixTestSockets.connectInFlight().map(c => opened(c.targetFd, Absent)).map { subject =>
                    if driver.isInstanceOf[IoUringDriver] then subject.fillSendBuffer().andThen(subject) else subject
                }
        end match
    end subject

    private def withDriver(driver: IoDriver[PosixHandle], sockets: RecordingSocketBindings, kind: Kind)(using
        Frame,
        AllowUnsafe
    ): CancelCell.Subject < (Async & Scope) =
        val loop = driver.start()
        Scope.ensure(Sync.defer(driver.close())).andThen(subject(driver, loop, sockets, kind))
    end withDriver

    object Poller extends CancelCell:
        def name: String                                  = if PosixConstants.isLinux then "epoll" else "kqueue"
        def unavailable(using AllowUnsafe): Maybe[String] =
            if PosixConstants.isLinux || PosixConstants.isMacOrBsd then Absent else Present("PollerIoDriver needs epoll or kqueue")
        val supports: Set[Op] =
            Set(
                Op.Read,
                Op.Write,
                Op.Upgrade,
                Op.UpgradeWaiter,
                Op.UpgradeProducer,
                Op.Connect,
                Op.Accept,
                Op.ListenerClaim,
                Op.CountsCloses
            )
        def open(kind: Kind)(using Frame, AllowUnsafe): CancelCell.Subject < (Async & Scope) =
            val sockets = RecordingSocketBindings(sock)
            val backend = PollerBackend.default()
            withDriver(TestDrivers.forBackend(backend, backend.create(), sockets), sockets, kind)
        end open
    end Poller

    object IoUring extends CancelCell:
        def name: String                                  = "io_uring"
        def unavailable(using AllowUnsafe): Maybe[String] =
            try
                UringGate.assumeUring()(using Frame.internal)
                Absent
            catch case c: kyo.test.TestCancelled => Present(s"io_uring is not usable on this host: $c")
        val supports: Set[Op] =
            Set(
                Op.Read,
                Op.Write,
                Op.Upgrade,
                Op.UpgradeWaiter,
                Op.UpgradeProducer,
                Op.Connect,
                Op.Accept,
                Op.ListenerClaim,
                Op.CountsCloses
            )
        def open(kind: Kind)(using Frame, AllowUnsafe): CancelCell.Subject < (Async & Scope) =
            val uring = Ffi.load[IoUringBindings]
            val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
            val rc    = uring.io_uring_queue_init(math.max(256, kyo.net.ioPoolSize() * 64), ring, 0)
            if rc != 0 then
                ring.close()
                throw new IllegalStateException(s"io_uring_queue_init failed: rc=$rc")
            val sockets = RecordingSocketBindings(sock)
            withDriver(TestDrivers.forBindings(uring, ring, sockets), sockets, kind)
        end open
    end IoUring

    /** The epoll regular-file stdin fallback. Its read is a blocking `read(2)`, so its handle is a connected pair left in blocking mode;
      * everything but the read delegates to the wrapped poller, so the leaves that need writes, connects or accepts run on the poller cell.
      */
    object BlockingReader extends CancelCell:
        def name: String                                                                     = "blocking-reader"
        def unavailable(using AllowUnsafe): Maybe[String]                                    = Poller.unavailable
        val supports: Set[Op]                                                                = Set(Op.Read, Op.CountsCloses)
        def open(kind: Kind)(using Frame, AllowUnsafe): CancelCell.Subject < (Async & Scope) =
            val sockets = RecordingSocketBindings(sock)
            val backend = PollerBackend.default()
            val driver  = TestDrivers.blockingReader(TestDrivers.forBackend(backend, backend.create(), sockets), sockets)
            val loop    = driver.start()
            Scope.ensure(Sync.defer(driver.close())).andThen {
                PosixTestSockets.blockingLoopbackPair().map { (fd, peer) =>
                    val handle = PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    // The peer is closed first: a read(2) still blocked on the handle returns on its FIN, so the release can complete.
                    Scope.ensure(Sync.defer {
                        discard(sock.close(peer))
                        if !handle.isClosing() then driver.closeHandle(handle)
                    }).andThen(new CancelCell.DriverSubject[PosixHandle](driver, handle):
                        def peerSend(bytes: Array[Byte])(using AllowUnsafe, Frame): Unit < Async =
                            val buf = Buffer.fromArray[Byte](bytes)
                            Sync.ensure(Sync.defer(buf.close()))(sock.send(peer, buf, bytes.length.toLong, 0).safe.get).unit
                        def closeListener()(using AllowUnsafe, Frame): Unit          = ()
                        def descriptorClosed(using AllowUnsafe, Frame): Unit < Async =
                            // A close waits for a read(2) in flight; the peer's FIN ends it.
                            Sync.defer(discard(sock.shutdown(peer, PosixTestSockets.SHUT_WR))).andThen(sockets.closed(fd).safe.get)
                        def descriptorCloses(using AllowUnsafe): Int             = sockets.closeCounts.getOrDefault(fd, 0)
                        def stopDriver()(using AllowUnsafe, Frame): Unit < Async =
                            Sync.defer(this.driver.close()).andThen(loop.safe.get))
                }
            }
        end open
    end BlockingReader

end PosixCancelCells
