package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome
import kyo.scheduler.IOPromise

/** Reproduce-first regression guard for the TLS read path ET half-close drain on epoll.
  *
  * The defect: `dispatchReadTls` did not forward `eofPending` from `dispatchRead`. On an epoll ET-armed connection where the peer sends
  * ciphertext and then immediately half-closes (TCP FIN), the kernel delivers one event carrying BOTH `EPOLLIN` and `EPOLLRDHUP`. The plain
  * read path (`dispatchReadPlain`) persists this as `halfClose = PeerHalfClosePending` and sets
  * `readMightHaveMore = filled || (halfClose == PeerHalfClosePending)` so the consumer-paced drain forces another recv after plaintext delivery.
  * The TLS path must perform both steps, or it strands:
  *   - if `halfClose` is not advanced to `PeerHalfClosePending`, the drain does not force a re-dispatch after plaintext delivery.
  *   - if the EAGAIN branch does not check for `PeerHalfClosePending`, a connection where the initial recv returned EAGAIN (ciphertext not yet
  *     visible at the moment of the call) with `eofPending=true` re-arms and waits for an EPOLLRDHUP edge that ET will not re-fire.
  *
  * `dispatchReadTls` mirrors `dispatchReadPlain`:
  *   - `dispatchReadTls` accepts and propagates `eofPending`.
  *   - In the `n > 0` branch: `halfClose` is advanced to `PeerHalfClosePending`; `readMightHaveMore` includes the `PeerHalfClosePending` check.
  *   - In the EAGAIN branch: `halfClose` is advanced to `PeerHalfClosePending`; the engine FIFO op delivers `Span.empty` when
  *     `halfClose == PeerHalfClosePending` and no engine-buffered plaintext remains.
  *
  * This test captures the dominant failure shape: a server encrypts a known plaintext, sends the ciphertext, and immediately half-closes
  * the TCP write side (shutdown SHUT_WR). The accepted side runs a TLS-armed `PollerIoDriver` in a re-arming standing-read loop (mirroring
  * the production `ReadPump`). The test asserts:
  *   - ALL decrypted plaintext bytes are delivered in order, byte-exact.
  *   - The terminal read is `Span.empty` (Success, orderly EOF), NOT a `Closed` failure or a timeout (the stranded-strand symptom).
  *
  * Gated on `TlsRealEngines.assumeTlsReady()` (BoringSSL staged) and `PosixTestSockets.assumePoller()` (Linux epoll or macOS kqueue).
  * Validated on podman epoll with STAGE_BORINGSSL=1. No sleep; all synchronization on real promises and real kernel events.
  */
class PollerIoDriverTlsHalfCloseEtTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** A re-arming standing TLS reader that accumulates every delivered plaintext chunk and records the terminal outcome on the first
      * non-data result: an empty Span completes `done` with [[TlsHalfCloseReader.EofSeen]] (orderly close, correct), a `Closed` failure
      * with [[TlsHalfCloseReader.ClosedSeen]] (regression), a timeout with [[TlsHalfCloseReader.TimedOut]] (stranded-strand symptom).
      */
    final private class TlsHalfCloseReader(
        driver: PollerIoDriver,
        handle: PosixHandle,
        acc: java.io.ByteArrayOutputStream,
        done: Promise.Unsafe[String, Any]
    ) extends IOPromise[Closed, ReadOutcome]:

        private val self: Promise.Unsafe[ReadOutcome, Abort[Closed]] =
            this.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]]

        def start()(using AllowUnsafe, Frame): Unit = driver.awaitRead(handle, self)

        override protected def onComplete(): Unit =
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            poll() match
                case Present(Result.Success(ReadOutcome.Bytes(bytes))) =>
                    acc.write(bytes.toArrayUnsafe)
                    if becomeAvailable() then driver.awaitRead(handle, self)
                    else done.completeDiscard(Result.succeed(TlsHalfCloseReader.BecomeAvailableFailed))
                case Present(Result.Success(ReadOutcome.PeerFin | ReadOutcome.LocalShutdown | ReadOutcome.CleanClose)) =>
                    done.completeDiscard(Result.succeed(TlsHalfCloseReader.EofSeen))
                case Present(Result.Success(_)) =>
                    done.completeDiscard(Result.succeed(TlsHalfCloseReader.EofSeen))
                case Present(Result.Failure(_: Closed)) => done.completeDiscard(Result.succeed(TlsHalfCloseReader.ClosedSeen))
                case Present(Result.Panic(t))           => done.completeDiscard(Result.panic(t))
                case Absent                             => done.completeDiscard(Result.succeed(TlsHalfCloseReader.NoResult))
            end match
        end onComplete
    end TlsHalfCloseReader

    private object TlsHalfCloseReader:
        val EofSeen: String               = "eof"
        val ClosedSeen: String            = "closed"
        val BecomeAvailableFailed: String = "becomeAvailable-failed"
        val NoResult: String              = "no-result"
        val TimedOut: String              = "timeout"
    end TlsHalfCloseReader

    /** Poll a real condition until it holds or the bound elapses, re-checking each turn after a short Async.sleep. Mirrors
      * [[IoUringFatalRecordCloseRaceTest.awaitCondition]].
      */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "PollerIoDriver TLS ET half-close drain" - {

        /** Core case: the peer sends encrypted data and immediately half-closes. The ciphertext and the TCP FIN both arrive in the kernel
          * buffer before the reader starts. On epoll, EPOLLIN + EPOLLRDHUP arrive in one event. The TLS path must:
          *   1. Decrypt the ciphertext and deliver the plaintext to the consumer.
          *   2. On the consumer's next awaitRead (re-arm), force another recv via readMightHaveMore (set when halfClose == PeerHalfClosePending).
          *   3. The second recv returns 0 (FIN), which surfaces as Span.empty (Success, not Closed).
          * Without the advance: readMightHaveMore would be false after delivering plaintext (halfClose not advanced to PeerHalfClosePending), so the consumer
          * would re-arm and park forever waiting for a second EPOLLRDHUP edge that ET will not re-fire.
          */
        "TLS plaintext delivered in full and Span.empty surfaces on ET half-close with buffered ciphertext (8c)" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                PosixTestSockets.assumePoller()
                TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                    val driver = PollerIoDriver.init(kyo.net.TransportConfig.default)
                    discard(driver.start())
                    Sync.ensure(Sync.defer(driver.close())) {
                        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                            // Complete the TLS handshake in-memory (no socket involvement for the handshake bytes here; both engines share
                            // memory. The accepted side uses the server engine for decryption.
                            val handshakeDone = TlsEngineLoopback.handshake(clientEngine, serverEngine)
                            assert(handshakeDone, "TLS handshake must complete before the half-close test")
                            // A known plaintext, distinct per index so reordering or truncation is caught byte-for-byte. Two TLS records
                            // to ensure the driver handles multi-record plaintext, not just a single record.
                            val record1 = Array.tabulate[Byte](4096)(i => (i % 251).toByte)
                            val record2 = Array.tabulate[Byte](4096)(i => ((i + 7) % 251).toByte)
                            val plain   = record1 ++ record2
                            // Encrypt both records through the client engine (in-memory, produces raw ciphertext bytes). The driver
                            // recvs these bytes from the socket and feeds the server engine via submitEngineOp -> feedAndDecrypt; no
                            // pre-feeding here (that would double-feed the engine and cause a TLS protocol error).
                            val cipher1 = TlsEngineLoopback.encrypt(clientEngine, record1)
                            val cipher2 = TlsEngineLoopback.encrypt(clientEngine, record2)
                            val cipher  = cipher1 ++ cipher2
                            // Attach the server engine to the accepted handle so dispatchReadTls uses it.
                            val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                            acceptedH.tls = Present(serverEngine)
                            // Send the ciphertext on the raw socket, then half-close for writing. Both the ciphertext and the TCP FIN
                            // arrive in the accepted side's kernel recv buffer before the reader below starts. On epoll, this guarantees
                            // EPOLLIN + EPOLLRDHUP will be co-reported in one poll event (the half-close path this guards).
                            val cipherBuf = Buffer.fromArray[Byte](cipher)
                            val sendR =
                                try sock.sendNow(client, cipherBuf, cipher.length.toLong, PosixConstants.MSG_NOSIGNAL)
                                finally cipherBuf.close()
                            assert(sendR.value.toInt > 0, s"send failed: errno=${sendR.errorCode}")
                            PosixTestSockets.halfClose(sock, client)
                            // Start the standing reader after the data and FIN are queued, so the test is deterministic.
                            val acc  = new java.io.ByteArrayOutputStream
                            val done = Promise.Unsafe.init[String, Any]()
                            val r    = new TlsHalfCloseReader(driver, acceptedH, acc, done)
                            r.start()
                            Abort.run[Timeout](Async.timeout(10.seconds)(done.safe.get)).map { outcome =>
                                driver.closeHandle(acceptedH)
                                discard(sock.close(client))
                                // The driver's TLS teardown (dischargeClose -> shutdownTls -> freeResources) is queued, not inline: it clears
                                // acceptedH.tls only once it actually runs on the poll thread. TlsRealEngines.withEngines' own `ensure` frees
                                // BOTH engines out-of-band the instant this body's result value completes; without waiting here that free can
                                // race the still-queued dischargeClose's SSL_shutdown, a native use-after-free on the freed engine (see
                                // TlsRealEngines.withEngines' ownership-rule doc). Waiting for tls to go Absent settles that race first, so
                                // withEngines' free becomes a harmless CAS-guarded second free.
                                awaitCondition(5.seconds)(!acceptedH.tls.isDefined).map { settled =>
                                    assert(settled, "the driver's own TLS teardown never settled (a hang, not the race this guard targets)")
                                    outcome match
                                        case Result.Success(TlsHalfCloseReader.EofSeen) =>
                                            assert(
                                                acc.toByteArray.toList == plain.toList,
                                                s"TLS plaintext not delivered in full before EOF: got ${acc.size()} of ${plain.length} bytes"
                                            )
                                        case Result.Success(TlsHalfCloseReader.ClosedSeen) =>
                                            fail(
                                                s"TLS half-close surfaced Closed after ${acc.size()} of ${plain.length} bytes instead of " +
                                                    "Span.empty EOF (dispatchReadTls did not propagate eofPending to halfClose == PeerHalfClosePending)"
                                            )
                                        case Result.Success(other) => fail(s"unexpected reader outcome: $other after ${acc.size()} bytes")
                                        case Result.Failure(_: Timeout) =>
                                            fail(
                                                s"TLS half-close read stalled after ${acc.size()} of ${plain.length} bytes " +
                                                    "(no EOF delivered; readMightHaveMore not set from halfClose == PeerHalfClosePending, " +
                                                    "strand waiting for an EPOLLRDHUP edge that ET will not re-fire)"
                                            )
                                        case other => fail(s"unexpected outcome: $other")
                                    end match
                                }
                            }
                        }
                    }
                }
        }

        /** EAGAIN variant: the reader is started BEFORE the data arrives, so the first recv returns EAGAIN with eofPending=true.
          * This exercises the EAGAIN branch of dispatchReadTls. Without the advance: `halfClose` is not advanced to `PeerHalfClosePending` in the
          * EAGAIN branch, so the handle would re-arm and wait for an EPOLLRDHUP edge that ET will not re-fire after the data was delivered.
          *
          * Timing: the reader registers awaitRead first; then the sender sends ciphertext + half-close. On the first edge the recv may
          * succeed (n > 0) or EAGAIN depending on race; on the second edge (if a first EAGAIN was returned) the recv delivers the data.
          * The test is correct in both races because:
          *   - If first recv delivers data (n > 0 with eofPending): covered by the first leaf above.
          *   - If first recv returns EAGAIN (eofPending set, no data yet): the EAGAIN branch must advance halfClose to PeerHalfClosePending
          *     and deliver EOF after subsequent edges bring the data + FIN; otherwise the strand parks forever on a missing re-edge.
          * The test accepts either outcome (both paths are correct); only a Closed failure or a timeout is a regression.
          */
        "TLS Span.empty surfaces on ET half-close when initial recv returns EAGAIN (8c)" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                PosixTestSockets.assumePoller()
                TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                    val driver = PollerIoDriver.init(kyo.net.TransportConfig.default)
                    discard(driver.start())
                    Sync.ensure(Sync.defer(driver.close())) {
                        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                            val handshakeDone = TlsEngineLoopback.handshake(clientEngine, serverEngine)
                            assert(handshakeDone, "TLS handshake must complete before the half-close test")
                            val plainData = Array.tabulate[Byte](2048)(i => (i % 127).toByte)
                            // Encrypt through the client engine; the driver will recv the wire bytes and feed the server engine via
                            // submitEngineOp -> feedAndDecrypt. No pre-feeding: that would double-feed and trigger a TLS protocol error.
                            val cipher    = TlsEngineLoopback.encrypt(clientEngine, plainData)
                            val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                            acceptedH.tls = Present(serverEngine)
                            // Register the awaitRead FIRST so the reader is parked when the data arrives.
                            val acc  = new java.io.ByteArrayOutputStream
                            val done = Promise.Unsafe.init[String, Any]()
                            val r    = new TlsHalfCloseReader(driver, acceptedH, acc, done)
                            r.start()
                            // Send data + half-close after the reader is registered, allowing an initial EAGAIN race.
                            val cipherBuf = Buffer.fromArray[Byte](cipher)
                            val sendR =
                                try sock.sendNow(client, cipherBuf, cipher.length.toLong, PosixConstants.MSG_NOSIGNAL)
                                finally cipherBuf.close()
                            assert(sendR.value.toInt > 0, s"send failed: errno=${sendR.errorCode}")
                            PosixTestSockets.halfClose(sock, client)
                            Abort.run[Timeout](Async.timeout(10.seconds)(done.safe.get)).map { outcome =>
                                driver.closeHandle(acceptedH)
                                discard(sock.close(client))
                                // See the sibling leaf above: wait for the queued TLS teardown to actually settle before returning, so
                                // TlsRealEngines.withEngines' out-of-band free of serverEngine cannot race the driver's own SSL_shutdown.
                                awaitCondition(5.seconds)(!acceptedH.tls.isDefined).map { settled =>
                                    assert(settled, "the driver's own TLS teardown never settled (a hang, not the race this guard targets)")
                                    outcome match
                                        case Result.Success(TlsHalfCloseReader.EofSeen) =>
                                            assert(
                                                acc.toByteArray.toList == plainData.toList,
                                                s"TLS plaintext not delivered in full before EOF: got ${acc.size()} of ${plainData.length} bytes"
                                            )
                                        case Result.Success(TlsHalfCloseReader.ClosedSeen) =>
                                            fail(
                                                s"TLS half-close surfaced Closed after ${acc.size()} of ${plainData.length} bytes " +
                                                    "(regression: should be Span.empty EOF)"
                                            )
                                        case Result.Success(other) => fail(s"unexpected reader outcome: $other after ${acc.size()} bytes")
                                        case Result.Failure(_: Timeout) =>
                                            fail(
                                                s"TLS half-close EAGAIN read stalled after ${acc.size()} of ${plainData.length} bytes " +
                                                    "(halfClose not advanced to PeerHalfClosePending in EAGAIN branch; strand waiting for a missing re-edge)"
                                            )
                                        case other => fail(s"unexpected outcome: $other")
                                    end match
                                }
                            }
                        }
                    }
                }
        }
    }

end PollerIoDriverTlsHalfCloseEtTest
