package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines

/** Deterministic reproduction + regression guard for the dispatchRead-vs-close use-after-free race in [[PollerIoDriver]].
  *
  * The poll loop's `dispatchRead` acquires `beginDispatch` to own the handle's resources, then calls `recvNow` synchronously (the fd is
  * O_NONBLOCK so recv never parks). The decrypt then runs on the engine FIFO worker: `feedCiphertext` then `decryptAll`. If `closeHandle` runs
  * concurrently from another fiber while that body is in flight, it must DEFER the resource free until `endDispatch` releases the guard. The race
  * is forced deterministically by re-entrancy: a [[RecordingTlsEngine]] over a real BoringSSL engine fires `closeHandle` on its `onFeedCiphertext`
  * one-shot hook (the exact moment the dispatch guard is held), exactly as [[PollerIoDriverWriteRaceTest]] does for the write-path.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair for real non-blocking recvNow) and `TlsRealEngines.assumeTlsReady()` (a real
  * BoringSSL engine).
  *
  * C2 resolved: the peer must send REAL TLS ciphertext so `feedCiphertext` consumes valid bytes (raw plaintext would make a real engine error).
  * The server engine encrypts a known plaintext into one TLS record; the peer (clientFd) pre-sends that ciphertext BEFORE `driver.awaitRead`, so
  * the kernel buffers it. When the real backend fires the read event and the driver calls `recvNow`, it returns the real ciphertext immediately
  * (fd is non-blocking, data already buffered). The close-during-decrypt guard defers the free until `endDispatch`, so `decryptAll` runs on a live
  * engine and recovers the known plaintext; the readPromise resolves with it (or Closed if the dispatch bailed), never corrupt data, and the
  * engine is freed exactly once after the guard releases.
  *
  * Anti-flakiness: the engine handshakes in-memory via `TlsEngineLoopback.handshake` before the race; `onFeedCiphertext` is a one-shot re-entrant
  * latch fired while `beginDispatch` is held. `Async.timeout(5.seconds)` is the deadlock ceiling (the unfixed driver would hang), not the
  * synchronization primitive; `readPromise.safe.get` latches on the real dispatch completion; the pre-sent bytes guarantee `recvNow` returns
  * immediately, so the race fires on the first poll. Two `fifoBarrier`s after the dispatch settle the deferred free (a separate FIFO op). No sleep.
  *
  * Drives a real BoringSSL engine through a `RecordingTlsEngine` decorator and asserts `freeCount.get() == 1`, `!usedAfterFree`,
  * a terminal `readPromise`, and `spy.closeCounts == 1`.
  */
class PollerIoDriverRaceTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Submit a marker engine op and return a promise that completes when the FIFO worker runs it. Two in sequence settle a deferred free that a
      * close submits from inside an in-flight dispatch's endDispatch: the first completes after the dispatch op (which enqueues the free op), the
      * second after the free op itself. Deterministic, sleep-free.
      */
    private def fifoBarrier(driver: PollerIoDriver)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** ON the driver's engine FIFO worker: handshake `client`/`server`, encrypt `plaintext` with the server into one TLS record, free the server,
      * and return the ciphertext via a promise. Keeps both sessions single-carrier (the client is later read on the FIFO; the server is only used
      * here). A cross-carrier real BoringSSL session corrupts, so the handshake and encryption run on the same worker that later runs feedCiphertext.
      */
    private def handshakeAndEncrypt(driver: PollerIoDriver, client: TlsEngine, server: TlsEngine, plaintext: Array[Byte])(using
        AllowUnsafe
    ): Promise.Unsafe[Array[Byte], Any] =
        val done = Promise.Unsafe.init[Array[Byte], Any]()
        driver.submitEngineOp { () =>
            discard(TlsEngineLoopback.handshake(client, server))
            val cipher = TlsEngineLoopback.encrypt(server, plaintext)
            server.free()
            done.completeDiscard(Result.succeed(cipher))
        }
        done
    end handshakeAndEncrypt

    "PollerIoDriver dispatchRead vs close race" - {
        "a TLS engine freed by closeHandle is never used by an in-flight dispatchRead (use-after-free guard)" in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeTlsReady()
            // The DRIVER frees the client engine via closeHandle, so it is created with singleEngine (no auto-free finally that would double-free
            // the native session). The server engine encrypts the peer's ciphertext on the FIFO worker, then is freed there; the handshake +
            // encryption run on the SAME worker that later runs feedCiphertext, so the sessions stay single-carrier.
            val clientEngine = TlsRealEngines.singleEngine(isServer = false)
            val serverEngine = TlsRealEngines.singleEngine(isServer = true)
            val knownPlain   = Array[Byte](1, 2, 3, 4)
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val targetFd = acceptedFd
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])

                var driverRef: PollerIoDriver = null
                var handleRef: PosixHandle    = null

                val engine = new RecordingTlsEngine(clientEngine)
                engine.onFeedCiphertext = () =>
                    if driverRef != null && handleRef != null then driverRef.closeHandle(handleRef)

                // Real epoll/kqueue: once the accepted fd has the buffered ciphertext, the first poll after awaitRead registers read interest and
                // fires a real read-ready event. The spy delegates poll to the real backend (no synthetic events).
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                driverRef = driver
                val handle = PosixHandle.socket(targetFd, PosixHandle.DefaultReadBufferSize, Absent)
                handle.tls = Present(engine)
                handleRef = handle

                // Handshake + encrypt the peer's record on the FIFO worker (submitEngineOp runs independently of the poll loop), then pre-send the
                // REAL TLS ciphertext from the client so the kernel buffers it before awaitRead. When the poll loop fires recvNow on acceptedFd it
                // returns these bytes immediately (no parking) and feedCiphertext consumes a valid record.
                handshakeAndEncrypt(driver, clientEngine, serverEngine, knownPlain).safe.get.map { ciphertext =>
                    val preSendBuf = Buffer.fromArray[Byte](ciphertext)
                    discard(Ffi.load[SocketBindings].sendNow(clientFd, preSendBuf, ciphertext.length.toLong, PosixConstants.MSG_NOSIGNAL))
                    preSendBuf.close()

                    discard(driver.start())
                    val readPromise = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                    driver.awaitRead(handle, readPromise)

                    // The dispatch runs on the FIFO worker: recvNow returns the real ciphertext (pre-buffered), feedCiphertext fires closeHandle (the
                    // race), decryptAll runs on the live engine and recovers knownPlain, finishDispatch delivers it, endDispatch releases the guard and
                    // submits the deferred free. The readPromise resolves within the bounded window.
                    Abort.run[Timeout | Closed](Async.timeout(5.seconds)(readPromise.safe.get)).map { outcome =>
                        // Settle the deferred free (a separate FIFO op submitted at endDispatch).
                        fifoBarrier(driver).safe.get.andThen(fifoBarrier(driver).safe.get).map { _ =>
                            driver.close()
                            // Close the client fd (driver.close does not close socket fds; closeHandle closed acceptedFd via spy).
                            PosixTestSockets.closePeerForEof(spy, clientFd)
                            assert(
                                engine.freeCount.get() == 1,
                                s"closeHandle must free the engine exactly once, was ${engine.freeCount.get()}"
                            )
                            assert(
                                !engine.usedAfterFree.get(),
                                "use-after-free: dispatchRead touched the engine after closeHandle freed it"
                            )
                            // Real close recorded: closeHandle called sockets.close(targetFd) exactly once.
                            assert(
                                spy.closeCounts.getOrDefault(targetFd, 0) == 1,
                                s"closeHandle must have closed targetFd=$targetFd exactly once, counts=${spy.closeCounts}"
                            )
                            outcome match
                                // genuine close-during-decrypt race; read may resolve Success or Closed, the free-once/no-UAF invariant is pinned unconditionally above
                                case Result.Failure(_: Closed) => succeed
                                case Result.Failure(_: Timeout) =>
                                    fail("the dispatch did not complete within the timeout (read promise stranded)")
                                case Result.Success(span) =>
                                    // The dispatch ran on a LIVE engine (free deferred), so it correctly recovers the known plaintext (or delivers
                                    // an empty span if it bailed before decrypting). Either proves no use-after-free; corrupt data is the failure.
                                    assert(
                                        span.isEmpty || span.toArray.sameElements(knownPlain),
                                        s"dispatch on a live engine must recover the known plaintext, got ${span.toArray.toList}"
                                    )
                                case other => fail(s"unexpected dispatch outcome: $other")
                            end match
                        }
                    }
                }
            }
        }
    }

end PollerIoDriverRaceTest
