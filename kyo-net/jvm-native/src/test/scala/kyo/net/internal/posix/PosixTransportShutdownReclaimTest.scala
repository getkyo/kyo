package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsProviderPlatform
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.TlsTestCert
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.ReadOutcome

/** Regression coverage for [[PosixTransport]]'s handshake-obligation reclaim paths: the exactly-once `disarm` gate a registered handshake shares
  * with whatever can reclaim it early, so a race between the handshake's own outcome and an early reclaim never double-frees the fd/engine and
  * never strands them.
  *
  * Two reclaim points remain now that the transport itself is process-lifetime and never closes: a listener's own `close()`, which sweeps every
  * handshake it owns ([[PosixTransport.dischargeListenerHandshakes]]), and the caller's own interrupt of an in-flight connect, which discharges
  * the connect-side handshake it was awaiting ([[PosixTransport.registerHandshake]]'s no-owner form). Each leaf below exercises one of those
  * races directly against a real [[PosixTransport]] built via [[TestTransports.forTesting]] over a real driver, so this test's own cleanup closes
  * the driver, not the transport, mirroring production (a transport is never closed).
  */
class PosixTransportShutdownReclaimTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private val serverTls = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )

    private def assumeTlsReady(): Unit =
        PosixTestSockets.assumePoller()
        try discard(TlsProviderPlatform.engine(serverTls, "localhost", isServer = true))
        catch case _: Throwable => cancel("no TLS provider staged for this host")
    end assumeTlsReady

    /** Connect a raw client socket to `port` without accepting or exchanging any application/handshake bytes; returns the client fd. */
    private def connectRaw(port: Int)(using Frame, kyo.test.AssertScope): Int < Async =
        val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0))).map(_ => client)
    end connectRaw

    "PosixTransport registerHandshake races its OWNING LISTENER's close sweep" - {
        "a handshake registered after its listener already swept is discharged by the insertion recheck, exactly once" in {
            PosixTestSockets.assumePoller()
            val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real      = PollerBackend.default()
            val pollerFd  = real.create()
            val backend   = RecordingPollerBackend(real)
            val driver    = TestDrivers.forBackend(backend, pollerFd, spy)
            val transport = TestTransports.forTesting(driver, spy, backendIsEpoll = false)
            discard(driver.start())
            // Captured BEFORE close(), per closeWake's documented contract: it notifies whatever promise is installed at the moment it runs,
            // so installing one after close() races the driver's terminal exit and can never be notified.
            val closeWakeDone = backend.closeWakeDone()
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                // A listener that is ALREADY closed, standing in for one that closed inside handleAccepted's window: the accept-side
                // registration happens on the driver carrier at the end of handleAccepted, and dischargeListenerHandshakes runs on the
                // closing carrier, so a close anywhere in that window sweeps an empty map and this registration arrives afterwards.
                val closedListener =
                    new PosixListener(
                        serverFd = -1,
                        port = 0,
                        host = "127.0.0.1",
                        address = kyo.net.NetAddress.Tcp("127.0.0.1", 0),
                        sockets = spy,
                        registry = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap[
                            PosixListener,
                            java.lang.Boolean
                        ]()),
                        closedFlag = AtomicBoolean.Unsafe.init(true)
                    )
                val handle    = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                val rawEngine = TlsRealEngines.singleEngine(isServer = true)
                val engine    = new RecordingTlsEngine(rawEngine)
                val reaped    = AtomicBoolean.Unsafe.init(false)
                val settled   = AtomicBoolean.Unsafe.init(false)
                // Mirrors handleAccepted's own teardown shape: reap through the driver, then the raw fd shutdown and engine free.
                def teardown(): Unit =
                    reaped.set(true)
                    driver.closeHandle(handle)
                    if handle.claimFdClose() then discard(spy.shutdown(accepted, PosixConstants.SHUT_RDWR))
                    engine.free()
                end teardown
                // The accept path's exactly-once gate, the same one armHandshakeDeadline builds.
                def disarm(): Boolean = settled.compareAndSet(false, true)
                // Without the insertion recheck this entry sits in pendingHandshakes with nothing that would ever discharge it: the
                // listener's sweep has passed, a second close is a CAS no-op, and the transport-wide sweep never runs on a shared transport.
                discard(transport.registerHandshake(Present(closedListener), () => disarm(), () => teardown()))
                discard(spy.close(client))
                assert(
                    reaped.get(),
                    "a handshake registered after its listener's sweep must be discharged by the insertion recheck, or its fd and engine leak for the process lifetime"
                )
                // Exactly once: the gate is spent, so the handshake's own outcome callback finds it lost and does not tear down twice.
                assert(!disarm(), "the discharge must have won the exactly-once gate, leaving nothing for a later outcome to double-free")
                assert(engine.freeCount.get() == 1, s"the engine must be freed exactly once, got ${engine.freeCount.get()}")
                driver.close()
                // Await the driver's terminal teardown before the leaf ends, or its poll carrier is still parked in kevent when the
                // end-of-run leak check samples the scheduler.
                closeWakeDone.safe.get.map(_ => succeed)
            }
        }
    }

    "PosixTransport connect-side TLS handshake abandoned by its caller" - {
        "an interrupted TLS connect discharges the handshake obligation: the engine is freed and the fd closed exactly once" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            // A want-read engine parks the connect handshake on its first read; the peer sends nothing, so the abandoned caller's promise
            // settlement is the only route that can release the fd and engine. Built before the transport and injected through buildEngine.
            val engine = new ParkedWantReadEngine
            val transport =
                TestTransports.forTesting(
                    driver,
                    spy,
                    backendIsEpoll = false,
                    buildEngine = (_, _, _) => engine
                )
            discard(driver.start())
            // A RAW listening socket entirely outside the transport (the connect-side idiom above): the kernel's backlog completes the
            // TCP connect, the client's want-read handshake sends nothing and parks awaiting bytes this peer never sends, and no deadline
            // exists on the connect path, so the promise-settlement discharge is the ONLY route that can ever release the fd and engine.
            // An interrupted caller (a timeout, a losing race arm, an enclosing abort) settles the connect promise exactly like the
            // upgrade path's abandoned caller does.
            val rawServer = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (sa, sl)  = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
            // The driver is closed last, as the sibling leaves do: this leaf started it, and a transport is process-lifetime, so nothing
            // else ever reclaims its poller fd or stops its poll loop.
            Sync.ensure(Sync.defer { sa.close(); discard(sock.close(rawServer)); driver.close() }) {
                assert(sock.bind(rawServer, sa, sl).value == 0)
                assert(sock.listen(rawServer, 4).value == 0)
                val out = Buffer.alloc[Byte](SockAddr.inet4Size)
                val ol  = Buffer.alloc[Int](1)
                ol.set(0, SockAddr.inet4Size)
                val port =
                    try
                        assert(sock.getsockname(rawServer, out, ol).value == 0)
                        ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                    finally
                        out.close()
                        ol.close()
                for
                    fiber <-
                        Fiber.init(Abort.run[NetException](transport.connectTls("127.0.0.1", port, NetTlsConfig(trustAll = true)).safe.get))
                    _    <- assertEventually(Sync.defer(engine.stepCount.get() >= 1))
                    done <- fiber.interrupt
                    _ = assert(done, "fiber.interrupt returned false: the parked connect handshake fiber was not interrupted")
                    // The settlement discharge must release everything the abandoned handshake held: the engine freed and the fd closed,
                    // each exactly once. A stranded handshake leaves the engine unfreed and the fd open forever (nothing else can reach
                    // them: no Connection was ever wired, no deadline is armed, and the process-shared transport is never closed).
                    _ <- assertEventually(Sync.defer(engine.freed.get()))
                    _ <- assertEventually(Sync.defer(spy.closeCounts.size() >= 1))
                yield
                    import scala.jdk.CollectionConverters.*
                    val counts = spy.closeCounts.asScala.toMap
                    assert(counts.size == 1, s"exactly the abandoned connect's fd must be closed, got $counts")
                    assert(counts.values.forall(_ == 1), s"the fd must be closed exactly once, got $counts")
                end for
            }
        }
    }

    "closing a listener reclaims the handshakes it accepted" - {

        // The transport-wide sweep runs only from close(), and the process-shared transport is never closed, so for a server the reclamation
        // point that actually happens is its listener closing. Without a per-listener discharge, a handshake still in flight when the server
        // shut down held its fd and TLS engine for the life of the process.
        //
        // This leaf pins the case with NO deadline armed (handshakeTimeout = Infinity, which is what a kyo-http server ships), so the listener
        // close is the only thing that can reclaim it: with a finite deadline the timer would eventually do it and the leaf would pass whether
        // or not the discharge existed.
        "a stalled accept handshake with no deadline is released when its listener closes" in {
            PosixTestSockets.assumePoller()
            assumeTlsReady()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            val captured = new AtomicReference[RecordingTlsEngine]()
            val transport = TestTransports.forTesting(
                driver,
                spy,
                backendIsEpoll = false,
                buildEngine = (cfg, host, isServer) =>
                    val e = new RecordingTlsEngine(TlsProviderPlatform.engine(cfg, host, isServer))
                    captured.set(e)
                    e
            )
            discard(driver.start())
            val unbounded = serverTls.copy(handshakeTimeout = Duration.Infinity)
            transport.listenTls("127.0.0.1", 0, 4, unbounded)(_ => ()).safe.get.map { listener =>
                // Guards the listener even on a path that fails before the explicit close()/driver.close() below run (e.g. connectRaw or
                // an assertEventually failing). Idempotent, so it is a harmless no-op after the explicit listener.close() on the success path.
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    val baseline = backend.registerReadCount.get()
                    // A raw, non-TLS client: the server's handshake starts and then parks on a read for a ClientHello that never arrives. No
                    // Connection exists for an in-flight handshake, so nothing else tracks this fd.
                    connectRaw(listener.port).map { clientFd =>
                        // Same guard for the raw client fd: closed explicitly below on success, but reclaimed here on any earlier failure.
                        Scope.ensure(Sync.defer(discard(sock.close(clientFd)))).andThen {
                            assertEventually(Sync.defer(backend.registerReadCount.get() > baseline)).map { _ =>
                                // Close ONLY the listener. The transport stays open, exactly as the process-shared transport does.
                                //
                                // The client fd stays OPEN and silent across the assertion below, and that is what makes this discriminating. Closing
                                // it here would end the stalled handshake by itself: the server's parked read fails, its onFailed teardown runs, and
                                // the engine is freed whether or not the listener discharged anything. Verified by removing the discharge and watching
                                // an earlier version of this leaf still pass. With the peer held open there is no other route to engine.free().
                                listener.close()
                                assertEventually(Sync.defer(captured.get() != null && captured.get().freeCount.get() == 1)).map { _ =>
                                    val engine = captured.get()
                                    assert(
                                        engine.freeCount.get() == 1,
                                        s"the stalled handshake's engine must be freed exactly once by the listener close, was ${engine.freeCount.get()}"
                                    )
                                    discard(sock.close(clientFd))
                                    driver.close()
                                    succeed
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end PosixTransportShutdownReclaimTest
