package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines
import kyo.scheduler.IOPromise

/** Inbound-ciphertext BIO bound on the [[PollerIoDriver]] TLS read path (security finding #8, CWE-400 / TLS record amplification).
  *
  * The BoringSSL/OpenSSL read side is an in-memory `BIO_s_mem` that accepts every byte fed to it
  * (`kyo_bssl_feed_ciphertext` / `kyo_ossl_feed_ciphertext`: an unbounded BIO). On its own that is an amplification surface: a peer that
  * crams a large coalesced burst of TLS records into one connection could make the read-BIO hold the whole burst if the driver fed faster than
  * it decrypted. The control is the driver's read-path ordering, NOT a cap in the C shim: the poll carrier `recv`s at most `readBufferSize`
  * bytes per syscall into the per-handle `recvStaging` buffer (PollerIoDriver.dispatchReadTls, the `recvNowWithRetry(fd, staging,
  * readBufferSize, ...)` call), hands that ONE staging buffer to `feedAndDecrypt` on the engine FIFO worker, and only re-arms read interest
  * (which is what allows the NEXT recv) INSIDE that same engine op, AFTER `feedAndDecrypt` has fed the chunk and drained every complete record
  * out of the engine (`decryptInbound` runs to completion before `rearmOwned`). So a second recv can never be fed before the prior feed's
  * plaintext is fully drained: the read-BIO holds at most one `readBufferSize`-sized feed plus at most one incomplete-record tail between
  * feeds, never the whole burst.
  *
  * This test makes that bound OBSERVABLE at the feed/drain seam rather than peeking at an internal BIO length. A real BoringSSL client
  * encrypts N large distinct application records (one record per `writePlain`), the peer blasts the whole ~N*16KB ciphertext stream
  * back-to-back, and a re-arming standing read (the transport's `ReadPump` shape) drives the production recv/feed/decrypt/re-arm loop on the
  * accepted side. The accepted-side engine is wrapped in [[RecordingTlsEngine]] so the test asserts:
  *   - every `feedCiphertext` len is `<= readBufferSize`: the burst reaches the BIO in `readBufferSize`-bounded chunks, never the whole
  *     ~N*16KB at once (the amplification bound; a path that fed the whole burst would record a single feed of the burst size);
  *   - the burst is drained incrementally across MANY feeds (feeds `>= ceil(totalCipher / readBufferSize)`), proving the BIO is consumed as it
  *     fills rather than accumulating;
  *   - `maxInFlight == 1`: no two engine ops overlapped, so no feed ran before the prior feed's drain completed (the single-in-flight invariant
  *     that bounds the inter-feed BIO peak);
  *   - the accumulated decrypted plaintext equals the in-order concatenation of the N records, byte-for-byte (the bound did not cost
  *     correctness: every record still decrypts in order).
  *
  * If a future change let the driver feed a second recv before draining the prior feed (dropping the single-in-flight ordering, or feeding
  * larger-than-`readBufferSize` chunks), `maxInFlight` would exceed 1 or a feed len would exceed `readBufferSize`, and this test fails: it is
  * the regression guard for the finding-#8 control.
  *
  * Built on the same real loopback + real epoll/kqueue + real BoringSSL infrastructure as [[PollerIoDriverTlsStagingAliasTest]]. JVM/Native
  * only (JS uses a different recv shape). Deterministic: every record is encrypted and queued before the reader runs, the sender parks on real
  * writable readiness when the kernel send buffer fills, the reader frees it, and the bounded await resolves on the real plaintext-byte count
  * (no sleep, no timeout-as-synchronization). The FIFO ordering driving the bound is the production mechanism, not a scripted sequence.
  */
class PollerIoDriverTlsInboundBioBoundTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Run a TLS engine op on the driver's engine FIFO and return its result via a promise (the engine single-owner contract). */
    private def onFifo[A](driver: PollerIoDriver, op: => A): Promise.Unsafe[A, Any] =
        val done = Promise.Unsafe.init[A, Any]()
        driver.submitEngineOp(() => done.completeDiscard(Result.succeed(op)))
        done
    end onFifo

    private def isWouldBlock(errno: Int): Boolean =
        errno == PosixConstants.EAGAIN || errno == PosixConstants.EWOULDBLOCK

    /** Send the whole byte array on `fd`, parking on the driver's writable readiness whenever the non-blocking send buffer fills (EAGAIN), so a
      * burst larger than the kernel send buffer is delivered in full without blocking a thread. The reader drains the peer concurrently,
      * freeing send-buffer space and waking the writable readiness, so the sender makes progress on real events only.
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
                    val wp = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    driver.awaitWritable(clientH, wp)
                    wp.safe.get.andThen(loop(off))
                else loop(off + sent)
                end if
        loop(0)
    end sendAllBackpressured

    /** A re-arming standing reader (the transport's `ReadPump` shape): on each delivered plaintext chunk it accumulates the bytes and
      * immediately re-arms the next read via `awaitRead`, completing `done` once `expected` plaintext bytes have arrived. This drives the
      * production recv/feed/decrypt/re-arm loop on the accepted side so the inbound-BIO bound is exercised by the real driver path.
      */
    final private class StandingReader(
        driver: PollerIoDriver,
        handle: PosixHandle,
        expected: Int,
        acc: java.io.ByteArrayOutputStream,
        done: Promise.Unsafe[Unit, Abort[Closed]]
    ) extends IOPromise[Closed, Span[Byte]]:

        private val self: Promise.Unsafe[Span[Byte], Abort[Closed]] =
            this.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]]

        def start()(using AllowUnsafe, Frame): Unit = driver.awaitRead(handle, self)

        override protected def onComplete(): Unit =
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            poll() match
                case Present(Result.Success(bytes)) =>
                    if bytes.isEmpty then
                        done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "EOF before expected bytes")))
                    else
                        acc.write(bytes.toArrayUnsafe)
                        if acc.size() >= expected then done.completeDiscard(Result.succeed(()))
                        else if becomeAvailable() then driver.awaitRead(handle, self)
                        else done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "becomeAvailable failed")))
                case Present(Result.Failure(c: Closed)) => done.completeDiscard(Result.fail(c))
                case Present(Result.Panic(t))           => done.completeDiscard(Result.panic(t))
                case Absent => done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "no result")))
            end match
        end onComplete
    end StandingReader

    "PollerIoDriver TLS inbound BIO stays bounded under a coalesced-record burst" - {
        "a large back-to-back ciphertext burst is fed in readBufferSize-bounded chunks, drained incrementally, single-in-flight (8c)" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                assumePoller()
                val clientEngine    = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine    = TlsRealEngines.singleEngine(isServer = true)
                val recordingServer = RecordingTlsEngine(serverEngine)
                val driver          = PollerIoDriver.init(kyo.net.TransportConfig.default)
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                        val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                        acceptedH.tls = Present(recordingServer)
                        val readBufferSize = acceptedH.readBufferSize
                        onFifo(driver, TlsEngineLoopback.handshake(clientEngine, serverEngine)).safe.get.flatMap { handshakeDone =>
                            assert(handshakeDone, "handshake must complete before the reads")
                            val n = 16
                            // N large distinct application records near the TLS max record size, so the ciphertext stream is ~256 KB: far larger
                            // than the accepted side's 8192-byte recv buffer (PosixHandle.DefaultReadBufferSize) and the kernel socket buffer.
                            // That forces MANY separate recvNow calls (and therefore many feedCiphertext feeds) into the one recvStaging buffer,
                            // which is exactly the coalesced-burst load finding #8 is about. The distinct per-record/per-index pattern makes any
                            // corruption or reorder a concrete byte mismatch.
                            val recordSize = 16000
                            val records =
                                Array.tabulate(n)(k => Array.tabulate[Byte](recordSize)(i => ((k * 31 + i) % 251).toByte))
                            val expectedPlain = records.foldLeft(Array.emptyByteArray)(_ ++ _)
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
                                // parks on real writable readiness when the kernel send buffer fills and the reader frees it, so the whole
                                // ciphertext burst flows across many separate recvs without a thread block.
                                reader.start()
                                Fiber.initUnscoped(Abort.run[Closed](sendAllBackpressured(driver, clientH, client, allCipher))).flatMap {
                                    _ =>
                                        Abort.run[Timeout | Closed](Async.timeout(20.seconds)(done.safe.get)).map { outcome =>
                                            import scala.jdk.CollectionConverters.*
                                            val feedLens = recordingServer.feedLens.iterator().asScala.toList
                                            val maxIn    = recordingServer.maxInFlight.get()
                                            driver.submitEngineOp(() => clientEngine.free())
                                            driver.closeHandle(acceptedH)
                                            driver.closeHandle(clientH)
                                            discard(sock.close(client))
                                            outcome match
                                                case Result.Success(()) =>
                                                    // Byte-exact, in-order plaintext: the bound did not cost correctness.
                                                    assert(
                                                        plainAcc.toByteArray.toList == expectedPlain.toList,
                                                        s"decrypted plaintext mismatch: got ${plainAcc.size()} bytes, expected ${expectedPlain.length}"
                                                    )
                                                    // The amplification bound: NO single feed carried the whole burst. Every feed is bounded by
                                                    // readBufferSize, so the read-BIO never received more than readBufferSize bytes in one feed.
                                                    feedLens.zipWithIndex.foreach { case (len, i) =>
                                                        assert(
                                                            len <= readBufferSize,
                                                            s"feed $i carried $len bytes, exceeds readBufferSize=$readBufferSize: the BIO took more than one recv-sized chunk"
                                                        )
                                                    }
                                                    // Incremental drain: the ~256 KB burst arrived across at least ceil(totalCipher /
                                                    // readBufferSize) feeds, proving the BIO is consumed as it fills, not accumulated whole.
                                                    val minFeeds = (allCipher.length + readBufferSize - 1) / readBufferSize
                                                    assert(
                                                        feedLens.size >= minFeeds,
                                                        s"expected >= $minFeeds feeds for ${allCipher.length} ciphertext bytes at readBufferSize=$readBufferSize, got ${feedLens.size}: the burst was not fed incrementally"
                                                    )
                                                    // The total fed equals the whole ciphertext stream (no bytes dropped by the bound).
                                                    assert(
                                                        feedLens.sum == allCipher.length,
                                                        s"fed ${feedLens.sum} bytes total, expected the whole ciphertext stream ${allCipher.length}"
                                                    )
                                                    // Single-in-flight: no feed ran before the prior feed's drain completed, so the inter-feed
                                                    // BIO peak is bounded by one feed (<= readBufferSize) plus at most one partial-record tail.
                                                    assert(
                                                        maxIn == 1,
                                                        s"engine ops overlapped: maxInFlight=$maxIn (expected 1); a feed ran before the prior drain finished, so the BIO could accumulate"
                                                    )
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

end PollerIoDriverTlsInboundBioBoundTest
