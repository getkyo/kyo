package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.WriteResult

/** Per-connection write-backpressure tail must stay BOUNDED against a peer that never reads (CWE-400 slow-read DoS).
  *
  * The async-write paths ([[PollerIoDriver.writeTls]], [[IoUringDriver.writeTls]], [[IoUringDriver.writeRaw]]) enqueue the encrypt/append-then-flush
  * on the engine FIFO worker and return [[WriteResult.Done]] immediately, decoupling the write from the actual send. When the peer never reads, the
  * kernel send buffer fills, the flush short-sends, and the unsent bytes accumulate in the handle's pending tail ([[PosixHandle.pendingCipher]] for
  * TLS, [[PosixHandle.rawPending]] for io_uring raw). A [[kyo.net.internal.transport.WritePump]] sees Done and pulls the next span, so without a
  * bound the tail grows once per write toward OOM: the outbound-channel + WritePump backpressure cannot bound it, because `write` never reports
  * Partial on these paths (it returns Done before the bytes ever reach the wire).
  *
  * Each leaf drives a connection where the PEER NEVER READS (no drain) while a WritePump-shaped loop issues writes back-to-back. The loop mirrors
  * [[kyo.net.internal.transport.WritePump]]'s issue path: on Done it issues the next write; on Partial it STOPS, because a Partial is exactly the
  * bound's signal (the WritePump would park there), so the loop has proven the tail is held without growing further. The invariant: the driver reports
  * Partial once the unsent tail reaches [[PosixHandle.WriteTailHighWater]], and the observed peak stays within the mark plus ONE in-flight write (the
  * write that crosses the mark is the one that trips Partial, so the peak is at most the mark plus one payload).
  *
  * Determinism: the peer is held by NEVER calling any recv on the peer fd (a real, deterministic no-read; no sleep, no timer-as-sync). The loop
  * terminates deterministically in BOTH directions with no park and no timeout: each `write` resolves synchronously, and against the never-reading
  * peer the tail can only grow, so a bounded driver returns Partial within a handful of writes (the loop stops at the first Partial) while an
  * unbounded driver returns Done for every write and the loop runs the fixed `writes` count to completion, exposing the grown peak. The tail size is
  * sampled after a `fifoBarrier` so every enqueued append has landed before it is read.
  *
  * Gates: [[PosixTestSockets.assumePoller]] / [[PosixTestSockets.assumeUring]] (real loopback pair for real EAGAIN) and
  * [[TlsRealEngines.assumeTlsReady]] (a real BoringSSL/OpenSSL engine for the TLS leaves). JS uses the synchronous `sendNow` binding rather than the
  * async-tail paths these leaves target, so every leaf gates on isJS.
  */
class PosixHandleWriteTailBoundTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Per-write payload sized so a handful of writes overflows the shrunk send buffer and, without a bound, would overflow the tail high-water mark
      * many times over. 256 KB keeps each write well under the mark so the BOUND is what stops growth, not a single oversized write.
      */
    private val perWrite = 256 * 1024

    /** Upper bound on the writes the loop attempts. With the tail bound, the loop stops at the first Partial after only a handful of writes cross the
      * high-water mark; without the bound, the tail grows by `perWrite` on every one of these and `writes * perWrite` (~24 MB) far exceeds the mark, so
      * an unbounded tail is unmistakable in the peak the loop returns after running all of them.
      */
    private val writes = 96

    /** Complete the in-memory handshake for `client`/`server` ON the driver's engine FIFO worker so the client session is created, handshaked, and
      * later written on one carrier. Returns a promise that completes once the handshake has run.
      */
    private def handshakeOnDriver(driver: IoDriver[PosixHandle], client: TlsEngine, server: TlsEngine)(using
        AllowUnsafe
    ): Promise.Unsafe[Boolean, Any] =
        val done = Promise.Unsafe.init[Boolean, Any]()
        driver.submitEngineOp(() => done.completeDiscard(Result.succeed(TlsEngineLoopback.handshake(client, server))))
        done
    end handshakeOnDriver

    /** Submit a marker engine op and return a promise that completes when the FIFO worker runs it. Awaiting it proves every engine op submitted
      * before it (the appends/flushes of every issued write) has run to completion: a deterministic, sleep-free settle point that lets the test
      * observe the tail state after all pending appends have landed.
      */
    private def fifoBarrier(driver: IoDriver[PosixHandle])(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** Run a WritePump-shaped issue loop against a never-reading peer and return `(boundTripped, peak)`: whether the driver reported `Partial`
      * (the high-water bound kicked in) and the maximum unsent tail observed across every `write`.
      *
      * Mirrors [[kyo.net.internal.transport.WritePump]]'s issue path WITHOUT parking: on Done it issues the next write, on Partial it STOPS (a Partial
      * is the bound's signal: the WritePump would park here, so this loop has proven the tail is held). The peer never reads, so once the tail crosses
      * the high-water mark the very next `write` returns Partial and the loop terminates deterministically: there is no park, no sleep, and no
      * timeout-as-sync. An unbounded driver returns Done for every write, so the loop runs to `writes` with the tail grown past the cap; the returned
      * peak then exposes the unbounded growth. This is fully deterministic in both directions: a bounded driver stops at the first Partial, an
      * unbounded driver runs the fixed `writes` count to completion.
      *
      * The peak is sampled AFTER a `fifoBarrier` so every enqueued append has landed before the unsent size is read (the append runs on the FIFO
      * worker; the synchronous `write` returns before it).
      */
    private def issueUntilBoundedOrGrown(driver: IoDriver[PosixHandle], handle: PosixHandle)(using
        Frame
    ): (Boolean, Int) < (Abort[Closed] & Async) =
        def payload(): Span[Byte] = Span.fromUnsafe(Array.fill[Byte](perWrite)(7.toByte))
        def loop(k: Int, peak: Int): (Boolean, Int) < (Abort[Closed] & Async) =
            if k >= writes then (false, peak)
            else
                Sync.defer(driver.write(handle, payload(), 0)).map { result =>
                    fifoBarrier(driver).safe.get.map { _ =>
                        val newPeak = math.max(peak, handle.unsentTailBytes)
                        result match
                            case WriteResult.Done          => loop(k + 1, newPeak)
                            case WriteResult.Partial(_, _) => (true, newPeak)
                            case WriteResult.Error         => (false, newPeak)
                        end match
                    }
                }
        end loop
        loop(0, 0)
    end issueUntilBoundedOrGrown

    /** The bound must have tripped (the driver reported Partial) AND the observed peak unsent tail must stay within the cap: at most the high-water
      * mark plus ONE in-flight payload (the write that crosses the mark is the one that trips Partial, so the peak is the mark plus at most one
      * payload's worth of bytes already appended before the trip), plus a small slack for the engine's per-record framing overhead on the TLS path.
      */
    private def assertBounded(boundTripped: Boolean, peak: Int)(using kyo.test.AssertScope, Frame): Unit =
        val cap = PosixHandle.WriteTailHighWater + perWrite + 64 * 1024
        assert(
            boundTripped,
            s"the write tail bound never tripped against a never-reading peer: every write returned Done and the tail grew to $peak bytes. " +
                "An unbounded tail is the CWE-400 slow-read DoS; the driver must report Partial once the tail reaches the high-water mark."
        )
        assert(
            peak <= cap,
            s"unsent tail grew to $peak bytes against a never-reading peer; it must stay bounded at <= $cap " +
                s"(high-water ${PosixHandle.WriteTailHighWater} + one in-flight write). An unbounded tail is the CWE-400 slow-read DoS."
        )
    end assertBounded

    "write tail stays bounded against a peer that never reads" - {

        "poller TLS path (pendingCipher) does not grow without limit" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                PosixTestSockets.assumePoller()
                val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (writeFd, peerFd) =>
                    val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                    val real     = PollerBackend.default()
                    val pollerFd = real.create()
                    val backend  = RecordingPollerBackend(real)
                    val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                    val handle   = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.tls = Present(clientEngine)
                    discard(driver.start())
                    for
                        handshakeDone <- handshakeOnDriver(driver, clientEngine, serverEngine).safe.get
                        _ = assert(handshakeDone, "the in-memory handshake must complete before the writes")
                        // The peer fd is NEVER drained: no recv is ever issued on peerFd, so the tail can only grow if the driver fails to bound it.
                        result <- issueUntilBoundedOrGrown(driver, handle)
                    yield
                        driver.submitEngineOp(() => serverEngine.free())
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, peerFd)
                        assertBounded(result._1, result._2)
                    end for
                }
        }

        "io_uring raw path (rawPending) does not grow without limit" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                PosixTestSockets.assumeUring()
                PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (writeFd, peerFd) =>
                    val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
                    val realUring = Ffi.load[IoUringBindings]
                    val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
                    val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
                    if rc.value != 0 then
                        realRing.close()
                        throw Closed("PosixHandleWriteTailBoundTest", summon[Frame], s"queue_init failed: rc=${rc.value}")
                    val driver = TestDrivers.forBindings(RecordingIoUringBindings(realUring, realRing), realRing)
                    val handle = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                    discard(driver.start())
                    for
                        result <- issueUntilBoundedOrGrown(driver, handle)
                    yield
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(sock, peerFd)
                        assertBounded(result._1, result._2)
                    end for
                }
        }

        "io_uring TLS path (pendingCipher) does not grow without limit" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                PosixTestSockets.assumeUring()
                val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (writeFd, peerFd) =>
                    val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
                    val realUring = Ffi.load[IoUringBindings]
                    val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
                    val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
                    if rc.value != 0 then
                        realRing.close()
                        throw Closed("PosixHandleWriteTailBoundTest", summon[Frame], s"queue_init failed: rc=${rc.value}")
                    val driver = TestDrivers.forBindings(RecordingIoUringBindings(realUring, realRing), realRing)
                    val handle = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.tls = Present(clientEngine)
                    discard(driver.start())
                    for
                        handshakeDone <- handshakeOnDriver(driver, clientEngine, serverEngine).safe.get
                        _ = assert(handshakeDone, "the in-memory handshake must complete before the writes")
                        result <- issueUntilBoundedOrGrown(driver, handle)
                    yield
                        driver.submitEngineOp(() => serverEngine.free())
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(sock, peerFd)
                        assertBounded(result._1, result._2)
                    end for
                }
        }
    }

end PosixHandleWriteTailBoundTest
