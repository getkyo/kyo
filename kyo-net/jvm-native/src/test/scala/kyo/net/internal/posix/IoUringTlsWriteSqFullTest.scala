package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** SQ-full backpressure for a TLS write on an [[IoUringDriver]] handle, over a REAL io_uring ring: the TLS twin of
  * [[IoUringRawWriteSqFullTest]].
  *
  * A TLS write encrypts its plaintext into the handle's pending ciphertext tail and submits one send SQE on the reap carrier
  * ([[IoUringDriver.writeTls]] -> `flushTls`). When `kyo_uring_get_sqe` returns Absent the submission queue is full and nothing was submitted, so
  * there is no send CQE to re-drive the flush: the record sits in the tail with `sendInFlight` clear. The handle must therefore be parked for the
  * reap turn's re-flush, exactly as `flushRaw` parks a stalled raw send. It was not, and that is a permanent strand rather than a delay: only a
  * LATER write on the same handle would ever flush the tail again, so a peer in a request/response exchange (write once, then read) waits for a
  * record that was encrypted but never sent, until its deadline fires.
  *
  * That is the mechanism behind the intermittent, load-dependent io_uring hang in `TransportTlsConnectConcurrentTest`: under many simultaneous
  * connections the SQ genuinely fills, one echo response strands mid-flush, and its client blocks forever. It is io_uring-only (the nio and epoll
  * backends have no submission queue to fill) and it leaves no evidence behind, because the stranded op is unregistered from `pending` before the
  * strand, so the end-of-run stranded-op gate sees a clean driver.
  *
  * The SQ-full is forced STRUCTURALLY, never by timing: a real depth-1 ring has exactly one SQE, and a latch (released by the test, not a sleep)
  * pins the reap carrier so the filler read, the TLS write, and the peer read all enqueue and drain in ONE pass. The filler read consumes the one
  * SQE; the TLS write's `flushTls` then sees `get_sqe` return Absent. Releasing the latch lets the reap loop re-flush the parked send. The
  * invariant is proven end to end: the peer read delivers exactly the written payload, so the SQ-full TLS write was parked and re-flushed rather
  * than stranded.
  *
  * The engine is a local pass-through double whose "ciphertext" is the plaintext, so the peer can assert exact bytes without a TLS provider or a
  * handshake. What is under test is the driver's tail/flush bookkeeping, which is engine-independent.
  *
  * Gated by [[PosixTestSockets.assumeUring]] (cancels off Linux and where a real ring cannot init).
  */
class IoUringTlsWriteSqFullTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Pass-through TLS engine: the wire bytes ARE the plaintext, so a peer read can assert exact equality. `writePlain` stages the whole
      * plaintext and reports it consumed; `drainCiphertext` hands those staged bytes back once and then reports 0, so `encryptPlaintext`'s drain
      * loop terminates after one record.
      */
    private class PassThroughEngine extends TlsEngine:
        private var staged: Array[Byte] = Array.emptyByteArray

        override def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
            staged = Array.tabulate[Byte](len)(i => buf.get(i))
            len

        override def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
            val n = math.min(len, staged.length)
            var i = 0
            while i < n do
                buf.set(i, staged(i))
                i += 1
            end while
            staged = Array.emptyByteArray
            n
        end drainCiphertext

        override def handshakeStep()(using AllowUnsafe): Int                             = 1
        override def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
        override def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = 0
        override def hasBufferedPlaintext(using AllowUnsafe): Boolean                    = false
        override def readBuffered()(using AllowUnsafe): Span[Byte]                       = Span.empty
        override def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]                  = Absent
        override def shutdownStep()(using AllowUnsafe): Int                              = 0
        override def free()(using AllowUnsafe): Unit                                     = ()
    end PassThroughEngine

    /** Allocate a REAL io_uring ring at `depth`, build a driver over it with its reap loop started, run `body`, then close the driver. The reap
      * loop must run: the append+flush drains on the reap carrier and the stalled-send re-flush runs from the reap loop, so the SQ-full
      * backpressure under test only makes progress with the loop running.
      */
    private def withRealRingDriver[A](depth: Int)(
        body: IoUringDriver => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("IoUringTlsWriteSqFullTest", summon[Frame], s"queue_init failed: rc=$rc")
        val driver = TestDrivers.forBindings(realUring, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withRealRingDriver

    "IoUringDriver TLS write SQ-full backpressure (real ring)" - {

        "a TLS write whose flush hits SQ-full is parked and re-flushed (not stranded), delivering the record to the peer" in {
            PosixTestSockets.assumeUring()
            withRealRingDriver(1) { drv =>
                PosixTestSockets.loopbackPair().map { case (fillerClient, fillerAccepted) =>
                    PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                        val fillerH = PosixHandle.socket(fillerAccepted, PosixHandle.DefaultReadBufferSize, Absent)
                        val writeH  = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                        val peerH   = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                        writeH.tls = Present(new PassThroughEngine)
                        writeH.engineFreeSink = _ => ()
                        val gate  = new java.util.concurrent.CountDownLatch(1)
                        val pinIn = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        drv.submitEngineOp { () =>
                            pinIn.completeDiscard(Result.succeed(()))
                            gate.await()
                        }
                        pinIn.safe.get.map { _ =>
                            // Reap carrier pinned: all three ops enqueue behind it and drain together when it releases.
                            val fillerP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            drv.awaitRead(fillerH, fillerP) // consumes the one SQE, stays in flight (no peer bytes)
                            val payload = Span.fromUnsafe(Array.tabulate[Byte](48)(i => (i * 7 + 3).toByte))
                            val w       = drv.write(writeH, payload, 0)
                            val peerP   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            drv.awaitRead(peerH, peerP)
                            // Release the gate BEFORE asserting on the write result: a failed assertion must never leave the reap carrier
                            // parked (it would wedge every later test on this scheduler).
                            gate.countDown()
                            assert(w == WriteResult.Done, "writeTls encrypts and returns Done; the send runs on the reap carrier")
                            Abort.run[Closed](peerP.safe.get).map { result =>
                                writeH.tls = Absent
                                drv.closeHandle(fillerH)
                                drv.closeHandle(writeH)
                                drv.closeHandle(peerH)
                                discard(sock.close(fillerClient))
                                result match
                                    case Result.Success(ReadOutcome.Bytes(got)) =>
                                        assert(
                                            got.toArray.toList == payload.toArray.toList,
                                            s"the re-flushed SQ-full TLS write delivered wrong bytes: ${got.toArray.toList}"
                                        )
                                    case other =>
                                        fail(
                                            "a TLS write whose flush hit SQ-full must be parked and re-flushed to the peer, never stranded " +
                                                s"in the ciphertext tail with no send in flight: got $other"
                                        )
                                end match
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringTlsWriteSqFullTest
