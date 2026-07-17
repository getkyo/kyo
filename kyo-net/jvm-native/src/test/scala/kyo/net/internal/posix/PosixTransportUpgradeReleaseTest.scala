package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetConnectionClosedException
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.NetTlsException
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsTestCert

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

    private val transportConfig = kyo.net.TransportConfig.default

    private def sock = Ffi.load[SocketBindings]

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
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(256, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("PosixTransportUpgradeReleaseTest", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        Sync.ensure(Sync.defer(driver.close()))(body(transport, driver, recording))
    end withRecordingTransport

    "a failed or abandoned upgrade releases the fd through the driver's deferred close (io_uring)" - {

        "buildEngine failure: the read buffer stays open while the pump's recv SQE is kernel-owned, and closes once it reaps" in {
            PosixTestSockets.assumeUring()
            withRecordingTransport { (transport, driver, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    Sync.ensure(Sync.defer(discard(sock.close(accepted)))) {
                        val handle    = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                        val plaintext = transport.openWith(handle, driver)
                        assert(plaintext.start(), "the plaintext connection must start")
                        // The ReadPump's first recv is now armed (or arming); wait for the SQE to be genuinely kernel-owned.
                        awaitCondition(5.seconds)(handle.recvInFlight).map { armed =>
                            assert(armed, "the pump's recv SQE never became kernel-owned (a hang, not the release hazard under test)")
                            // Pin the reap carrier so nothing can reap between the upgrade's failure and the observation below: the
                            // release path forces the pending recv to EOF via shutdown, so an unpinned observation would race that
                            // reap and could only ever see the post-discharge state.
                            val gate  = new java.util.concurrent.CountDownLatch(1)
                            val pinIn = Promise.Unsafe.init[Unit, Abort[Closed]]()
                            driver.submitEngineOp { () =>
                                pinIn.completeDiscard(Result.succeed(()))
                                gate.await()
                            }
                            val reapLatch = recording.awaitReap()
                            pinIn.safe.get.flatMap { _ =>
                                // A verifying client with no reference identity: buildEngine fails closed (RFC 9525 6.1), landing in
                                // upgradeRole's engine-build catch while the pump's recv is still kernel-owned.
                                val verifyingNoSni =
                                    NetTlsConfig(trustAll = false, caCertPath = Present(TlsTestCert.certPath), sniHostname = Absent)
                                Abort.run[NetException](transport.upgradeToTls(plaintext, verifyingNoSni, 16).safe.get).map { outcome =>
                                    outcome match
                                        case Result.Failure(_: NetTlsException) => ()
                                        case other =>
                                            fail(s"a verifying no-SNI upgrade must fail closed with a NetTlsException, got $other")
                                    end match
                                    // Snapshot with the reap carrier pinned, then release BEFORE asserting so a failed assertion never
                                    // leaves the carrier parked.
                                    val closedDuringWindow = handle.readBuffer.isClosed
                                    val reapedDuringWindow = reapLatch.done()
                                    gate.countDown()
                                    assert(
                                        !reapedDuringWindow,
                                        "the pump's recv CQE must not have reaped while the reap carrier was pinned"
                                    )
                                    assert(
                                        !closedDuringWindow,
                                        "read buffer must stay open while the recv SQE is in flight (a bare close here frees kernel-owned memory)"
                                    )
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
            withRecordingTransport { (transport, driver, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    Sync.ensure(Sync.defer(discard(sock.close(accepted)))) {
                        val handle    = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                        val plaintext = transport.openWith(handle, driver)
                        assert(plaintext.start(), "the plaintext connection must start")
                        awaitCondition(5.seconds)(handle.recvInFlight).map { armed =>
                            assert(armed, "the pump's recv SQE never became kernel-owned (a hang, not the release hazard under test)")
                            // A want-read engine parks the handshake on its first read; the peer sends nothing, so the only recv that
                            // can ever be in flight is the stale pump recv armed above (kernel-owned, uncancellable).
                            val engine = new ParkedWantReadEngine
                            transport.testEngineFactory = Present { (_, _, _) => engine }
                            val upgrade = transport.upgradeToTls(plaintext, NetTlsConfig(trustAll = true), 16).safe
                            awaitCondition(5.seconds)(engine.stepCount.get() >= 1).map { stepped =>
                                assert(stepped, "the upgrade handshake never reached its first step")
                                val reapLatch = recording.awaitReap()
                                // Abandon the upgrade through the production route: close() of the plaintext connection routes to the
                                // upgrade's owner promise, whose discharge runs releaseFailedUpgrade on the engine FIFO. The discharge op
                                // is enqueued synchronously by the close below, so a probe op enqueued right after it observes the state
                                // strictly after the release ran and strictly before that drain pass's reap of the shutdown-forced EOF.
                                plaintext.close()
                                val probed = Promise.Unsafe.init[(Boolean, Boolean), Abort[Closed]]()
                                driver.submitEngineOp { () =>
                                    probed.completeDiscard(Result.succeed((handle.readBuffer.isClosed, reapLatch.done())))
                                }
                                probed.safe.get.flatMap { case (closedDuringWindow, reapedDuringWindow) =>
                                    assert(!reapedDuringWindow, "the stale recv CQE must not have reaped before the probe ran")
                                    assert(
                                        !closedDuringWindow,
                                        "read buffer must stay open while the stale upgrade recv is kernel-owned (a bare close here frees kernel-owned memory)"
                                    )
                                    Abort.run[NetException](upgrade.get).map { outcome =>
                                        outcome match
                                            case Result.Failure(e: NetConnectionClosedException) =>
                                                assert(
                                                    e.operation == "close",
                                                    s"the abandoned upgrade must fail its close leaf, got ${e.operation}"
                                                )
                                            case other => fail(s"the abandoned upgrade must fail NetConnectionClosedException, got $other")
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

    "a close() landing inside onFinished's completion window" - {

        "must close the orphaned upgraded connection instead of leaving it live and unreferenced" in {
            PosixTestSockets.assumePoller()
            val driver = PollerIoDriver.init(transportConfig)
            discard(driver.start())
            val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    Sync.ensure(Sync.defer(discard(sock.close(accepted)))) {
                        val handle    = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                        val plaintext = transport.openWith(handle, driver)
                        assert(plaintext.start(), "the plaintext connection must start")
                        // The engine completes its handshake immediately, and its certSha256 (called by onFinished's wireUpgraded, after
                        // the outcome gate is won and before the success completion) closes the plaintext connection: the close routes to
                        // the upgrade's owner promise, which settles as a failure while onFinished is still mid-body. The discharge hook
                        // loses the already-won outcome gate and releases nothing, so the success completion that follows is the only
                        // place left that can see the settled promise and close the orphan.
                        val engine = new FinishWithCertHookEngine(onCertSha = () => plaintext.close())
                        transport.testEngineFactory = Present { (_, _, _) => engine }
                        Abort.run[NetException](transport.upgradeToTls(plaintext, NetTlsConfig(trustAll = true), 16).safe.get).map {
                            outcome =>
                                outcome match
                                    case Result.Failure(e: NetConnectionClosedException) =>
                                        assert(
                                            e.operation == "close",
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
