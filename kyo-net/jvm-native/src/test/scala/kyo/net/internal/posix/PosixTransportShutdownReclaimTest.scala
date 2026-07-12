package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsProviderPlatform
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.tls.TlsTestCert
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.ReadOutcome

/** A scripted [[TlsEngine]] fake (never wraps or delegates to a real engine, so a use-after-free here touches only this class's own JVM fields,
  * never native BoringSSL/JDK memory) that self-perpetuates the `WantWrite` branch of `PosixTransport.driveHandshake`'s step loop: `handshakeStep`
  * always returns `-1` (want-write), and `drainCiphertext` returns a few dummy bytes exactly once per step, then `0` (so the drain phase ends and
  * the loop re-enters `step`, which submits a fresh engine op). On the `triggerAt`-th `handshakeStep` call, invokes `onTrigger` (a `transport.
  * close()`) SYNCHRONOUSLY, before returning, so the production use-after-free race reproduces with zero timing luck: that call runs on the
  * engine-FIFO worker INSIDE the still-executing step thunk, so `close()`'s sweep offers its free op `F` into the SAME
  * queue; the step thunk's own continuation (re-entering `step`, a fresh `submitEngineOp`) then offers the next step `S` strictly AFTER `F`. The
  * FIFO's single-consumer drain runs `F` (free) then `S`; `usedAfterFree` is set if `S` (or any later op) touches the engine post-free.
  *
  * `completeAfterTrigger` (default `false`, unchanged behavior for the two leaves above) makes the `triggerAt`-th call return `1` (handshake
  * done) instead of `-1`, for the accept-side `armHandshakeDeadline` Infinity-gate leaf below: that leaf needs `onTrigger`'s `transport.close()`
  * (which races `close()`'s sweep against this SAME handshake reaching `Done`) to be followed immediately by `driveHandshake`'s `Done` branch,
  * not another `WantWrite` round trip.
  */
private class SelfPerpetuatingFakeEngine(triggerAt: Int, onTrigger: () => Unit, completeAfterTrigger: Boolean = false) extends TlsEngine:
    val freed                = new JAtomicBoolean(false)
    val usedAfterFree        = new JAtomicBoolean(false)
    private val stepCount    = new AtomicInteger(0)
    private val pendingDrain = new AtomicInteger(0)

    // Mirrors RecordingTlsEngine.touch(): flip usedAfterFree if any method (other than free() itself) runs once freed is set.
    private def touch(): Unit = if freed.get() then usedAfterFree.set(true)

    def handshakeStep()(using AllowUnsafe): Int =
        touch()
        pendingDrain.set(4)
        if stepCount.incrementAndGet() == triggerAt then
            onTrigger()
            if completeAfterTrigger then 1 else -1
        else -1
        end if
    end handshakeStep

    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        touch()
        len
    end feedCiphertext

    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        touch()
        val n = pendingDrain.getAndSet(0)
        var i = 0
        while i < n do
            buf.set(i, 0.toByte)
            i += 1
        n
    end drainCiphertext

    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        touch()
        0
    end readPlain

    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        touch()
        0
    end writePlain

    def hasBufferedPlaintext(using AllowUnsafe): Boolean =
        touch()
        false
    end hasBufferedPlaintext

    def readBuffered()(using AllowUnsafe): Span[Byte] =
        touch()
        Span.empty
    end readBuffered

    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]] =
        touch()
        Absent
    end certSha256

    def shutdownStep()(using AllowUnsafe): Int =
        touch()
        0
    end shutdownStep

    // Not a use-after-free of itself, mirroring RecordingTlsEngine.free()'s own note: touch() is not called here.
    def free()(using AllowUnsafe): Unit = freed.set(true)
end SelfPerpetuatingFakeEngine

