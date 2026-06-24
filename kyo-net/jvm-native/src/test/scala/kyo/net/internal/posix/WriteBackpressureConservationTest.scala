package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.transport.WriteResult

/** Write CONSERVATION under repeated backpressure plus writable-event interleaving for the TLS write path in [[PollerIoDriver]].
  *
  * This pushes the `writableArmed` arm/clear/re-submit cycle in [[PollerIoDriver.armWritableForFlush]] far harder than the single double-arm
  * coalesce that [[WritableArmedCoalesceTest]] pins. The field is the one piece of write-path state that is NOT strictly single-owner: the engine
  * FIFO worker SETS `writableArmed = true` while arming, and the writable-promise completion carrier (off the FIFO) CLEARS it false. The danger
  * is a lost arm: an append that lands while `writableArmed` is true relies on the already-pending flush re-submit to carry its bytes; if the clear
  * races that append such that no flush is pending and none is armed, the appended ciphertext is stranded forever in `pendingCipher` and the bytes
  * never reach the socket.
  *
  * The invariant under test is CONSERVATION: every byte of every write must ultimately be sent to the socket, exactly once, in submission order,
  * with none stranded in `pendingCipher` and none duplicated. With a real engine the wire bytes are real ciphertext, so conservation is verified
  * by DECRYPTING the ciphertext the peer received (with the server engine) and comparing the recovered plaintext to the concatenation of the
  * payloads. Both engines are created, handshaked, and used on ONE carrier (the driver's engine FIFO worker): the client encrypts on the FIFO
  * (via `driver.write`) and the server decrypts on the FIFO (via `submitEngineOp`), matching the engine-FIFO single-owner contract. Driving a real
  * BoringSSL session across carriers corrupts it.
  *
  * Coherence: the backend is a [[RecordingPollerBackend]] over the real epoll/kqueue, the socket is a real `smallBufferedPair`, and the engines
  * are real BoringSSL engines post-handshake. The real kernel produces both the EAGAIN backpressure (the peer does not read, so the send buffer
  * fills) AND the writable readiness (when the test drains the peer, the buffer empties and the real backend fires a real write event the poll
  * loop turns into a flush re-submit). There is no scripted event source: the real socket drives the arm/clear/re-submit cycle exactly as
  * production does.
  *
  * Two leaves:
  *   - a SEQUENTIAL leaf that issues a known sequence of writes, then drains the peer to completion via real read-readiness, decrypts the received
  *     ciphertext on the FIFO, and asserts the recovered plaintext equals the payloads concatenated in submission order;
  *   - a many-fiber STRESS leaf that fires hundreds of concurrent writes against a real socket whose peer drains concurrently, decrypts the
  *     received ciphertext on the FIFO, and asserts the same conservation invariant (which holds under ANY interleaving).
  *
  * JS uses the synchronous `sendNow` binding rather than the `@Ffi.blocking` `send` these leaves target, so both leaves gate on isJS.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair for real EAGAIN and real write-readiness) and `TlsRealEngines.assumeTlsReady()`
  * (a real BoringSSL/OpenSSL engine).
  *
  * Anti-flakiness: the engines handshake in-memory on the FIFO worker before any write; the drain latches on the peer's real read-readiness via
  * `driver.awaitRead` (a real `Promise.Unsafe` completed by the real recv), each issued write returns Done synchronously, and each decrypt batch
  * latches on a `submitEngineOp` Promise. No sleep; the loop exits on the real condition recovered >= total.
  *
  * Uses real BoringSSL engines via `TlsRealEngines.singleEngine`, handshaked and exercised on the driver's engine FIFO worker. Conservation is
  * verified by decrypting all bytes read back from the peer fd and comparing the recovered plaintext to the payloads.
  */
class WriteBackpressureConservationTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Complete the in-memory handshake for `client`/`server` ON the driver's engine FIFO worker so both sessions are created, handshaked, and
      * later used (client encrypt + server decrypt) on the same carrier. Returns a promise that completes once the handshake has run.
      */
    private def handshakeOnDriver(driver: PollerIoDriver, client: TlsEngine, server: TlsEngine)(using
        AllowUnsafe
    ): Promise.Unsafe[Boolean, Any] =
        val done = Promise.Unsafe.init[Boolean, Any]()
        driver.submitEngineOp(() => done.completeDiscard(Result.succeed(TlsEngineLoopback.handshake(client, server))))
        done
    end handshakeOnDriver

    /** Decrypt one ciphertext batch with `server` ON the driver's engine FIFO worker and return the recovered plaintext via a promise. Keeps the
      * server session single-carrier (it is handshaked and read on the FIFO).
      */
    private def decryptOnDriver(driver: PollerIoDriver, server: TlsEngine, ciphertext: Array[Byte])(using
        AllowUnsafe
    ): Promise.Unsafe[Array[Byte], Any] =
        val done = Promise.Unsafe.init[Array[Byte], Any]()
        driver.submitEngineOp { () =>
            TlsEngineLoopback.feed(server, ciphertext)
            done.completeDiscard(Result.succeed(TlsEngineLoopback.drainPlain(server)))
        }
        done
    end decryptOnDriver

    /** Drain the peer fd, decrypting the received ciphertext with `serverEngine` on the FIFO worker, until `total` plaintext bytes are recovered.
      *
      * Each iteration parks an `awaitRead` on the peer fd, recvNow-drains all buffered ciphertext, decrypts it on the FIFO, and accumulates the
      * recovered plaintext. Draining the socket frees kernel buffer space, which makes the WRITE fd writable, so the real backend fires a real
      * write event the poll loop turns into a flush re-submit. The loop exits on the real condition recovered >= total.
      *
      * Anti-flakiness: each iteration parks on the peer's real read-ready Promise (no sleep). The bound `steps > total + 256` only trips on a
      * genuine conservation shortfall, at which point the assertion catches it.
      */
    private def drainPeerPlaintext(
        driver: PollerIoDriver,
        peerHandle: PosixHandle,
        total: Int,
        serverEngine: TlsEngine
    )(using AllowUnsafe): Array[Byte] < (Abort[Closed] & Async) =
        val acc = scala.collection.mutable.ArrayBuffer.empty[Byte]
        // The driver's dispatch on the engine-less peer handle is the SINGLE reader of the peer fd (a second recvNow here would split the byte
        // stream with the driver's recvNow and corrupt the TLS record order). Each awaitRead delivers the next in-order ciphertext batch, which
        // is decrypted on the FIFO worker; draining frees buffer space so the real backend re-flushes the write side.
        def loop(steps: Int): Array[Byte] < (Abort[Closed] & Async) =
            if acc.size >= total then acc.toArray
            else if steps > total + 256 then acc.toArray // safety bound; the conservation assertion catches a shortfall
            else
                val p = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                driver.awaitRead(peerHandle, p)
                p.safe.get.map { delivered =>
                    val batch = delivered.toArray
                    val step: Unit < Async =
                        if batch.isEmpty then ()
                        else decryptOnDriver(driver, serverEngine, batch).safe.get.map(plain => acc ++= plain)
                    step.map(_ => loop(steps + 1))
                }
            end if
        end loop
        loop(0)
    end drainPeerPlaintext

    "TLS write conservation under repeated backpressure + writable interleaving" - {

        "sequential: a known sequence of large writes loses no byte and preserves order" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                PosixTestSockets.assumePoller()
                // 16 KB per write exceeds the shrunk kernel SO_SNDBUF (min ~2-4 KB), so each write that lands while the buffer is full EAGAINs
                // and arms writability. The peer is drained only after all writes are issued, so the arm/clear/re-submit cycle runs across the
                // whole drain. writeFd = client (first): limited SO_SNDBUF; peerFd = accepted (second): receives the writes.
                val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                PosixTestSockets.smallBufferedPair(sndBuf = 128, rcvBuf = 128).map { case (writeFd, peerFd) =>
                    val cycles     = 8
                    val perWrite   = 16384
                    val spy        = RecordingSocketBindings(Ffi.load[SocketBindings])
                    val real       = PollerBackend.default()
                    val pollerFd   = real.create()
                    val backend    = RecordingPollerBackend(real)
                    val driver     = TestDrivers.forBackend(backend, pollerFd, spy)
                    val handle     = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val peerHandle = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.tls = Present(clientEngine)
                    discard(driver.start())

                    // Distinct, identifiable payloads: write k uses perWrite bytes whose value is (k % 251)+1, so the concatenation is a
                    // known sequence.
                    def payload(k: Int): Array[Byte] =
                        Array.fill[Byte](perWrite)(((k % 251) + 1).toByte)

                    val expected = (0 until cycles).flatMap(k => payload(k).toIndexedSeq).toArray
                    val total    = expected.length

                    def write(k: Int): WriteResult < Sync = Sync.defer(driver.write(handle, Span.fromUnsafe(payload(k)), 0))

                    // Issue all writes (each returns Done synchronously; the FIFO encrypts + flushes, EAGAINing and arming as the buffer fills).
                    val issueAll: Unit < Async =
                        Loop(0) { k =>
                            if k >= cycles then Loop.done(())
                            else
                                write(k).map(w =>
                                    assert(w == WriteResult.Done, s"write $k should be Done, got $w"); Loop.continue(k + 1)
                                )
                        }

                    for
                        handshakeDone <- handshakeOnDriver(driver, clientEngine, serverEngine).safe.get
                        _ = assert(handshakeDone, "the in-memory handshake must complete before the writes")
                        _ <- issueAll
                        // Drain: latch on the peer's real read-readiness; each drain frees buffer space and the real backend re-flushes.
                        decrypted <- drainPeerPlaintext(driver, peerHandle, total, serverEngine)
                    yield
                        // Free the engines on the FIFO worker (closeHandle routes the client free; the server free is submitted), then the driver.
                        driver.submitEngineOp(() => serverEngine.free())
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, peerFd)
                        discard(assert(
                            decrypted.length == total,
                            s"conservation: expected $total plaintext bytes recovered from the peer, got ${decrypted.length} " +
                                s"(lost ${total - decrypted.length} or duplicated)"
                        ))
                        discard(assert(
                            decrypted.sameElements(expected),
                            "conservation: decrypted peer bytes must equal the payloads concatenated in submission order"
                        ))
                        assert(
                            handle.pendingCipher.isEmpty || handle.pendingCipherSent == 0,
                            "no bytes may be stranded in pendingCipher"
                        )
                    end for
                }
        }

        "stress: hundreds of concurrent writes with EAGAIN/drain interleaving lose no byte" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                PosixTestSockets.assumePoller()
                // writeFd = client (first): limited SO_SNDBUF; peerFd = accepted (second): receives writes.
                val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                PosixTestSockets.smallBufferedPair(sndBuf = 128, rcvBuf = 128).map { case (writeFd, peerFd) =>
                    val writes     = 400
                    val spy        = RecordingSocketBindings(Ffi.load[SocketBindings])
                    val real       = PollerBackend.default()
                    val pollerFd   = real.create()
                    val backend    = RecordingPollerBackend(real)
                    val driver     = TestDrivers.forBackend(backend, pollerFd, spy)
                    val handle     = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val peerHandle = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.tls = Present(clientEngine)
                    discard(driver.start())

                    // Each write w emits a 4-byte big-endian marker equal to its index, so the decrypted bytes, regrouped into 4-byte words,
                    // must be exactly 0,1,2,... in order. Distinct per write, so loss / reorder / duplication is detectable.
                    def payload(w: Int): Array[Byte] =
                        Array[Byte](((w >> 24) & 0xff).toByte, ((w >> 16) & 0xff).toByte, ((w >> 8) & 0xff).toByte, (w & 0xff).toByte)

                    val total       = writes * 4
                    val issuingDone = new AtomicBoolean(false)

                    val recovered = scala.collection.mutable.ArrayBuffer.empty[Byte]

                    // A fiber that drains the peer concurrently with writes being issued. The driver's dispatch on the engine-less peer handle is the
                    // SINGLE reader of the peer fd (a separate recvNow would split the byte stream and corrupt the TLS record order). Each awaitRead
                    // delivers the next in-order ciphertext batch, decrypted on the FIFO worker; draining frees buffer space so the write side re-flushes.
                    def drainLoop: Unit < Async =
                        Loop(0) { steps =>
                            if recovered.size >= total && issuingDone.get() then Loop.done(())
                            else if steps > 8 * total + 256 then Loop.done(()) // safety net
                            else
                                val p = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                                driver.awaitRead(peerHandle, p)
                                Abort.run[Closed](p.safe.get).map {
                                    case Result.Success(delivered) =>
                                        val batch = delivered.toArray
                                        val step: Unit < Async =
                                            if batch.isEmpty then ()
                                            else decryptOnDriver(driver, serverEngine, batch).safe.get.map(plain => recovered ++= plain)
                                        step.map(_ => Loop.continue(steps + 1))
                                    case _ => Loop.continue(steps + 1)
                                }
                        }

                    val issueAll: Unit < Async =
                        Loop(0) { w =>
                            if w >= writes then
                                issuingDone.set(true)
                                Loop.done(())
                            else
                                Sync.defer(driver.write(handle, Span.fromUnsafe(payload(w)), 0)).map { r =>
                                    assert(r == WriteResult.Done, s"write $w should return Done, got $r")
                                    Loop.continue(w + 1)
                                }
                        }

                    for
                        handshakeDone <- handshakeOnDriver(driver, clientEngine, serverEngine).safe.get
                        _ = assert(handshakeDone, "the in-memory handshake must complete before the writes")
                        drainFiber <- Fiber.initUnscoped(drainLoop)
                        _          <- issueAll
                        _          <- drainFiber.get
                    yield
                        driver.submitEngineOp(() => serverEngine.free())
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, peerFd)
                        val got = recovered.toList
                        discard(assert(
                            got.size == total,
                            s"conservation: expected $total plaintext bytes, got ${got.size} (lost ${total - got.size} or duplicated)"
                        ))
                        // Regroup into 4-byte words and decode the markers: they must be exactly 0..writes-1 in order.
                        val words = got.grouped(4).map { g =>
                            val b = g.toArray
                            ((b(0) & 0xff) << 24) | ((b(1) & 0xff) << 16) | ((b(2) & 0xff) << 8) | (b(3) & 0xff)
                        }.toList
                        discard(assert(
                            words == (0 until writes).toList,
                            s"conservation: write markers must arrive 0..${writes - 1} in submission order with none lost/duplicated"
                        ))
                        assert(
                            handle.pendingCipher.isEmpty || handle.pendingCipherSent == 0,
                            "no bytes may be stranded in pendingCipher"
                        )
                    end for
                }
        }
    }

end WriteBackpressureConservationTest
