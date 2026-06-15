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
  * the awaited op provably cannot resolve, by a STRUCTURAL guarantee the kernel cannot break on its own: a connect's writable parks on a fd
  * stuck in SYN-SENT against a non-routable black-hole address (`connectInFlight`: a connect to RFC 5737 TEST-NET-1 gets no SYN-ACK and no RST,
  * so the handshake neither completes nor errors; the OS signals write-readiness on a connecting socket only when it succeeds or fails), and an
  * accept's read parks on a real listen fd with no client connecting (a listen fd is readable only on an incoming connection). Both are events
  * the kernel can produce only from an external action the setup denies, so the awaited op cannot complete and `fiber.interrupt` deterministically
  * interrupts the parked fiber and returns `true` regardless of timing (the same "the op cannot complete, so interrupt is deterministic" reasoning
  * the take-interrupt and `closeHandle`-cancels-pending-read tests rely on). A loopback send-buffer flood is NOT used for the connect side: the
  * kernel re-drains a loopback send buffer into the peer's recv buffer, briefly re-arming write-readiness, which can resolve the parked connect
  * before the interrupt and is the source of probabilistic interrupt-loss on single-OS-thread runtimes; the SYN-SENT black-hole park has no event.
  *
  * Cleanup is proven via a [[RecordingPollerBackend]] over the real epoll/kqueue: it records every `deregister` call, and after the interrupt
  * the test calls `driver.cancel(handle)` (the path the transport wires to interrupt: `awaitConnectThen`'s failure/interrupt close and
  * `listener.onClose(() => driver.cancel)`). `cancel` must (a) leave the deposited promise terminal, not a leaked pending promise (the
  * interrupt wins the completion race with a Panic; cancel's own `Closed` completion is then a no-op, but the map removal + deregister still
  * run), and (b) submit exactly one `deregister` for the fd through the change-FIFO worker (the registration is released, no fd/interest leak).
  * A companion pair of tests then re-arms a FRESH waiter on the same fd after cancel and proves the fd slot is clean and re-registerable. The
  * accept re-arm fires REAL readiness (connect a client so the listen fd becomes accept-ready and the fresh accept promise resolves with the -1
  * sentinel). The connect re-arm cannot complete its black-holed SYN-SENT handshake on demand, so it proves the re-armed slot is live by
  * delivering a real terminal event another way: `driver.close()` fails every entry still in `pendingWritables`, so the freshly re-armed promise
  * resolves with `Closed`; a stranded or mis-routed registration would leave it pending forever.
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
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value.toInt
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
        val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value.toInt
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0, "connect failed"))).map {
            _ =>
                val shim = Ffi.load[PosixShimBindings]
                assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                client
        }
    end connectClient

    "PollerIoDriver interrupt of a fiber parked in a driver-level op" - {

        "interrupting a fiber parked in awaitConnect returns true and cancel cleans up the pending writable op" in {
            PosixTestSockets.assumePoller()
            // A connect's writable parks on a real fd whose connect to a non-routable black-hole address is stuck in SYN-SENT (no SYN-ACK and no
            // RST, so the handshake neither completes nor errors), so the real backend never fires a write-ready event and the promise provably
            // cannot complete: interrupt wins. This is the connect-side mirror of the accept leaf's never-accept-ready listen fd, a STRUCTURAL
            // guarantee rather than the loopback send-buffer flood, which the kernel re-drains and which thus briefly re-arms write-readiness.
            PosixTestSockets.connectInFlight().map { inFlight =>
                val targetFd = inFlight.targetFd
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                discard(driver.start())
                val handle = PosixHandle.socket(targetFd, PosixHandle.DefaultReadBufferSize, Absent)

                val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
                driver.awaitConnect(handle, promise)

                for
                    fiber <- Fiber.init(Abort.run[Closed](promise.safe.get).unit)
                    // Latch on the change-FIFO worker arming write interest: when registeredWrite(targetFd) completes the fiber has
                    // provably parked on the connect promise and the poller registration is live. Interrupting a not-yet-parked fiber would
                    // be the startup race (i); this rules it out. The connect is parked in SYN-SENT against a black-hole address, so the
                    // write-ready event provably never fires and the connect promise cannot resolve: the interrupt deterministically wins,
                    // regardless of poll-cycle timing.
                    _    <- backend.registeredWrite(targetFd).safe.get
                    done <- fiber.interrupt
                    _    <- Sync.defer(driver.cancel(handle))
                    // cancel routes deregister through the async change-FIFO worker; await it deterministically rather than reading the buffer.
                    _ <- backend.deregisteredFd(targetFd).safe.get
                yield
                    val resolved = promise.poll().isDefined
                    driver.close()
                    PosixTestSockets.closeInFlight(spy, inFlight)
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
            PosixTestSockets.connectInFlight().map { inFlight =>
                val targetFd = inFlight.targetFd
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                discard(driver.start())
                val handle = PosixHandle.socket(targetFd, PosixHandle.DefaultReadBufferSize, Absent)

                val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
                driver.awaitConnect(handle, promise)

                for
                    fiber <- Fiber.init(Abort.run[Closed](promise.safe.get).unit)
                    // Latch on the change-FIFO worker arming write interest so the fiber has provably parked + the registration is live
                    // before interrupting (rules out startup race i); the connect is parked in SYN-SENT against a black-hole address, so the
                    // write-ready event provably never fires, so the connect promise cannot resolve and the interrupt deterministically wins.
                    _    <- backend.registeredWrite(targetFd).safe.get
                    done <- fiber.interrupt
                    _    <- Sync.defer(driver.cancel(handle))
                    _    <- backend.deregisteredFd(targetFd).safe.get
                    // Re-arm a FRESH waitable on the same fd after cancel, then deliver a real terminal event to it. armSocketWritable inserts the
                    // fresh promise into pendingWritables[targetFd] synchronously, then driver.close fails every entry still in pendingWritables with
                    // Closed. Both run in ONE synchronous block with no suspension between them, so the close is the deterministic deliverer (no
                    // poll-cycle can resolve the fresh promise from a kernel event in between). A stranded prior entry from cancel would block this
                    // re-arm or leave the fresh promise pending; here it resolves with Closed, proving the slot is clean and the registration live.
                    fresh = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    second <- Sync.defer {
                        driver.awaitWritable(handle, fresh)
                        driver.close()
                    }.andThen(Abort.run[Closed](fresh.safe.get))
                yield
                    PosixTestSockets.closeInFlight(spy, inFlight)
                    assert(done, "fiber.interrupt returned false")
                    assert(
                        second.isFailure,
                        s"the fresh awaitWritable promise on the re-armed fd must be delivered a terminal Closed on driver close (registration live, not stranded), got $second"
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