/** End-to-end regression coverage for `PosixTransport.close()`'s transport-level reclaim guarantee: it must never strand an fd behind either of
  * the two mechanisms the driver-level fixes alone cannot reach on their own -- a Connection whose WritePump is genuinely parked on writability
  * (`pool.close()`'s driver teardown must fail that parked promise so the WritePump's re-entrant close reaches `driver.closeHandle`), and an
  * accept-side TLS handshake that never completes (`PosixTransport.pendingHandshakes`'s sweep, since a still-handshaking fd has no
  * [[InternalConnection]] for the ordinary `connections` sweep to find).
  *
  * Both leaves use a [[RecordingSocketBindings]] spy shared by the driver AND the transport (via [[TestTransports.forTesting]]) as the sole fd
  * observation point, so no fd number needs to be known in advance: the assertions are on the total shape of what got closed (count of distinct
  * fds, each closed exactly once), which is precise without brittle fd-number bookkeeping.
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

    "PosixTransport close reclaims a connection parked on writability" - {
        "the fd is closed and never double-closed once the driver's terminal teardown fails the parked writable" in {
            PosixTestSockets.assumePoller()
            val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real      = PollerBackend.default()
            val pollerFd  = real.create()
            val backend   = RecordingPollerBackend(real)
            val driver    = TestDrivers.forBackend(backend, pollerFd, spy)
            val transport = TestTransports.forTesting(kyo.net.TransportConfig.default, driver, spy, backendIsEpoll = false)
            discard(driver.start())
            // The accept handler shrinks its own receive buffer and never reads a byte, so the client's write genuinely backpressures over a
            // real socket (no scripted EAGAIN).
            transport.listen("127.0.0.1", 0, 4) { accepted =>
                val acceptedHandle = accepted.asInstanceOf[InternalConnection[PosixHandle]].handle
                PosixTestSockets.setIntSockOpt(acceptedHandle.readFd, PosixConstants.SO_RCVBUF, 4096)
            }.safe.get.map { listener =>
                transport.connect("127.0.0.1", listener.port).safe.get.map { client =>
                    val conn   = client.asInstanceOf[InternalConnection[PosixHandle]]
                    val handle = conn.handle
                    PosixTestSockets.setIntSockOpt(handle.writeFd, PosixConstants.SO_SNDBUF, 4096)
                    // Enqueue directly onto the outbound channel: a queued span the WritePump has not yet fully flushed when close() runs.
                    val payload = Span.fromUnsafe(Array.fill[Byte](2 * 1024 * 1024)(9.toByte))
                    conn.outbound.offer(payload) match
                        case Result.Success(true) => ()
                        case other                => fail(s"outbound.offer must accept the queued span, got $other")
                    // Wait for a real EAGAIN to arm writability: the WritePump is now genuinely parked (outboundDrained not done, so
                    // Connection.close() alone cannot release the handle).
                    backend.registeredWrite(handle.writeFd).safe.get.map { _ =>
                        transport.close()
                        assertEventually(Sync.defer(spy.closeCounts.getOrDefault(handle.writeFd, 0) >= 1)).map { _ =>
                            assert(
                                spy.closeCounts.getOrDefault(handle.writeFd, 0) == 1,
                                s"the fd must be closed exactly once, never stranded, counts=${spy.closeCounts}"
                            )
                        }
                    }
                }
            }
        }
    }

    "PosixTransport close reclaims an in-flight accept-side handshake" - {
        "close() sweeps a stalled accept handshake's fd and engine, never stranding or double-closing it" in {
            assumeTlsReady()
            val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real      = PollerBackend.default()
            val pollerFd  = real.create()
            val backend   = RecordingPollerBackend(real)
            val driver    = TestDrivers.forBackend(backend, pollerFd, spy)
            val transport = TestTransports.forTesting(kyo.net.TransportConfig.default, driver, spy, backendIsEpoll = false)
            discard(driver.start())
            // The handshake engine is otherwise transport-internal, with no other injection point, so testEngineFactory wraps the
            // real engine buildEngine constructs in a RecordingTlsEngine spy, making its free() count observable below.
            val capturedEngine = new AtomicReference[RecordingTlsEngine]()
            transport.testEngineFactory = Present { (cfg, host, isServer) =>
                val e = new RecordingTlsEngine(TlsProviderPlatform.engine(cfg, host, isServer))
                capturedEngine.set(e)
                e
            }
            transport.listen("127.0.0.1", 0, 4, serverTls)(_ => ()).safe.get.map { listener =>
                val baseline = backend.registerReadCount.get()
                // A raw, non-TLS client that connects and sends nothing: the server's accept-side handshake starts (buildEngine + the first
                // driveHandshake step arms a read for the ClientHello that never arrives) and stalls forever, genuinely in flight when close()
                // runs. This handle is NEVER wrapped in a tracked Connection (no InternalConnection exists for an in-flight handshake), so the
                // ONLY path that can reclaim it is the pendingHandshakes sweep in close().
                connectRaw(listener.port).map { clientFd =>
                    assertEventually(Sync.defer(backend.registerReadCount.get() > baseline)).map { _ =>
                        transport.close()
                        discard(sock.close(clientFd))
                        // Two distinct fds must end up closed: the listener's own fd and the stalled handshake's fd, each exactly once. A
                        // stranded handshake would leave this at 1 (only the listener); a double-close would show a count > 1 for some fd.
                        assertEventually(Sync.defer(spy.closeCounts.size() >= 2)).map { _ =>
                            import scala.jdk.CollectionConverters.*
                            val counts = spy.closeCounts.asScala.toMap
                            assert(counts.size == 2, s"expected exactly 2 closed fds (listener + stalled handshake), got $counts")
                            assert(counts.values.forall(_ == 1), s"every fd must be closed exactly once, got $counts")
                            // The engine-reclaimed assertion: a stranded
                            // handshake leaks its TLS engine on top of its fd; freeCount == 1 confirms sweepPendingHandshakes's teardown
                            // thunk reached engine.free(), not just the fd close.
                            val engine = capturedEngine.get()
                            assert(engine != null, "the accept-side handshake must have built an engine through testEngineFactory")
                            assert(
                                engine.freeCount.get() == 1,
                                s"the handshake engine must be freed exactly once, was ${engine.freeCount.get()}"
                            )
                        }
                    }
                }
            }
        }
    }

    "PosixTransport registerHandshake races transport.close()'s one-shot sweep" - {
        "a handshake registered after close() has already swept discharges via the driver's own closed-recheck, exactly once" in {
            PosixTestSockets.assumePoller()
            val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real      = PollerBackend.default()
            val pollerFd  = real.create()
            val backend   = RecordingPollerBackend(real)
            val driver    = TestDrivers.forBackend(backend, pollerFd, spy)
            val transport = TestTransports.forTesting(kyo.net.TransportConfig.default, driver, spy, backendIsEpoll = false)
            discard(driver.start())
            // Capture closeWakeDone() BEFORE triggering close(), per its own documented contract (RecordingDecorators.scala's closeWake
            // notifies whatever promise is installed in closeWakeDoneRef AT THE MOMENT it runs; calling closeWakeDone() after close() races
            // the driver's own terminal exit and can install the promise too late to ever be notified -- observed as a deterministic hang on
            // this host). Close the transport with nothing registered yet: sweepPendingHandshakes runs and finds pendingHandshakes empty, and
            // the driver's own terminal exit runs to completion. This reproduces, deterministically, the race a handshake registration that
            // arrives after close()'s one-shot sweep has already passed would otherwise need a genuine, hard-to-drive timing race to hit.
            val closeWakeDone = backend.closeWakeDone()
            transport.close()
            closeWakeDone.safe.get.map { _ =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val handle    = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val rawEngine = TlsRealEngines.singleEngine(isServer = true)
                    val engine    = new RecordingTlsEngine(rawEngine)
                    val reaped    = AtomicBoolean.Unsafe.init(false)
                    // Mirrors handleAccepted's own teardown shape exactly: closeHandle (Absent-tls, since onFinished never ran to set
                    // handle.tls), then the raw fd shutdown/close and engine.free() the accept path runs alongside it.
                    def teardown(): Unit =
                        reaped.set(true)
                        driver.closeHandle(handle)
                        if handle.claimFdClose() then discard(sock.shutdown(accepted, PosixConstants.SHUT_RDWR))
                        engine.free()
                    end teardown
                    // Register the handshake obligation ONLY NOW, after close()'s sweep already ran: sweepPendingHandshakes will never run
                    // again for this transport, so if the registry's sweep were the sole reclaim path this handshake would leak forever.
                    val (token, disarm) = transport.registerHandshake(() => teardown())
                    val promise         = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    // Arm the read exactly as driveHandshake would for the handshake's next flight. The driver is already fully terminal
                    // (closeWakeDone above already fired), so the offer-then-recheck fails this promise INLINE instead of leaving it
                    // stranded in a dead regIntake/changeQueue that nothing will ever drain again.
                    driver.awaitRead(handle, promise)
                    promise.poll() match
                        case Present(Result.Failure(_)) =>
                            () // caller promise failed, synchronously, exactly as the offer-then-recheck guarantees
                        case other => fail(s"awaitRead must fail the promise inline once the driver is terminal, got $other")
                    end match
                    // Mirror driveHandshake's own onFailed dispatch: win the shared disarm gate, unregister, and run the same teardown
                    // sweepPendingHandshakes would have run had it not already passed this handshake by.
                    if disarm() then
                        transport.unregisterHandshake(token)
                        teardown()
                    discard(sock.close(client))
                    assert(reaped.get(), "the handshake teardown must have run")
                    assert(spy.closeCounts.getOrDefault(accepted, 0) == 1, s"the fd must be closed exactly once, counts=${spy.closeCounts}")
                    assert(engine.freeCount.get() == 1, s"the engine must be freed exactly once, was ${engine.freeCount.get()}")
                }
            }
        }
    }

    "PosixTransport close() racing a mid-flight handshake's own step chain" - {
        "connect-side: close() sweeps the engine while a step is still enqueuing, and the next queued step skips instead of touching it" in {
            PosixTestSockets.assumePoller()
            val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real      = PollerBackend.default()
            val pollerFd  = real.create()
            val backend   = RecordingPollerBackend(real)
            val driver    = TestDrivers.forBackend(backend, pollerFd, spy)
            val transport = TestTransports.forTesting(kyo.net.TransportConfig.default, driver, spy, backendIsEpoll = false)
            discard(driver.start())
            // A RAW listening socket, entirely OUTSIDE this transport (never accepted into a tracked Connection): `transport.close()` tears
            // down only ITS OWN driver/connections, so this peer survives the close and the client's small writes keep landing in its kernel
            // backlog's receive buffer (unread, harmless at this leaf's scale) instead of failing with a broken-pipe/reset once the transport
            // side goes away. A `transport.listen(...)`-backed peer would be wrong here: it shares this transport's driver, so `close()` would
            // tear it down too and the client's post-close write would fail via `onFailed` before ever reaching the queued next step.
            val rawServer = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (sa, sl)  = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
            Sync.ensure(Sync.defer(sa.close())) {
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
                val engine = new SelfPerpetuatingFakeEngine(triggerAt = 3, onTrigger = () => transport.close())
                transport.testEngineFactory = Present { (_, _, _) => engine }
                Abort.run[Closed](transport.connect("127.0.0.1", port, NetTlsConfig(trustAll = true)).safe.get).map { outcome =>
                    assert(outcome.isFailure, s"the connect handshake must fail once transport.close() races it mid-flight, got $outcome")
                }.andThen {
                    assertEventually(Sync.defer(engine.freed.get())).map { _ =>
                        assert(
                            !engine.usedAfterFree.get(),
                            "no engine method may run after free() once close() races a still-chaining connect handshake"
                        )
                    }
                }.andThen(Sync.defer(discard(sock.close(rawServer))))
            }
        }

        "upgrade-side: close() sweeps the engine while a STARTTLS step is still enqueuing, and the next queued step skips instead of touching it" in {
            PosixTestSockets.assumePoller()
            val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real      = PollerBackend.default()
            val pollerFd  = real.create()
            val backend   = RecordingPollerBackend(real)
            val driver    = TestDrivers.forBackend(backend, pollerFd, spy)
            val transport = TestTransports.forTesting(kyo.net.TransportConfig.default, driver, spy, backendIsEpoll = false)
            discard(driver.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                // The peer end is never read from (mirrors the connect-side leaf above): the upgrade's fake-engine bytes land in its receive
                // buffer, unread, which is harmless at this leaf's scale. It is NEVER owned by the transport (no InternalConnection wraps it),
                // so this leaf, not close()'s sweep, is responsible for releasing it: closed unconditionally via Sync.ensure.
                Sync.ensure(Sync.defer(discard(sock.close(peerFd)))) {
                    val handle    = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val plaintext = transport.openWith(handle, driver)
                    plaintext.start()
                    val engine = new SelfPerpetuatingFakeEngine(triggerAt = 3, onTrigger = () => transport.close())
                    transport.testEngineFactory = Present { (_, _, _) => engine }
                    Abort.run[Closed](transport.upgradeToTls(
                        plaintext,
                        NetTlsConfig(trustAll = true),
                        kyo.net.TransportConfig.default.channelCapacity
                    ).safe.get).map { outcome =>
                        assert(
                            outcome.isFailure,
                            s"the STARTTLS upgrade must fail once transport.close() races it mid-flight, got $outcome"
                        )
                    }.andThen {
                        assertEventually(Sync.defer(engine.freed.get())).map { _ =>
                            assert(
                                !engine.usedAfterFree.get(),
                                "no engine method may run after free() once close() races a still-chaining STARTTLS upgrade"
                            )
                        }
                    }
                }
            }
        }
    }

    "PosixTransport accept-side handshake completes the instant close() sweeps it, under an Infinity handshake deadline" - {
        "the already-freed engine is never wired into handle.tls (armHandshakeDeadline's Infinity-branch disarm must still be one-shot)" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            // handshakeTimeout = Infinity exercises armHandshakeDeadline's no-timer branch, whose returned `disarm` is what this leaf targets:
            // registerHandshake hands this SAME disarm to BOTH the handshake's own onFinished and close()'s sweepPendingHandshakes, and a
            // constant-true gate (the pre-fix shape) lets both proceed instead of exactly one.
            val config    = kyo.net.TransportConfig.default.copy(handshakeTimeout = Duration.Infinity)
            val transport = TestTransports.forTesting(config, driver, spy, backendIsEpoll = false)
            discard(driver.start())
            // No real TLS provider needed: testEngineFactory bypasses buildEngine's TlsProviderPlatform.engine call entirely, so serverTls's
            // cert/key paths are never read. completeAfterTrigger = true: the SAME call that synchronously runs transport.close() (racing
            // close()'s sweep against this handshake's own outcome) also reports the handshake Done, so driveHandshake's Done branch reaches
            // onFinished immediately afterward, in the same call chain, with zero timing luck.
            val engine = new SelfPerpetuatingFakeEngine(triggerAt = 1, onTrigger = () => transport.close(), completeAfterTrigger = true)
            transport.testEngineFactory = Present { (_, _, _) => engine }
            transport.listen("127.0.0.1", 0, 4, serverTls)(_ => ()).safe.get.map { listener =>
                connectRaw(listener.port).map { clientFd =>
                    assertEventually(Sync.defer(engine.freed.get())).map { _ =>
                        // `usedAfterFree` is the same synchronous signal the connect-side/upgrade-side leaves above use, and it is exactly
                        // equivalent to "wired into a live connection" here: if onFinished's own disarm() call also won, `handle.tls =
                        // Present(engine)` runs, then `spawnHandler` -> `installCertHash` -> `engine.certSha256()` touches the ALREADY-freed
                        // engine synchronously, before spawnHandler's asynchronous handler-fiber submission -- so this assertion does not
                        // depend on that fiber ever actually running.
                        assert(
                            !engine.usedAfterFree.get(),
                            "close()'s sweep and the handshake's own onFinished must not BOTH win the disarm gate: the already-freed engine " +
                                "must never be wired into handle.tls and touched again by installCertHash/deliverHandshakePlaintext"
                        )
                        discard(sock.close(clientFd))
                    }
                }
            }
        }
    }

end PosixTransportShutdownReclaimTest
