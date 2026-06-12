package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import scala.jdk.CollectionConverters.*

/** Deterministic interrupt coverage for a fiber parked directly in the driver-level connect / accept op (one layer BELOW the
  * `inbound.take` the `TransportUnsafeTest` interrupt leaf parks on).
  *
  * `awaitConnect` deposits a writable [[Promise.Unsafe]] in the driver's `pendingWritables` map and arms write interest; `awaitAccept`
  * deposits an accept promise in `pendingAccepts` and arms read interest. A fiber that awaits either via `.safe.get` is parked on that
  * promise. The determinism comes from two pieces. First, each leaf latches on the change-FIFO worker arming interest
  * (`backend.registeredWrite(fd)` for connect, `backend.registeredRead(fd)` for accept) BEFORE interrupting: when that latch completes the
  * fiber has provably parked and the poller registration is live, ruling out the startup race of interrupting a not-yet-parked fiber. Second,
  * the awaited op provably cannot resolve: a connect's writable parks on a socket flooded to zero-window STEADY STATE (`fillSendBuffer` sends
  * until 64 consecutive EAGAINs, proving both buffers are saturated and loopback flow control has halted, so the fd stays not-writable until
  * the peer reads), and an accept's read parks on a real listen fd with no client connecting (a listen fd is readable only on an incoming
  * connection). Stopping the flood at the FIRST EAGAIN was the INV-FLAKE root cause: it captured a transient where loopback re-drained the send
  * buffer and the fd became writable again, so a real write-ready event resolved the connect promise and `fiber.interrupt` lost the race to
  * completion (returned false). With the op provably non-resolvable, `fiber.interrupt` deterministically interrupts the parked fiber and
  * returns `true` regardless of timing (the same "the op cannot complete, so interrupt is deterministic" reasoning the take-interrupt and
  * `closeHandle`-cancels-pending-read tests rely on).
  *
  * Cleanup is proven via a [[RecordingPollerBackend]] over the real epoll/kqueue: it records every `deregister` call, and after the interrupt
  * the test calls `driver.cancel(handle)` (the path the transport wires to interrupt: `awaitConnectThen`'s failure/interrupt close and
  * `listener.onClose(() => driver.cancel)`). `cancel` must (a) leave the deposited promise terminal, not a leaked pending promise (the
  * interrupt wins the completion race with a Panic; cancel's own `Closed` completion is then a no-op, but the map removal + deregister still
  * run), and (b) submit exactly one `deregister` for the fd through the change-FIFO worker (the registration is released, no fd/interest leak).
  * A companion pair of tests then re-arms a FRESH waiter on the same fd after cancel and fires REAL readiness (drain the peer so the connect fd
  * becomes writable; connect a client so the listen fd becomes accept-ready), proving the fd slot is clean and re-registerable.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real epoll/kqueue for the readiness arming and the real deregister).
  *
  * Uses a `RecordingPollerBackend(PollerBackend.default())` over real fds. Deregister completions are latched via
  * `backend.deregisteredFd(fd)` and the full deregister set is read from `backend.deregisteredFds`.
  */
class PollerIoDriverConnectAcceptInterruptTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Build a bound + listening server fd on 127.0.0.1; returns (serverFd, port). No client connects, so the listen fd is never accept-ready
      * until the test connects one.
      */
    private def listenSocket()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0, "bind failed")
            assert(sock.listen(server, 4).value == 0, "listen failed")
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sock.getsockname(server, out, ol).value == 0, "getsockname failed")
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val shim = Ffi.load[PosixShimBindings]
            assert(shim.kyo_posix_set_nonblocking(server) == 0, "set_nonblocking(server) failed")
            Sync.defer((server, port))
        }
    end listenSocket

    /** Connect a fresh non-blocking client to `port`; returns the client fd. The connection makes the server's listen fd accept-ready. */
    private def connectClient(port: Int)(using Frame, kyo.test.AssertScope): Int < Async =
        val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0, "connect failed"))).map {
            _ =>
                val shim = Ffi.load[PosixShimBindings]
                assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                client
        }
    end connectClient

    /** Flood `fd`'s send buffer to zero-window steady state so the kernel stably reports it not-writable. Stopping at the FIRST EAGAIN
      * captures only a transient: the loopback kernel keeps draining the send buffer into the peer's recv buffer, so the fd becomes writable
      * again moments later and a real write-ready event resolves a parked connect promise (the INV-FLAKE this test reproduces). Flooding until
      * 64 CONSECUTIVE EAGAINs proves both buffers are saturated and loopback flow control has halted; with the peer never reading, the fd then
      * stays not-writable and a write interest on `fd` provably never fires until the peer drains.
      */
    private def fillSendBuffer(fd: Int)(using AllowUnsafe): Unit =
        val chunk = Buffer.alloc[Byte](65536)
        try
            var consecutiveEagain = 0
            var guard             = 0
            while consecutiveEagain < 64 && guard < 65536 do
                val r = sock.sendNow(fd, chunk, 65536L, PosixConstants.MSG_DONTWAIT | PosixConstants.MSG_NOSIGNAL)
                if r.value <= 0 then consecutiveEagain += 1
                else consecutiveEagain = 0
                guard += 1
            end while
        finally chunk.close()
        end try
    end fillSendBuffer

    "PollerIoDriver interrupt of a fiber parked in a driver-level op" - {

        "interrupting a fiber parked in awaitConnect returns true and cancel cleans up the pending writable op" in {
            PosixTestSockets.assumePoller()
            // A connect's writable parks on a real fd whose send buffer is flooded to zero-window steady state (smallBufferedPair, peer never
            // reads), so the real backend never fires a write-ready event and the promise provably cannot complete: interrupt wins.
            PosixTestSockets.smallBufferedPair(sndBuf = 2048, rcvBuf = 2048).map { case (writeFd, peerFd) =>
                val targetFd = writeFd
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                discard(driver.start())
                val handle = PosixHandle.socket(targetFd, PosixHandle.DefaultReadBufferSize, Absent)
                // Flood the send buffer to zero-window steady state so the fd is STABLY not writable: a single-EAGAIN snapshot leaves a
                // transient where loopback re-drains and the fd becomes writable, resolving the connect promise (the flake). At steady state
                // the connect's writable provably never fires until the peer reads.
                fillSendBuffer(targetFd)

                val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
                driver.awaitConnect(handle, promise)

                for
                    fiber <- Fiber.init(Abort.run[Closed](promise.safe.get).unit)
                    // Latch on the change-FIFO worker arming write interest: when registeredWrite(targetFd) completes the fiber has
                    // provably parked on the connect promise and the poller registration is live. Interrupting a not-yet-parked fiber would
                    // be the startup race (i); this rules it out. The send buffer is at zero-window steady state (fillSendBuffer flooded to
                    // 64 consecutive EAGAINs), so the write-ready event provably never fires and the connect promise cannot resolve: the
                    // interrupt deterministically wins, regardless of poll-cycle timing.
                    _    <- backend.registeredWrite(targetFd).safe.get
                    done <- fiber.interrupt
                    _    <- Sync.defer(driver.cancel(handle))
                    // cancel routes deregister through the async change-FIFO worker; await it deterministically rather than reading the buffer.
                    _ <- backend.deregisteredFd(targetFd).safe.get
                yield
                    val resolved = promise.poll().isDefined
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, peerFd)
                    PosixTestSockets.closePeerForEof(spy, targetFd)
                    assert(done, "fiber.interrupt returned false: the parked awaitConnect fiber was not interrupted")
                    assert(resolved, "cancel must leave the deposited connect promise terminal (no leaked pending promise)")
                    assert(
                        backend.deregisteredFds.asScala.toList == List(targetFd),
                        s"cancel must deregister the connect fd exactly once, recorded: ${backend.deregisteredFds.asScala.toList}"
                    )
                    succeed
                end for
            }
        }

        "after interrupt + cancel the connect fd re-arms cleanly and a fresh waiter is delivered (no stranded registration)" in {
            PosixTestSockets.assumePoller()
            PosixTestSockets.smallBufferedPair(sndBuf = 2048, rcvBuf = 2048).map { case (writeFd, peerFd) =>
                val targetFd = writeFd
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                discard(driver.start())
                val handle = PosixHandle.socket(targetFd, PosixHandle.DefaultReadBufferSize, Absent)
                fillSendBuffer(targetFd)

                val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
                driver.awaitConnect(handle, promise)

                for
                    fiber <- Fiber.init(Abort.run[Closed](promise.safe.get).unit)
                    // Latch on the change-FIFO worker arming write interest so the fiber has provably parked + the registration is live
                    // before interrupting (rules out startup race i); the zero-window steady-state send buffer means the write-ready event
                    // provably never fires, so the connect promise cannot resolve and the interrupt deterministically wins (rules out race ii,
                    // the INV-FLAKE this leaf was failing on at this line: done was false because the connect promise had completed first).
                    _    <- backend.registeredWrite(targetFd).safe.get
                    done <- fiber.interrupt
                    _    <- Sync.defer(driver.cancel(handle))
                    _    <- backend.deregisteredFd(targetFd).safe.get
                    // Re-arm a FRESH waitable on the same fd after cancel. Drain the peer so the kernel buffer empties and the fd becomes
                    // genuinely writable: the fresh promise resolves Success via the REAL write-ready event. If cancel had stranded the old
                    // pendingWritables entry, this fresh promise's delivery would be blocked / mis-routed.
                    fresh = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    _ <- Sync.defer(driver.awaitWritable(handle, fresh))
                    _ <-
                        PosixTestSockets.drainPeer(driver, PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent), peerFd, 1)
                    second <- Abort.run[Closed](fresh.safe.get)
                yield
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, peerFd)
                    PosixTestSockets.closePeerForEof(spy, targetFd)
                    assert(done, "fiber.interrupt returned false")
                    assert(
                        second.isSuccess,
                        s"a fresh awaitWritable after cancel must be delivered the write-ready event (fd not stranded), got $second"
                    )
                    succeed
                end for
            }
        }

        "interrupting a fiber parked in awaitAccept returns true and cancel cleans up the pending accept op" in {
            PosixTestSockets.assumePoller()
            // An accept's read parks on a real listen fd with NO client connecting, so the real backend never fires an accept-ready event and
            // the promise provably cannot complete: interrupt wins deterministically.
            listenSocket().map { case (serverFd, _) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                discard(driver.start())
                val handle = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent)

                val promise = Promise.Unsafe.init[Int, Abort[Closed]]()
                driver.awaitAccept(handle, promise)

                for
                    fiber <- Fiber.init(Abort.run[Closed](promise.safe.get).unit)
                    // Latch on the change-FIFO worker arming read interest so the fiber has provably parked + the registration is live before
                    // interrupting (rules out startup race i). A listen fd with no client connecting is robustly never accept-ready (only an
                    // incoming connection makes it readable), so the accept promise cannot resolve and the interrupt deterministically wins.
                    _    <- backend.registeredRead(serverFd).safe.get
                    done <- fiber.interrupt
                    _    <- Sync.defer(driver.cancel(handle))
                    _    <- backend.deregisteredFd(serverFd).safe.get
                yield
                    val resolved = promise.poll().isDefined
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, serverFd)
                    assert(done, "fiber.interrupt returned false: the parked awaitAccept fiber was not interrupted")
                    assert(resolved, "cancel must leave the deposited accept promise terminal (no leaked pending promise)")
                    assert(
                        backend.deregisteredFds.asScala.toList == List(serverFd),
                        s"cancel must deregister the accept fd exactly once, recorded: ${backend.deregisteredFds.asScala.toList}"
                    )
                    succeed
                end for
            }
        }

        "after interrupt + cancel the accept fd re-arms cleanly and a fresh waiter is delivered (no stranded registration)" in {
            PosixTestSockets.assumePoller()
            listenSocket().map { case (serverFd, port) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                discard(driver.start())
                val handle = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent)

                val promise = Promise.Unsafe.init[Int, Abort[Closed]]()
                driver.awaitAccept(handle, promise)

                for
                    fiber <- Fiber.init(Abort.run[Closed](promise.safe.get).unit)
                    // Latch on the change-FIFO worker arming read interest so the fiber has provably parked + the registration is live before
                    // interrupting (rules out startup race i). A listen fd with no client connecting is robustly never accept-ready, so the
                    // accept promise cannot resolve and the interrupt deterministically wins.
                    _    <- backend.registeredRead(serverFd).safe.get
                    done <- fiber.interrupt
                    _    <- Sync.defer(driver.cancel(handle))
                    _    <- backend.deregisteredFd(serverFd).safe.get
                    // Re-arm a FRESH accept waiter on the same fd after cancel, then connect a REAL client so the listen fd becomes
                    // accept-ready: the fresh accept promise resolves with the -1 readiness sentinel (the accept-ready signal). If cancel had
                    // stranded the old pendingAccepts entry, this fresh promise's delivery would be blocked / mis-routed.
                    fresh = Promise.Unsafe.init[Int, Abort[Closed]]()
                    _      <- Sync.defer(driver.awaitAccept(handle, fresh))
                    client <- connectClient(port)
                    second <- Abort.run[Closed](fresh.safe.get)
                yield
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, client)
                    PosixTestSockets.closePeerForEof(spy, serverFd)
                    assert(done, "fiber.interrupt returned false")
                    assert(
                        second == Result.succeed(-1),
                        s"a fresh awaitAccept after cancel must be delivered the accept-ready sentinel -1 (fd not stranded), got $second"
                    )
                    succeed
                end for
            }
        }
    }

end PollerIoDriverConnectAcceptInterruptTest
