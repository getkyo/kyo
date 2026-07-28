package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome
import kyo.scheduler.IOPromise

/** Aliasing / single-in-flight guard for the per-handle TLS `recvStaging` buffer on the [[PollerIoDriver]] read path.
  *
  * On the TLS read path the poll carrier `recv`s ciphertext directly into the per-handle `recvStaging` buffer (PollerIoDriver.scala:801-802)
  * and then hands that SAME buffer to the engine FIFO worker, which feeds it to `feedCiphertext` (:809-810). The staging buffer is reused
  * across reads (PosixHandle.scala:80, `stagingFor` at :773). The correctness invariant is single-in-flight: the FIFO worker's
  * `feedCiphertext` read of staging must complete before the poll carrier's next `recvNow` write into it, otherwise a second recv overwrites
  * staging while the first feed is still consuming it (a reused-buffer aliasing corruption). The invariant is enforced by the engine-op
  * enqueue ordering: the re-arm that allows the next recv (`rearmOwned`) runs INSIDE the engine op, after `feedAndDecrypt` consumed staging
  * (:810-816, :726-731).
  *
  * This test stresses that ordering: a real BoringSSL client encrypts N distinct application records, the peer blasts all N ciphertext
  * flights back-to-back, and a re-arming standing read (the transport's `ReadPump` shape) drives back-to-back recvs into the one reused
  * staging buffer. The accepted-side engine is wrapped in [[RecordingTlsEngine]] so the test observes:
  *   - `maxInFlight == 1`: no two engine ops (feeds) overlapped on the shared staging buffer (the single-owner proof);
  *   - every `feedCiphertext` buffer is the SAME `recvStaging` instance (the reuse is real, not a fresh buffer per read);
  *   - the accumulated decrypted plaintext equals the in-order concatenation of the N records, byte-for-byte. A staging-aliasing bug (a recv
  *     overwriting staging mid-feed) would corrupt or reorder the decrypted bytes and fail the byte-equality assertion.
  *
  * Built on the same real loopback + real epoll/kqueue + real BoringSSL infrastructure as the TLS leaves in PollerIoDriverTest. JVM/Native
  * only (JS uses the sendNow path and a different recv shape). Deterministic: every record is encrypted and queued before the reader runs,
  * the bounded await resolves on the real plaintext-byte count, and the FIFO ordering is the production mechanism, not a scripted sequence.
  */
class PollerIoDriverTlsStagingAliasTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Run a TLS engine op on the driver's engine FIFO and return its result via a promise (the engine single-owner contract: every op for a
      * connection runs on the one FIFO worker carrier).
      */
    private def onFifo[A](driver: PollerIoDriver, op: => A): Promise.Unsafe[A, Any] =
        val done = Promise.Unsafe.init[A, Any]()
        driver.submitEngineOp(() => done.completeDiscard(Result.succeed(op)))
        done
    end onFifo

    /** Send the whole byte array on `clientH`'s fd, parking on the driver's writable readiness whenever the non-blocking send buffer fills
      * (EAGAIN), so a large payload (larger than the kernel send buffer) is delivered in full without blocking a thread. The reader drains
      * the peer concurrently, freeing send-buffer space and waking the writable readiness, so the sender makes progress on real events only
      * (no sleep, no busy-spin). Returns once every byte has been accepted by the kernel.
      */
    private def sendAllBackpressured(driver: PollerIoDriver, clientH: PosixHandle, fd: Int, bytes: Array[Byte])(using
        Frame
    ): Unit < (Abort[Closed] & Async) =
        def loop(off: Int): Unit < (Abort[Closed] & Async) =
            if off >= bytes.length then ()
            else
                val len = bytes.length - off
                val buf = Buffer.alloc[Byte](len)
                var i   = 0
                while i < len do
                    buf.set(i, bytes(off + i))
                    i += 1
                val sent =
                    try
                        val r = sock.sendNow(fd, buf, len.toLong, PosixConstants.MSG_NOSIGNAL)
                        val n = r.value.toInt
                        if n < 0 then if isWouldBlock(r.errorCode) then 0 else -1
                        else n
                    finally buf.close()
                if sent < 0 then Abort.fail(Closed("sendAllBackpressured", summon[Frame], s"send failed fd=$fd"))
                else if sent == len then ()
                else if sent == 0 then
                    // Send buffer full: park on writable readiness, then retry the unsent region. The reader drains the peer concurrently.
                    val wp = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    driver.awaitWritable(clientH, wp)
                    wp.safe.get.andThen(loop(off))
                else loop(off + sent)
                end if
        loop(0)
    end sendAllBackpressured

    private def isWouldBlock(errno: Int): Boolean =
        errno == PosixConstants.EAGAIN || errno == PosixConstants.EWOULDBLOCK

    /** A re-arming standing reader (the transport's `ReadPump` shape): on each delivered plaintext chunk it accumulates the bytes and
      * immediately re-arms the next read via `awaitRead`, completing `done` once `expected` plaintext bytes have arrived. This drives
      * back-to-back recvs into the reused `recvStaging` buffer so a second recv can race the prior feed if the single-in-flight ordering is
      * broken.
      */
    final private class StandingReader(
        driver: PollerIoDriver,
        handle: PosixHandle,
        expected: Int,
        acc: java.io.ByteArrayOutputStream,
        done: Promise.Unsafe[Unit, Abort[Closed]]
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
                    if acc.size() >= expected then done.completeDiscard(Result.succeed(()))
                    else if becomeAvailable() then driver.awaitRead(handle, self)
                    else done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "becomeAvailable failed")))
                case Present(Result.Success(ReadOutcome.PeerFin | ReadOutcome.LocalShutdown | ReadOutcome.CleanClose)) =>
                    done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "EOF before expected bytes")))
                case Present(Result.Success(_)) =>
                    done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "EOF before expected bytes")))
                case Present(Result.Failure(c: Closed)) => done.completeDiscard(Result.fail(c))
                case Present(Result.Panic(t))           => done.completeDiscard(Result.panic(t))
                case Absent => done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "no result")))
            end match
        end onComplete
    end StandingReader

    "PollerIoDriver TLS recv staging is single-in-flight" - {
        "N back-to-back ciphertext flights into a reused staging buffer decrypt to the correct plaintext, in order" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                assumePoller()
                val clientEngine    = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine    = TlsRealEngines.singleEngine(isServer = true)
                val recordingServer = RecordingTlsEngine(serverEngine)
                val driver          = PollerIoDriver.init()
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                        val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                        acceptedH.tls = Present(recordingServer)
                        // Handshake both real engines on the FIFO worker (single-owner), then encrypt N distinct records on the FIFO too.
                        onFifo(driver, TlsEngineLoopback.handshake(clientEngine, serverEngine)).safe.get.flatMap { handshakeDone =>
                            assert(handshakeDone, "handshake must complete before the reads")
                            val n = 12
                            // N large distinct application records. Each record is 16000 bytes (near the TLS max record size) with a
                            // per-record byte pattern (base k, stepped by index), so the ciphertext stream is ~192 KB: far larger than the
                            // accepted side's 8192-byte recv buffer (PosixHandle.DefaultReadBufferSize) and the kernel socket buffer, which
                            // forces MANY separate recvNow calls (and therefore many feedCiphertext feeds) into the one reused recvStaging
                            // buffer. The distinct per-record / per-index pattern makes any aliasing corruption or reorder a concrete byte
                            // mismatch. Small records coalesce into one recv (a single feed) and would not exercise the staging-overwrite window.
                            val recordSize = 16000
                            val records =
                                Array.tabulate(n)(k => Array.tabulate[Byte](recordSize)(i => ((k * 31 + i) % 251).toByte))
                            val expectedPlain = records.foldLeft(Array.emptyByteArray)(_ ++ _)
                            // Encrypt each record on the FIFO and concatenate the ciphertext, then blast it all back-to-back from the client so
                            // multiple recvs land into the one reused recvStaging buffer on the accepted side.
                            def encryptAll(k: Int, acc: Array[Byte]): Array[Byte] < (Abort[Closed] & Async) =
                                if k >= n then acc
                                else
                                    onFifo(driver, TlsEngineLoopback.encrypt(clientEngine, records(k))).safe.get
                                        .map(c => encryptAll(k + 1, acc ++ c))
                            encryptAll(0, Array.emptyByteArray).flatMap { allCipher =>
                                val clientH  = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                                val plainAcc = new java.io.ByteArrayOutputStream
                                val done     = Promise.Unsafe.init[Unit, Abort[Closed]]()
                                val reader   = new StandingReader(driver, acceptedH, expectedPlain.length, plainAcc, done)
                                // Start the standing reader first so it drains concurrently, then fork the backpressured sender: the sender
                                // parks on real writable readiness when the kernel send buffer fills and the reader frees it, so the ~192 KB
                                // ciphertext flows across many separate recvs without a thread block.
                                reader.start()
                                Fiber.initUnscoped(Abort.run[Closed](sendAllBackpressured(driver, clientH, client, allCipher))).flatMap {
                                    _ =>
                                        Abort.run[Timeout | Closed](Async.timeout(15.seconds)(done.safe.get)).map { outcome =>
                                            import scala.jdk.CollectionConverters.*
                                            val feedBufs = recordingServer.feedBufs.iterator().asScala.toList
                                            val staging =
                                                acceptedH.recvStaging.getOrElse(fail("recvStaging must be Present after TLS reads"))
                                            val maxIn = recordingServer.maxInFlight.get()
                                            driver.submitEngineOp(() => clientEngine.free())
                                            driver.closeHandle(acceptedH)
                                            driver.closeHandle(clientH)
                                            discard(sock.close(client))
                                            outcome match
                                                case Result.Success(()) =>
                                                    // Byte-exact, in-order plaintext: an aliasing recv-over-staging-mid-feed corrupts/reorders here.
                                                    assert(
                                                        plainAcc.toByteArray.toList == expectedPlain.toList,
                                                        s"decrypted plaintext mismatch: got ${plainAcc.size()} bytes, expected ${expectedPlain.length}"
                                                    )
                                                    // Single-in-flight: no two engine ops (feeds) overlapped on the shared staging buffer.
                                                    assert(
                                                        maxIn == 1,
                                                        s"engine ops overlapped on the shared staging buffer: maxInFlight=$maxIn (expected 1)"
                                                    )
                                                    // The recv staging buffer is reused across reads: every feedCiphertext got the SAME instance.
                                                    // Require >= 2 feeds so the reuse / single-in-flight assertions are exercised over a real
                                                    // multi-recv sequence (a single coalesced feed would not stress the staging-overwrite window).
                                                    assert(
                                                        feedBufs.size >= 2,
                                                        s"expected >= 2 feedCiphertext calls so the staging-overwrite window is exercised, got ${feedBufs.size}"
                                                    )
                                                    feedBufs.zipWithIndex.foreach { case (buf, i) =>
                                                        assert(
                                                            buf eq staging,
                                                            s"feedCiphertext $i received a different buffer than recvStaging: got $buf, staging=$staging"
                                                        )
                                                    }
                                                    succeed
                                                case Result.Failure(_: Timeout) =>
                                                    fail(
                                                        s"standing read stalled after ${plainAcc.size()} of ${expectedPlain.length} plaintext bytes"
                                                    )
                                                case other => fail(s"unexpected standing-read outcome: $other")
                                            end match
                                        }
                                }
                            }
                        }
                    }
                }
        }
    }

end PollerIoDriverTlsStagingAliasTest
