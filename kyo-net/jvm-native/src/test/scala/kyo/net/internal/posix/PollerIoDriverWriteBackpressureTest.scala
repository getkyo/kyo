package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Reproduction + regression guard for the TLS write-path backpressure stall in [[PollerIoDriver]].
  *
  * A TLS write routes through `submitEngineOp` and runs on the SINGLE per-driver engine FIFO worker (one op at a time for every connection
  * on the driver). When the socket send buffer is full, the ciphertext send returns EAGAIN. A spin that retries the SAME bytes in a
  * bounded retry loop (up to 4096 iterations) inside that one worker, then gives up with `WriteResult.Error`, has two consequences this leaf pins:
  *
  *   - Property (a) no spurious teardown: a briefly-slow peer (a full kernel buffer that drains only when the test reads it) must NOT fail
  *     the connection. Every ciphertext byte the engine emits must eventually reach the peer once the peer drains, not be abandoned. The
  *     such a spin would exhaust its retry budget and drop the un-sent tail, so the peer would decrypt fewer bytes than were written.
  *   - Property (b) no blast radius: while connection 1 is backpressured (buffer full, bytes pending), a SECOND connection on the SAME driver
  *     whose small write fits its buffer must still complete. Such a spin would hold the one FIFO worker, so connection 2's engine op could not
  *     run until connection 1's spin ended, stalling connection 2 until the 15s framework timeout.
  *
  * Both run over REAL loopback socket pairs against the real poller (epoll on Linux, kqueue on macOS/BSD), with a tiny SO_RCVBUF on the peer
  * and SO_SNDBUF on the client, and a REAL BoringSSL engine post-handshake. A large plaintext write encrypts into a similarly large ciphertext
  * blob that is guaranteed to hit EAGAIN with bytes still pending. The peer is drained deterministically via the driver's own `awaitRead`
  * readiness plus a non-blocking `recvNow`, never a sleep, and the received ciphertext is decrypted with the server engine; the recovered
  * plaintext must equal the written payload exactly (the real-engine conservation check). The driver appends the pending ciphertext,
  * flushes what it can, arms writability, and re-submits the flush when the socket drains, so every emitted byte arrives and the second
  * connection is never starved.
  *
  * Gate: `assumePoller()` (real poller) and `TlsRealEngines.assumeTlsReady()` (a real BoringSSL/OpenSSL engine).
  *
  * Uses real BoringSSL engines via `TlsRealEngines.singleEngine`, handshaked and exercised on the driver's engine FIFO. The received bytes
  * are decrypted and compared against the known plaintext rather than checked by count alone.
  */
class PollerIoDriverWriteBackpressureTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Build a connected loopback socket pair (client, accepted), both non-blocking after connect. Sets a small SO_SNDBUF on the client and a
      * small SO_RCVBUF on both the listen socket (inherited by the accepted socket) and the accepted socket directly, so a large send fills the
      * buffers and returns EAGAIN well before the whole blob is delivered. The exact buffer size the kernel grants is not asserted (kernels
      * round and double the request); the blob is sized far larger than any plausible small buffer so EAGAIN is guaranteed.
      */
    private def smallBufferedPair(sndBuf: Int, rcvBuf: Int)(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            // Small SO_RCVBUF on the listen socket so the accepted socket inherits it.
            setIntSockOpt(server, PosixConstants.SO_RCVBUF, rcvBuf)
            assert(sock.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sock.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            // Small SO_SNDBUF on the client so its send buffer fills fast too.
            setIntSockOpt(client, PosixConstants.SO_SNDBUF, sndBuf)
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sock.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    // Also set SO_RCVBUF directly on the accepted socket to be robust across kernels that do not inherit it.
                    setIntSockOpt(accepted, PosixConstants.SO_RCVBUF, rcvBuf)
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set_nonblocking(accepted) failed")
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end smallBufferedPair

    /** Set a 4-byte int socket option (SO_SNDBUF / SO_RCVBUF) little-endian. Failures are non-fatal: a kernel that rejects the size still
      * caps the buffer, and the oversized blob guarantees EAGAIN regardless of the exact granted size.
      */
    private def setIntSockOpt(fd: Int, optname: Int, value: Int)(using AllowUnsafe): Unit =
        val opt = Buffer.alloc[Byte](4)
        opt.set(0, (value & 0xff).toByte)
        opt.set(1, ((value >> 8) & 0xff).toByte)
        opt.set(2, ((value >> 16) & 0xff).toByte)
        opt.set(3, ((value >> 24) & 0xff).toByte)
        try discard(sock.setsockopt(fd, PosixConstants.SOL_SOCKET, optname, opt, 4))
        finally opt.close()
        end try
    end setIntSockOpt

    /** Complete the in-memory handshake for `client`/`server` ON the driver's engine FIFO worker so both sessions are created, handshaked, and
      * later used (client encrypt + server decrypt) on the same carrier; a cross-carrier real BoringSSL session corrupts. Returns a promise.
      */
    private def handshakeOnDriver(driver: PollerIoDriver, client: TlsEngine, server: TlsEngine)(using
        AllowUnsafe
    ): Promise.Unsafe[Boolean, Any] =
        val done = Promise.Unsafe.init[Boolean, Any]()
        driver.submitEngineOp(() => done.completeDiscard(Result.succeed(TlsEngineLoopback.handshake(client, server))))
        done
    end handshakeOnDriver

    /** Decrypt one ciphertext batch with `server` ON the driver's engine FIFO worker and return the recovered plaintext via a promise. */
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

    /** Read ciphertext from the peer `handle` via the driver's readiness, decrypting it with `serverEngine` on the FIFO worker, until `want`
      * plaintext bytes are recovered. The driver's dispatch on the engine-less peer handle is the SINGLE reader of the peer fd (a separate recvNow
      * would split the byte stream with the driver's recvNow and corrupt the TLS record order). Each awaitRead delivers the next in-order ciphertext
      * batch, decrypted on the FIFO; draining frees buffer space so the write side re-flushes. No sleeps. The loop exits on recovered >= want.
      */
    private def drainAndDecrypt(driver: PollerIoDriver, handle: PosixHandle, serverEngine: TlsEngine, want: Int)(
        using Frame
    ): Array[Byte] < (Abort[Closed] & Async) =
        val recovered = scala.collection.mutable.ArrayBuffer.empty[Byte]
        def loop(steps: Int): Array[Byte] < (Abort[Closed] & Async) =
            if recovered.size >= want then recovered.toArray
            else if steps > want + 256 then recovered.toArray // safety bound; the conservation assertion catches a shortfall
            else
                val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, promise)
                promise.safe.get.map {
                    case ReadOutcome.Bytes(delivered) =>
                        decryptOnDriver(driver, serverEngine, delivered.toArray).safe.get.map(plain => recovered ++= plain).map(_ =>
                            loop(steps + 1)
                        )
                    case _ => recovered.toArray
                }
            end if
        end loop
        loop(0)
    end drainAndDecrypt

    "PollerIoDriver TLS write backpressure" - {
        "property (a): every emitted ciphertext byte reaches a slow peer (no spurious teardown)" in {
            assumePoller()
            TlsRealEngines.assumeTlsReady()
            // 256 KB plaintext, far larger than the ~2 KB buffers: the real engine encrypts it into a similarly large ciphertext blob the flush
            // cannot drain in one pass.
            val plaintext    = Array.tabulate[Byte](256 * 1024)(i => ((i % 251) + 1).toByte)
            val clientEngine = TlsRealEngines.singleEngine(isServer = false)
            val serverEngine = TlsRealEngines.singleEngine(isServer = true)
            val driver       = PollerIoDriver.init(kyo.net.TransportConfig.default)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                smallBufferedPair(sndBuf = 2048, rcvBuf = 2048).map { case (client, accepted) =>
                    val clientH = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    clientH.tls = Present(clientEngine)
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    Async.timeout(14.seconds) {
                        for
                            // Handshake the engines on the FIFO worker so the client encrypt and server decrypt both run on one carrier.
                            handshakeDone <- handshakeOnDriver(driver, clientEngine, serverEngine).safe.get
                            _ = assert(handshakeDone, "the in-memory handshake must complete before the write")
                            // The write returns Done immediately (the pump never sees backpressure); the FIFO encrypts, appends the
                            // ciphertext, flushes until EAGAIN, then arms writability. A retry loop would exhaust and abandon the un-sent tail.
                            w <- Sync.defer(driver.write(clientH, Span.fromUnsafe(plaintext), 0))
                            _ = assert(w == WriteResult.Done, s"TLS write should return Done, got $w")
                            // Drain the peer and decrypt until the full plaintext is recovered. A dropping spin would never recover the whole payload
                            // (bytes dropped) so the 14s bound trips; here every byte arrives as the flush re-arms and re-submits.
                            got <- drainAndDecrypt(driver, acceptedH, serverEngine, plaintext.length)
                        yield
                            // Free the engines on the FIFO worker (closeHandle routes the client free; the server free is submitted), then the fds.
                            driver.submitEngineOp(() => serverEngine.free())
                            driver.closeHandle(clientH)
                            discard(sock.close(accepted))
                            discard(assert(
                                got.length == plaintext.length,
                                s"slow peer recovered ${got.length} of ${plaintext.length} written plaintext bytes"
                            ))
                            assert(
                                got.sameElements(plaintext),
                                "the decrypted peer bytes must equal the written plaintext exactly (no drop/dup/reorder)"
                            )
                        end for
                    }
                }
            }
        }

        "property (b): a second connection on the same driver is not starved while the first is backpressured (no blast radius)" in {
            assumePoller()
            TlsRealEngines.assumeTlsReady()
            val bigPlain   = Array.tabulate[Byte](256 * 1024)(i => ((i % 251) + 1).toByte)
            val smallPlain = Array.fill[Byte](512)(7.toByte)
            // Two independent real engine pairs: one per connection, so each peer's ciphertext decrypts against its own server engine.
            val clientEngine1 = TlsRealEngines.singleEngine(isServer = false)
            val serverEngine1 = TlsRealEngines.singleEngine(isServer = true)
            val clientEngine2 = TlsRealEngines.singleEngine(isServer = false)
            val serverEngine2 = TlsRealEngines.singleEngine(isServer = true)
            val driver        = PollerIoDriver.init(kyo.net.TransportConfig.default)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                smallBufferedPair(sndBuf = 2048, rcvBuf = 2048).map { case (client1, accepted1) =>
                    smallBufferedPair(sndBuf = 65536, rcvBuf = 65536).map { case (client2, accepted2) =>
                        val client1H = PosixHandle.socket(client1, PosixHandle.DefaultReadBufferSize, Absent)
                        client1H.tls = Present(clientEngine1)
                        val client2H = PosixHandle.socket(client2, PosixHandle.DefaultReadBufferSize, Absent)
                        client2H.tls = Present(clientEngine2)
                        val accepted1H = PosixHandle.socket(accepted1, PosixHandle.DefaultReadBufferSize, Absent)
                        val accepted2H = PosixHandle.socket(accepted2, PosixHandle.DefaultReadBufferSize, Absent)
                        Async.timeout(14.seconds) {
                            for
                                // Handshake both pairs on the FIFO worker so every client encrypt and server decrypt runs on one carrier.
                                h1 <- handshakeOnDriver(driver, clientEngine1, serverEngine1).safe.get
                                h2 <- handshakeOnDriver(driver, clientEngine2, serverEngine2).safe.get
                                _ = assert(h1 && h2, "both in-memory handshakes must complete before the writes")
                                // Connection 1: backpressure it (256 KB into a 2 KB buffer). Returns Done; the FIFO appends + flushes to
                                // EAGAIN + arms. A retry loop would leave the FIFO worker stuck retrying connection 1's full buffer.
                                w1 <- Sync.defer(driver.write(client1H, Span.fromUnsafe(bigPlain), 0))
                                _ = assert(w1 == WriteResult.Done, s"connection 1 write should return Done, got $w1")
                                // Connection 2: a small write that fits in one flush pass. If the FIFO worker were held by connection 1's
                                // retry loop, this op could not run and connection 2's bytes would never go out, tripping the 14s bound.
                                w2 <- Sync.defer(driver.write(client2H, Span.fromUnsafe(smallPlain), 0))
                                _ = assert(w2 == WriteResult.Done, s"connection 2 write should return Done, got $w2")
                                // Assert connection 2 progressed (recovered its full small payload) WHILE connection 1 is still
                                // backpressured (we have not drained peer 1 yet). This completes promptly because connection 1's backpressure never holds the FIFO worker.
                                got2 <- drainAndDecrypt(driver, accepted2H, serverEngine2, smallPlain.length)
                                _ = assert(
                                    got2.sameElements(smallPlain),
                                    s"connection 2 recovered ${got2.length} of ${smallPlain.length} while connection 1 backpressured (blast radius)"
                                )
                                // Only NOW drain peer 1, proving connection 2 progressed before connection 1's backpressure was relieved.
                                got1 <- drainAndDecrypt(driver, accepted1H, serverEngine1, bigPlain.length)
                            yield
                                // Free the engines on the FIFO worker (closeHandle routes each client free; the server frees are submitted), then fds.
                                driver.submitEngineOp(() => serverEngine1.free())
                                driver.submitEngineOp(() => serverEngine2.free())
                                driver.closeHandle(client1H)
                                driver.closeHandle(client2H)
                                discard(sock.close(accepted1))
                                discard(sock.close(accepted2))
                                assert(
                                    got1.sameElements(bigPlain),
                                    s"connection 1 recovered ${got1.length} of ${bigPlain.length} after drain"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

end PollerIoDriverWriteBackpressureTest
