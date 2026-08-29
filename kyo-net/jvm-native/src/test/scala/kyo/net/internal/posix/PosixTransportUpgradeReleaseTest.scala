package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Connection
import kyo.net.NetConnectionClosedException
import kyo.net.NetConnectionClosedException.Operation
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.NetTlsProviderUnavailableException
import kyo.net.Test
import kyo.net.internal.TlsEngine

/** A scripted [[TlsEngine]] fake that parks the handshake on its first read: `handshakeStep` always returns `0` (want-read) and
  * `drainCiphertext` never produces bytes, so `driveHandshake` goes straight to its read path and parks against a peer that sends nothing.
  * `stepCount` records that the handshake genuinely reached its park point; `freed` records the engine release.
  */
private class ParkedWantReadEngine extends TlsEngine:
    val freed     = new JAtomicBoolean(false)
    val stepCount = new AtomicInteger(0)

    def handshakeStep()(using AllowUnsafe): Int =
        discard(stepCount.incrementAndGet())
        0
    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int  = len
    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int       = 0
    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = len
    def hasBufferedPlaintext(using AllowUnsafe): Boolean                     = false
    def readBuffered()(using AllowUnsafe): Span[Byte]                        = Span.empty
    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]                   = Absent
    def shutdownStep()(using AllowUnsafe): Int                               = 0
    def free()(using AllowUnsafe): Unit                                      = freed.set(true)
end ParkedWantReadEngine

/** A scripted [[TlsEngine]] fake whose handshake completes on the first step (`handshakeStep` returns `1` immediately), with a one-shot
  * callback fired from `certSha256` (the first engine call `upgradeRole.onFinished` makes after winning the handshake's outcome gate, via
  * `wireUpgraded` -> `installCertHash`). The callback therefore runs INSIDE onFinished's body, after the discharge hook has already lost the
  * outcome gate and before the upgrade promise's success completion, which is exactly the window a caller interrupt or a plaintext-connection
  * close() can land in on a real race.
  */
private class FinishWithCertHookEngine(onCertSha: () => Unit) extends TlsEngine:
    val freed        = new JAtomicBoolean(false)
    private val once = new JAtomicBoolean(false)

    def handshakeStep()(using AllowUnsafe): Int                              = 1
    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int  = len
    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int       = 0
    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = len
    def hasBufferedPlaintext(using AllowUnsafe): Boolean                     = false
    def readBuffered()(using AllowUnsafe): Span[Byte]                        = Span.empty
    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]] =
        if once.compareAndSet(false, true) then onCertSha()
        Absent
    def shutdownStep()(using AllowUnsafe): Int = 0
    def free()(using AllowUnsafe): Unit        = freed.set(true)
end FinishWithCertHookEngine

/** A scripted [[TlsEngine]] that builds successfully and then throws `cause` out of `feedCiphertext`, which is how the upgrade's
  * staged-ciphertext feed fails after the engine already exists. `fed` records that the feed was genuinely reached (an empty staging would skip
  * it and leave the leaf proving nothing); `freed` records the engine release the failure path owes.
  */
private class ThrowOnFeedEngine(cause: Throwable) extends TlsEngine:
    val freed = new JAtomicBoolean(false)
    val fed   = new JAtomicBoolean(false)

    def handshakeStep()(using AllowUnsafe): Int = 0
    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        fed.set(true)
        throw cause
    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int       = 0
    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = len
    def hasBufferedPlaintext(using AllowUnsafe): Boolean                     = false
    def readBuffered()(using AllowUnsafe): Span[Byte]                        = Span.empty
    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]                   = Absent
    def shutdownStep()(using AllowUnsafe): Int                               = 0
    def free()(using AllowUnsafe): Unit                                      = freed.set(true)
end ThrowOnFeedEngine

/** Release discipline of an abandoned or failed STARTTLS upgrade in [[PosixTransport]]: every release of the detached fd must route through
  * the driver's close (`closeUnwiredHandle` -> `driver.closeHandle`), never a bare `PosixHandle.close`.
  *
  * On io_uring the distinction is memory safety, not hygiene: a failed or abandoned upgrade routinely leaves a kernel-owned recv SQE in
  * flight for this handle (the stale plaintext pump recv the upgrade window cannot cancel), and that SQE has already captured the read
  * buffer's address. A bare `PosixHandle.close` runs `freeResources` inline with zero guard holders (io_uring reads hold no handle-guard),
  * returning the off-heap buffer to the process-wide allocator while the kernel can still complete the old recv into it: a cross-connection
  * corruption with no driver-side evidence. `driver.closeHandle` defers the free until the handle's in-flight count drains to zero
  * (`registerDeferredClose`), which the two io_uring leaves below assert at both edges (open while the recv is kernel-owned, closed once its
  * CQE reaps). Both leaves pin or probe the reap carrier through the engine FIFO so the observation is ordered against the reap, never racing
  * it.
  *
  * The third leaf covers the success-side orphan of the same ownership model on any posix backend: a close() of the plaintext connection
  * landing inside `onFinished`'s body (after the handshake outcome gate is won, before the success completion) must not leave the freshly
  * started upgraded connection live and unreferenced; the success completion is checked and the orphan closed, mirroring `completeConnect`.
  */
class PosixTransportUpgradeReleaseTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.NetConfig.default

    private def sock = Ffi.load[SocketBindings]

    /** Whether the connection is holding unconsumed inbound bytes, which is what `detachForUpgrade` hands the upgrade as its staging. A closed
      * channel counts as no bytes: the caller polls this to reach a staged state, and a close means that state is unreachable.
      */
    private def hasBytes[A](ch: Channel.Unsafe[A])(using AllowUnsafe, Frame): Boolean =
        ch.empty().contains(false)

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    /** Allocate a real io_uring ring wrapped in a [[RecordingIoUringBindings]] spy, build a driver and a transport over it, run `body`, then
      * close the driver. Mirrors IoUringDriverTest.withRecordingDriver plus the transport layer.
      */
    private def withRecordingTransport[A](
        body: (PosixTransport, IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        withRecordingTransport(transportConfig, Ffi.load[SocketBindings])(body)

    /** As above, over a caller-supplied transport config and socket bindings (a recording decorator, so a leaf can observe or hook the
      * transport's own shutdown/close syscalls).
      */
    private def withRecordingTransport[A](
        config: kyo.net.NetConfig,
        transportSockets: SocketBindings,
        buildEngine: PosixTransport.TlsEngineFactory = PosixTransport.realEngineFactory
    )(
        body: (PosixTransport, IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(256, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("PosixTransportUpgradeReleaseTest", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        val transport = TestTransports.forTesting(driver, transportSockets, backendIsEpoll = false, buildEngine)
        Sync.ensure(Sync.defer(driver.close()))(body(transport, driver, recording))
    end withRecordingTransport

    "a failed or abandoned upgrade releases the fd through the driver's deferred close (io_uring)" - {

        "buildEngine failure: the read buffer stays open while the pump's recv SQE is kernel-owned, and closes once it reaps" in {
            PosixTestSockets.assumeUring()
            withRecordingTransport { (transport, driver, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    Sync.ensure(Sync.defer(discard(sock.close(accepted)))) {
                        val handle    = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                        val plaintext = transport.openWith(handle, driver, transportConfig.channelCapacity)
                        assert(plaintext.start(), "the plaintext connection must start")
                        // The ReadPump's first recv is now armed (or arming); wait for the SQE to be genuinely kernel-owned.
                        awaitCondition(5.seconds)(handle.recvInFlight).map { armed =>
                            assert(armed, "the pump's recv SQE never became kernel-owned (a hang, not the release hazard under test)")
                            val reapLatch = recording.awaitReap()
                            // Run the buildEngine-failure upgrade ON THE REAP CARRIER via an engine op, so its release orders on the same
                            // FIFO as the observation without ever parking a thread. The release path (closeUnwiredHandle's synchronous
                            // shutdown forces the still-kernel-owned pump recv to EOF, then driver.closeHandle enqueues the deferred close)
                            // runs on this carrier, and the reap loop can only reap that forced EOF in a LATER drainReady pass, never during
                            // this drainEngineOps pass. A probe op enqueued right after the release therefore runs strictly after it (buffer
                            // not yet freed, recv still kernel-owned) and strictly before the reap, mirroring the mid-handshake leaf below.
                            val upgradeFiber =
                                new java.util.concurrent.atomic.AtomicReference[Fiber.Unsafe[Connection, Abort[NetException]]]()
                            val probed = Promise.Unsafe.init[(Boolean, Boolean), Abort[Closed]]()
                            // A pinned-but-unavailable TLS provider makes buildEngine throw NetTlsProviderUnavailableException in the shared
                            // TlsProvider.selectFor, before any provider builds an engine, so the throw is deterministic on every platform. (A
                            // verifying no-SNI client is not a reliable buildEngine throw: the BoringSSL and OpenSSL providers bind an
                            // unmatchable identity and reject at handshake instead, and only the JDK floor throws at build time.)
                            val unavailableProvider = NetTlsConfig(tlsProvider = Present("nonexistent-tls-provider"))
                            driver.submitEngineOp { () =>
                                upgradeFiber.set(transport.upgradeToTls(plaintext, unavailableProvider, 16))
                                driver.submitEngineOp { () =>
                                    probed.completeDiscard(Result.succeed((handle.readBuffer.isClosed, reapLatch.done())))
                                }
                            }
                            probed.safe.get.flatMap { case (closedDuringWindow, reapedDuringWindow) =>
                                assert(
                                    !reapedDuringWindow,
                                    "the pump's recv CQE must not have reaped while the release's deferred close was still in flight"
                                )
                                assert(
                                    !closedDuringWindow,
                                    "read buffer must stay open while the recv SQE is in flight (a bare close here frees kernel-owned memory)"
                                )
                                Abort.run[NetException](upgradeFiber.get().safe.get).map { outcome =>
                                    // Defensive: the upgrade is expected to fail closed (unavailable provider); if a regression ever
                                    // let it succeed, close the unexpected upgraded connection rather than leak it.
                                    outcome.foreach(_.close())
                                    outcome match
                                        case Result.Failure(_: NetTlsProviderUnavailableException) => ()
                                        case other =>
                                            fail(s"an unavailable-provider upgrade must fail closed with a NetTlsException, got $other")
                                    end match
                                    // The release's own shutdown already forced the recv to EOF; once its CQE reaps, the deferred close
                                    // must run and free the buffer, exactly once, after the reap.
                                    reapLatch.safe.get.map { _ =>
                                        assert(
                                            handle.readBuffer.isClosed,
                                            "the deferred close must free the read buffer once the recv CQE is reaped"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "abandoned mid-handshake upgrade: releaseFailedUpgrade must not free the read buffer under the kernel-owned stale recv" in {
            PosixTestSockets.assumeUring()
            // A want-read engine parks the handshake on its first read; the peer sends nothing, so the only recv that
            // can ever be in flight is the stale pump recv armed below (kernel-owned, uncancellable).
            val engine = new ParkedWantReadEngine
            withRecordingTransport(transportConfig, Ffi.load[SocketBindings], buildEngine = (_, _, _) => engine) {
                (transport, driver, recording) =>
                    PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                        Sync.ensure(Sync.defer(discard(sock.close(accepted)))) {
                            val handle    = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                            val plaintext = transport.openWith(handle, driver, transportConfig.channelCapacity)
                            assert(plaintext.start(), "the plaintext connection must start")
                            awaitCondition(5.seconds)(handle.recvInFlight).map { armed =>
                                assert(armed, "the pump's recv SQE never became kernel-owned (a hang, not the release hazard under test)")
                                val upgrade = transport.upgradeToTls(plaintext, NetTlsConfig(trustAll = true), 16).safe
                                awaitCondition(5.seconds)(engine.stepCount.get() >= 1).map { stepped =>
                                    assert(stepped, "the upgrade handshake never reached its first step")
                                    val reapLatch = recording.awaitReap()
                                    val probed    = Promise.Unsafe.init[(Boolean, Boolean), Abort[Closed]]()
                                    // Abandon the upgrade through the production route, ON THE REAP CARRIER via an engine op, mirroring the
                                    // buildEngine-failure leaf above: close() of the plaintext connection routes to the upgrade's owner promise,
                                    // whose discharge runs releaseFailedUpgrade (its closeUnwiredHandle shutdown forces the still-kernel-owned stale
                                    // recv to EOF, then driver.closeHandle enqueues the deferred close) on THIS carrier. A probe op enqueued from
                                    // inside the same op sits behind that discharge in the same drainEngineOps pass, so it observes the state strictly
                                    // after the release ran and strictly before that pass's later drainReady reap of the shutdown-forced EOF. Running
                                    // close() from inside the engine op (rather than on the test carrier, then enqueuing the probe after) removes the
                                    // gap between the two enqueues that a parallel preemption could let the reap carrier drain and reap across.
                                    driver.submitEngineOp { () =>
                                        plaintext.close()
                                        driver.submitEngineOp { () =>
                                            probed.completeDiscard(Result.succeed((handle.readBuffer.isClosed, reapLatch.done())))
                                        }
                                    }
                                    probed.safe.get.flatMap { case (closedDuringWindow, reapedDuringWindow) =>
                                        assert(!reapedDuringWindow, "the stale recv CQE must not have reaped before the probe ran")
                                        assert(
                                            !closedDuringWindow,
                                            "read buffer must stay open while the stale upgrade recv is kernel-owned (a bare close here frees kernel-owned memory)"
                                        )
                                        Abort.run[NetException](upgrade.get).map { outcome =>
                                            // Defensive: the abandoned upgrade is expected to fail; if a regression ever let it
                                            // succeed, close the unexpected upgraded connection rather than leak it.
                                            outcome.foreach(_.close())
                                            outcome match
                                                case Result.Failure(e: NetConnectionClosedException) =>
                                                    assert(
                                                        e.operation == Operation.Close,
                                                        s"the abandoned upgrade must fail its close leaf, got ${e.operation}"
                                                    )
                                                case other =>
                                                    fail(s"the abandoned upgrade must fail NetConnectionClosedException, got $other")
                                            end match
                                            reapLatch.safe.get.map { _ =>
                                                assert(
                                                    handle.readBuffer.isClosed,
                                                    "the deferred close must free the read buffer once the stale recv CQE is reaped"
                                                )
                                                assert(engine.freed.get(), "the abandoned upgrade's engine must be freed")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }.map(_ => succeed)
        }
    }

    "a failed upgrade whose pump recv has already reaped (io_uring)" - {

        "buildEngine failure: the fd-close credit must be installed before the driver close can consume it" in {
            PosixTestSockets.assumeUring()
            // channelCapacity = 1 lets two peer sends fill the inbound channel and park the ReadPump on its put with NO recv re-armed:
            // the handle then has zero in-flight SQEs while the connection is still Established. In that state the release's driver
            // close takes registerDeferredClose's immediate closeNow branch, whose freeResources is the single, at-most-once consumer
            // of the fdCloseSink credit. If the release enables that consumer before installing the credit, the one consuming run reads
            // the sink Absent, the credit installed afterwards strands forever, and the fd is never closed: a permanent descriptor leak.
            val cfg = transportConfig.copy(channelCapacity = 1)
            val spy = RecordingSocketBindings(Ffi.load[SocketBindings])
            withRecordingTransport(cfg, spy) { (transport, driver, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    Sync.ensure(Sync.defer(discard(sock.close(accepted)))) {
                        val handle    = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                        val plaintext = transport.openWith(handle, driver, cfg.channelCapacity)
                        assert(plaintext.start(), "the plaintext connection must start")
                        awaitCondition(5.seconds)(handle.recvInFlight).map { armed =>
                            assert(armed, "the pump's first recv never became kernel-owned")
                            // First byte: recv 1 delivers it, the offer fills the capacity-1 channel, and the pump re-arms exactly once.
                            val c0  = recording.cqeSeenCount.get()
                            val one = Buffer.fromArray[Byte](Array[Byte](1))
                            assert(sock.sendNow(accepted, one, 1L, 0).value == 1L, "peer send 1 must succeed")
                            awaitCondition(5.seconds)(recording.cqeSeenCount.get() > c0 && handle.recvInFlight).map { rearmed =>
                                assert(rearmed, "the pump must deliver the first byte and re-arm its recv")
                                // Second byte: recv 2 delivers it, the pump's put parks on the full channel, and no recv is re-armed.
                                // Once recv 2's CQE has reaped with nothing in flight, the state is stable: the parked put can never
                                // succeed (capacity 1, held by the first chunk, no consumer), so no re-arm can ever follow.
                                val two = Buffer.fromArray[Byte](Array[Byte](2))
                                assert(sock.sendNow(accepted, two, 1L, 0).value == 1L, "peer send 2 must succeed")
                                awaitCondition(5.seconds)(
                                    recording.cqeSeenCount.get() > c0 + 1 && !driver.hasInFlightRead(handle) && !handle.recvInFlight
                                ).map { drained =>
                                    one.close()
                                    two.close()
                                    assert(drained, "the pump must park on the full channel with no recv in flight")
                                    // Order observation (no carrier is ever held): the release's shutdown(SHUT_RDWR) sits immediately before
                                    // it installs the fdCloseSink credit, and IoUringDriver.closeHandle replaces handle.engineFreeSink
                                    // synchronously when the driver close is requested. A client shutdown seen with engineFreeSink already
                                    // replaced therefore proves the driver close (which enables the credit's single freeResources consumer)
                                    // was requested before the credit was installed, so that consumer can read the sink Absent and the credit
                                    // strands forever, leaking the fd. With the credit installed before the driver close, engineFreeSink is
                                    // still untouched at shutdown time and the flag stays clear. Pure observation, so the reproducer never parks
                                    // a carrier: the flag is deterministically set if the credit is installed late, regardless of how the reap carrier races.
                                    val sinkBefore          = handle.engineFreeSink
                                    val creditInstalledLate = new JAtomicBoolean(false)
                                    spy.onShutdown = fd =>
                                        if fd == client && (handle.engineFreeSink ne sinkBefore) then creditInstalledLate.set(true)
                                    // A pinned-but-unavailable TLS provider makes buildEngine throw NetTlsProviderUnavailableException in
                                    // the shared TlsProvider.selectFor, landing in upgradeRole's engine-build catch on THIS carrier, with zero
                                    // in-flight SQEs for the handle. (A verifying no-SNI client is not a reliable buildEngine throw: the
                                    // BoringSSL and OpenSSL providers bind an unmatchable identity and reject at handshake instead.)
                                    val unavailableProvider = NetTlsConfig(tlsProvider = Present("nonexistent-tls-provider"))
                                    Abort.run[NetException](transport.upgradeToTls(plaintext, unavailableProvider, 16).safe.get).map {
                                        outcome =>
                                            // Defensive: the upgrade is expected to fail closed (unavailable provider); if a
                                            // regression ever let it succeed, close the unexpected upgraded connection rather than leak it.
                                            outcome.foreach(_.close())
                                            outcome match
                                                case Result.Failure(_: NetTlsProviderUnavailableException) => ()
                                                case other =>
                                                    fail(
                                                        s"an unavailable-provider upgrade must fail closed with a NetTlsException, got $other"
                                                    )
                                            end match
                                            // The credit install must precede the driver close (else the single consumer strands it); observed
                                            // above with no carrier held, so a credit-late ordering fails here deterministically, not via a leaked fd.
                                            assert(
                                                !creditInstalledLate.get(),
                                                "the fd-close credit must be installed before the driver close is requested (else its single " +
                                                    "consumer reads the sink Absent and the credit strands, leaking the fd)"
                                            )
                                            awaitCondition(5.seconds)(spy.closeCounts.getOrDefault(client, 0) >= 1).map { credited =>
                                                assert(
                                                    credited,
                                                    "the release stranded its fd-close credit: the deferred close(fd) never ran (a permanent fd leak)"
                                                )
                                                assert(
                                                    spy.closeCounts.getOrDefault(client, 0) == 1,
                                                    s"the abandoned fd must be closed exactly once, counts=${spy.closeCounts}"
                                                )
                                                assert(handle.readBuffer.isClosed, "the release must have freed the handle's resources")
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

    "an engine build that throws something other than a NetTlsException" - {

        "must settle the upgrade as a panic and release the fd, rather than strand both" in {
            PosixTestSockets.assumePoller()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            // Not a NetTlsException, so the buildEngine arm inside the upgrade does not handle it: this is the throw the containment at the
            // detach-handover boundary has to catch. The real shape is a TLS provider that lets a raw failure escape its build (the JDK
            // floor's CertificateException for a file that exists but holds no certificate); an identity-checked RuntimeException pins the
            // mechanism without depending on which provider the host stages.
            val boom = new RuntimeException("engine build failed outside the NetTlsException taxonomy")
            val transport =
                TestTransports.forTesting(
                    driver,
                    Ffi.load[SocketBindings],
                    backendIsEpoll = false,
                    buildEngine = (_, _, _) => throw boom
                )
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    Sync.ensure(Sync.defer(discard(sock.close(accepted)))) {
                        val handle    = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                        val plaintext = transport.openWith(handle, driver, transportConfig.channelCapacity)
                        assert(plaintext.start(), "the plaintext connection must start")
                        // The post-detach body runs inside a completion callback, and completion callbacks are run under a catch-all that
                        // logs rather than propagates. An unhandled throw there settles nothing, so this get is what fails when the
                        // containment is missing: it parks forever on a promise no path can complete, and the leaf's own cap reports it.
                        Abort.run[NetException](transport.upgradeToTls(plaintext, NetTlsConfig(trustAll = true), 16).safe.get).map {
                            outcome =>
                                // Defensive, as in the leaves above: a regression that let this upgrade succeed must not leak its connection.
                                outcome.foreach(_.close())
                                outcome match
                                    case Result.Panic(t) =>
                                        // Identity, not just the type: the caller has to receive the original throwable, because that is what
                                        // classifies the failure upstream (kyo-sql's TlsUpgrade reads the panic's cause to report a connect
                                        // failure). A substituted or wrapped throwable would pass a type check and still lose the diagnosis.
                                        assert(t eq boom, s"the caller must receive the engine build's own throwable, got $t")
                                    case other =>
                                        fail(
                                            s"an engine build throwing outside the NetTlsException taxonomy must panic the upgrade, got $other"
                                        )
                                end match
                                // The detach already handed the fd to the upgrade, and no engine exists to own it, so the escape path is the
                                // only thing that can release it: the plaintext connection's own close cannot take an Upgrading fd and this
                                // transport is never swept. Left open, the fd sits until the peer FINs it into CLOSE_WAIT.
                                awaitCondition(10.seconds)(handle.readBuffer.isClosed).map { released =>
                                    assert(released, "the escape path must release the detached fd it was left holding")
                                }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "must free the ENGINE too when the throw lands after the engine was built" in {
            PosixTestSockets.assumePoller()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            // The window between the engine build and the upgrade's owner hook has two throw sites of its own, `feedStaged` and
            // `feedCoalescedHandshake`, and by then the release owes the engine as well as the fd. The leaf above cannot reach that: it throws
            // during the build, when no engine exists yet. Reaching it needs staged ciphertext, since `feedStaged` only enters the engine for
            // spans that carry bytes, which is why the peer writes first and the upgrade waits for those bytes to be sitting unconsumed.
            val boom   = new RuntimeException("staged-ciphertext feed failed outside the NetTlsException taxonomy")
            val engine = new ThrowOnFeedEngine(boom)
            val transport =
                TestTransports.forTesting(driver, Ffi.load[SocketBindings], backendIsEpoll = false, buildEngine = (_, _, _) => engine)
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    Sync.ensure(Sync.defer(discard(sock.close(accepted)))) {
                        val handle    = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                        val plaintext = transport.openWith(handle, driver, transportConfig.channelCapacity)
                        assert(plaintext.start(), "the plaintext connection must start")
                        val payload = "staged-ciphertext".getBytes("UTF-8")
                        assert(
                            sock.sendNow(
                                accepted,
                                Buffer.fromArray[Byte](payload),
                                payload.length.toLong,
                                0
                            ).value == payload.length.toLong,
                            "the peer write that produces the staging must land in full"
                        )
                        // Barrier, not a sleep: the bytes must be off the socket and sitting unconsumed in the inbound channel, because that
                        // is exactly what `detachForUpgrade` hands back as `staged`. Upgrading before they land would feed an empty chunk and
                        // silently exercise the leaf above instead of this one.
                        awaitCondition(
                            5.seconds
                        )(hasBytes(plaintext.inbound)).map {
                            staged =>
                                assert(
                                    staged,
                                    "the peer's bytes never reached the inbound channel, so nothing would be staged for the engine"
                                )
                                Abort.run[NetException](transport.upgradeToTls(plaintext, NetTlsConfig(trustAll = true), 16).safe.get).map {
                                    outcome =>
                                        outcome.foreach(_.close())
                                        outcome match
                                            case Result.Panic(t) =>
                                                assert(t eq boom, s"the caller must receive the feed's own throwable, got $t")
                                            case other =>
                                                fail(
                                                    s"a staged-ciphertext feed throwing outside the NetTlsException taxonomy must panic the upgrade, got $other"
                                                )
                                        end match
                                        assert(
                                            engine.fed.get(),
                                            "the leaf did not reach `feedStaged`, so it proves nothing about the post-build window"
                                        )
                                        // Both obligations, because by this point the upgrade owns both and the fd-only release the pre-build
                                        // path uses would strand the engine's native memory with no other owner able to reach it.
                                        awaitCondition(10.seconds)(handle.readBuffer.isClosed && engine.freed.get()).map { released =>
                                            assert(
                                                released,
                                                s"fd released=${handle.readBuffer.isClosed} engine freed=${engine.freed.get()}"
                                            )
                                        }
                                }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

    "a close() landing inside onFinished's completion window" - {

        "must close the orphaned upgraded connection instead of leaving it live and unreferenced" in {
            PosixTestSockets.assumePoller()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            // The engine must reference the plaintext connection this transport creates (its certSha256 hook closes it), so it is built after
            // the transport and published into a slot the injected factory reads at upgrade time; the slot lives entirely in the test tree.
            val engineSlot = new AtomicReference[TlsEngine]()
            val transport =
                TestTransports.forTesting(
                    driver,
                    Ffi.load[SocketBindings],
                    backendIsEpoll = false,
                    buildEngine = (_, _, _) => engineSlot.get()
                )
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    Sync.ensure(Sync.defer(discard(sock.close(accepted)))) {
                        val handle    = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                        val plaintext = transport.openWith(handle, driver, transportConfig.channelCapacity)
                        assert(plaintext.start(), "the plaintext connection must start")
                        // The engine completes its handshake immediately, and its certSha256 (called by onFinished's wireUpgraded, after
                        // the outcome gate is won and before the success completion) closes the plaintext connection: the close routes to
                        // the upgrade's owner promise, which settles as a failure while onFinished is still mid-body. The discharge hook
                        // loses the already-won outcome gate and releases nothing, so the success completion that follows is the only
                        // place left that can see the settled promise and close the orphan.
                        val engine = new FinishWithCertHookEngine(onCertSha = () => plaintext.close())
                        engineSlot.set(engine)
                        Abort.run[NetException](transport.upgradeToTls(plaintext, NetTlsConfig(trustAll = true), 16).safe.get).map {
                            outcome =>
                                // Defensive: the upgrade is expected to settle as NetConnectionClosedException; if a regression ever
                                // let it succeed, close the unexpected orphaned connection rather than leak it.
                                outcome.foreach(_.close())
                                outcome match
                                    case Result.Failure(e: NetConnectionClosedException) =>
                                        assert(
                                            e.operation == Operation.Close,
                                            s"the abandoned upgrade must fail its close leaf, got ${e.operation}"
                                        )
                                    case other =>
                                        fail(
                                            s"a close() landing mid-onFinished must settle the upgrade as NetConnectionClosedException, got $other"
                                        )
                                end match
                                // The caller was told the upgrade was cancelled, so nobody references the upgraded connection: it must be
                                // torn down (fd and buffers released), not left Established with running pumps on the never-swept
                                // process-shared transport. The peer end stays open, so nothing else can ever tear it down.
                                awaitCondition(10.seconds)(handle.readBuffer.isClosed).map { released =>
                                    assert(
                                        released,
                                        "the orphaned upgraded connection must be closed when the success completion finds the promise already settled"
                                    )
                                }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end PosixTransportUpgradeReleaseTest
