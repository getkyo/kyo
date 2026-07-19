package kyo.net.internal.posix

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsEngine

/** A recording [[TlsEngine]] fake (never wraps or delegates to a real engine) that records every [[feedCiphertext]] payload and completes the
  * handshake (`handshakeStep` returns `1`) once fed twice, `0` (want-read) until then: this test's upgrade always has exactly two chunks to
  * deliver (the signal `S`, staged and fed via `feedStaged`, and the coalesced flight `F`, fed by whichever of `feedCoalescedHandshake` /
  * `driveUpgradeRead`'s Carryover-drain wins the claim), so completing at exactly two feeds lets the handshake finish deterministically
  * without needing any further real ciphertext from the peer -- while still surfacing a THIRD feed (the bug: `onFinished`'s own
  * post-FINISHED slot drain re-feeding a Carryover nobody claimed first) as an extra recorded payload the test can assert against.
  */
private class RecordingFeedEngine extends TlsEngine:
    val feeds = new ConcurrentLinkedQueue[Array[Byte]]()

    def handshakeStep()(using AllowUnsafe): Int = if feeds.size() >= 2 then 1 else 0

    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        val arr = new Array[Byte](len)
        var i   = 0
        while i < len do
            arr(i) = buf.get(i)
            i += 1
        discard(feeds.add(arr))
        len
    end feedCiphertext

    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int       = 0
    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = 0
    def hasBufferedPlaintext(using AllowUnsafe): Boolean                     = false
    def readBuffered()(using AllowUnsafe): Span[Byte]                        = Span.empty
    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]                   = Absent
    def shutdownStep()(using AllowUnsafe): Int                               = 0
    def free()(using AllowUnsafe): Unit                                      = ()
end RecordingFeedEngine

/** Reproduce-first regression for the STARTTLS upgrade-flight double-feed: the peer's first handshake flight, arriving in its own read right
  * behind the upgrade signal, was fed into the engine TWICE when the plaintext pump's channel-offer for it failed (the offer parks under
  * backpressure, and `detachForUpgrade`'s `inbound.close()` fails that parked put). `PosixTransport.upgradeRole`'s `feedCoalescedHandshake`
  * feeds the flight from `PosixHandle.lastPlaintextRead` unconditionally, and the driver's `onInboundClosedDuringRead` salvage stages the
  * SAME array into `upgradeHandoff` for `driveUpgradeRead` to feed again -- both paths alias one slot with no mutual guard, so the engine
  * received a duplicate handshake record and failed the handshake with an `EngineError` (the shape that would fail `TransportStartTlsTest`'s
  * "repeated STARTTLS upgrades" leaf on both backends). `lastPlaintextRead` is a one-shot CAS claim: whichever
  * side wins delivers the flight, the loser skips.
  *
  * The scenario drives a REAL loopback pair with a scripted [[RecordingFeedEngine]] (injected through [[TestTransports.forTesting]]'s
  * `buildEngine`) so the
  * production race is exercised end to end, with `channelCapacity = 1` forcing the second read (the flight) to park behind the first (the
  * signal, which fills the capacity-1 channel and is never consumed): `S` lands and fills the channel, `F` (a well-formed TLS handshake
  * record) is read off the socket next and parks the plaintext pump's put, so `upgradeToTls`'s `detachForUpgrade()` races that parked put
  * exactly as the production upgrade path does. No sleeps: every wait polls an observable driver/handle state.
  */
class PosixTransportUpgradeDoubleFeedTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private val signal = Array[Byte]('U')

    // A well-formed 10-byte TLS handshake record: 0x16 (handshake content type), 0x03 0x01 (legacy record version), 0x00 0x05 (5-byte
    // fragment length), + 5 arbitrary body bytes. tlsRecordStart only validates the 5-byte header, so the body content is irrelevant.
    private val flight = Array[Byte](0x16, 0x03, 0x01, 0x00, 0x05, 1, 2, 3, 4, 5)

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    private def countFlightFeeds(engine: RecordingFeedEngine): Int =
        import scala.jdk.CollectionConverters.*
        engine.feeds.asScala.count(_.sameElements(flight))

    "PosixTransport upgrade-flight double-feed" - {

        "poller: a channel-offer failure during detachForUpgrade must not deliver the coalesced flight to the engine twice" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            val engine   = new RecordingFeedEngine
            val transport =
                TestTransports.forTesting(
                    kyo.net.TransportConfig.default.copy(channelCapacity = 1),
                    driver,
                    spy,
                    backendIsEpoll = false,
                    buildEngine = (_, _, _) => engine
                )
            discard(driver.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                // The peer end is a raw socket never owned by the transport (no InternalConnection wraps it): closed unconditionally here.
                Sync.ensure(Sync.defer(discard(sock.close(peerFd)))) {
                    val handle    = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val plaintext = transport.openWith(handle, driver)
                    plaintext.start()
                    assert(sock.sendNow(peerFd, Buffer.fromArray[Byte](signal), signal.length.toLong, 0).value == signal.length.toLong)
                    awaitCondition(5.seconds)(plaintext.inbound.size().getOrElse(-1) == 1).map { landed =>
                        assert(landed, "the signal byte never landed in the inbound channel (a hang, not the race under test)")
                        assert(sock.sendNow(peerFd, Buffer.fromArray[Byte](flight), flight.length.toLong, 0).value == flight.length.toLong)
                        // The channel is already full (capacity 1, the signal unconsumed) and nothing drains it, so the flight's read is
                        // guaranteed to have been dispatched (lastPlaintextRead set) well before its offer can ever succeed.
                        awaitCondition(5.seconds)(handle.lastPlaintextRead.get() match
                            case Present(a) => a.sameElements(flight)
                            case Absent     => false).map { read =>
                            assert(read, "the flight was never read off the socket (a hang, not the race under test)")
                            Abort.run[Closed](transport.upgradeToTls(
                                plaintext,
                                NetTlsConfig(trustAll = true),
                                1
                            ).safe.get).andThen {
                                // The handshake completes after exactly two feeds (the signal, then the flight), which is what lets
                                // .safe.get above return; give the salvage's (possibly asynchronous) delivery a moment to land in case the
                                // bug's extra feed arrives AFTER completion (onFinished's post-FINISHED drain), then assert on the
                                // flight's total delivery count.
                                awaitCondition(2.seconds)(countFlightFeeds(engine) >= 1).andThen {
                                    assert(
                                        countFlightFeeds(engine) == 1,
                                        s"the coalesced flight must reach the engine EXACTLY ONCE, was fed ${countFlightFeeds(engine)} times"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        "io_uring: a channel-offer failure during detachForUpgrade must not deliver the coalesced flight to the engine twice" in {
            PosixTestSockets.assumeUring()
            val depth = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
            val uring = Ffi.load[IoUringBindings]
            val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
            val rc    = uring.io_uring_queue_init(depth, ring, 0)
            if rc != 0 then
                ring.close()
                throw Closed("PosixTransportUpgradeDoubleFeedTest", summon[Frame], s"queue_init failed: rc=$rc")
            val driver = TestDrivers.forBindings(uring, ring)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                val sockets = Ffi.load[SocketBindings]
                val engine  = new RecordingFeedEngine
                val transport = TestTransports.forTesting(
                    kyo.net.TransportConfig.default.copy(channelCapacity = 1),
                    driver,
                    sockets,
                    backendIsEpoll = false,
                    buildEngine = (_, _, _) => engine
                )
                PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                    Sync.ensure(Sync.defer(discard(sockets.close(peerFd)))) {
                        val handle    = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                        val plaintext = transport.openWith(handle, driver)
                        plaintext.start()
                        assert(sockets.sendNow(
                            peerFd,
                            Buffer.fromArray[Byte](signal),
                            signal.length.toLong,
                            0
                        ).value == signal.length.toLong)
                        awaitCondition(5.seconds)(plaintext.inbound.size().getOrElse(-1) == 1).map { landed =>
                            assert(landed, "the signal byte never landed in the inbound channel (a hang, not the race under test)")
                            assert(sockets.sendNow(
                                peerFd,
                                Buffer.fromArray[Byte](flight),
                                flight.length.toLong,
                                0
                            ).value == flight.length.toLong)
                            // io_uring's own recv is kernel-owned and asynchronous: hasInFlightRead dropping to false is the reap-carrier
                            // signal that the flight's CQE reaped and its put parked (mirrors IoUringUpgradeHandoffDropTest exactly).
                            awaitCondition(5.seconds)(!driver.hasInFlightRead(handle)).map { parked =>
                                assert(parked, "the flight's recv never reaped / its put never parked (a hang, not the race under test)")
                                Abort.run[Closed](transport.upgradeToTls(
                                    plaintext,
                                    NetTlsConfig(trustAll = true),
                                    1
                                ).safe.get).andThen {
                                    awaitCondition(2.seconds)(countFlightFeeds(engine) >= 1).andThen {
                                        assert(
                                            countFlightFeeds(engine) == 1,
                                            s"the coalesced flight must reach the engine EXACTLY ONCE, was fed ${countFlightFeeds(engine)} times"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "PosixHandle.lastPlaintextRead claim contract (drives both race orders directly against the poller's onInboundClosedDuringRead)" - {

        "salvage claims first: the slot is left Absent, so a racing feedCoalescedHandshake would see it already claimed" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                Sync.ensure(Sync.defer { discard(sock.close(client)); discard(sock.close(accepted)) }) {
                    val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.driver = driver
                    handle.upgrading = true
                    handle.lastPlaintextRead.set(Present(flight))
                    driver.onInboundClosedDuringRead(handle, Span.fromUnsafe(flight))
                    assert(
                        handle.lastPlaintextRead.get() == Absent,
                        "the salvage must claim (CAS to Absent) the slot it delivers, so a racing feedCoalescedHandshake sees it already gone"
                    )
                    handle.upgradeHandoff.get() match
                        case c: PosixHandle.UpgradeHandoff.Carryover =>
                            assert(c.bytes.toSeq == flight.toSeq, s"the salvage must stage the exact flight bytes, got ${c.bytes.toSeq}")
                        case other => fail(s"the salvage must stage a Carryover when it wins the claim, got $other")
                    end match
                }
            }
        }

        "feedCoalescedHandshake claims first: the salvage sees the slot already Absent and discards the bytes without staging them" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                Sync.ensure(Sync.defer { discard(sock.close(client)); discard(sock.close(accepted)) }) {
                    val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.driver = driver
                    handle.upgrading = true
                    handle.lastPlaintextRead.set(Present(flight))
                    // Mirror feedCoalescedHandshake's own winning CAS exactly (the private method itself is not reachable from this test,
                    // but the atomic operation IS the contract under test): claim the slot before the salvage ever sees it.
                    assert(handle.lastPlaintextRead.compareAndSet(Present(flight), Absent))
                    driver.onInboundClosedDuringRead(handle, Span.fromUnsafe(flight))
                    assert(
                        handle.upgradeHandoff.get() == PosixHandle.UpgradeHandoff.Idle,
                        s"a salvage racing an already-claimed slot must discard the bytes (not stage them), got ${handle.upgradeHandoff.get()}"
                    )
                }
            }
        }
    }

end PosixTransportUpgradeDoubleFeedTest
