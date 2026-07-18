package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult
import kyo.test.AssertScope

/** Write CONSERVATION across back-to-back TLS writes on a single [[IoUringDriver]] handle, over a REAL io_uring ring and a REAL BoringSSL
  * engine.
  *
  * io_uring's send is asynchronous: [[IoUringDriver.writeTls]] enqueues the encrypt-then-send on the engine FIFO and returns Done immediately,
  * so [[kyo.net.internal.transport.WritePump]] issues the next write before the first send's CQE has reaped. The two writes drain in order on
  * the engine FIFO, then their send CQEs reap. The danger is the same one [[WriteBackpressureConservationTest]] pins for the poller, but caused
  * by the io_uring send being async: `pendingCipherSent` only advances when a send CQE reaps, so without a single-in-flight-send guard a second
  * `writeTls` whose flush runs while the first send's CQE is still outstanding re-sends the first write's still-unacknowledged ciphertext region,
  * putting `c1, c1 ++ c2` on the wire instead of `c1, c2`.
  *
  * The invariant under test is CONSERVATION: every plaintext byte of every write must reach the wire exactly once, in submission order, with
  * none duplicated, reordered, or stranded in `pendingCipher`. A reversible fake gave this for free by recording the exact ciphertext bytes; the
  * real-engine equivalent is DECRYPT-AND-COMPARE: the peer collects every byte the driver sent and decrypts it back, and the concatenated
  * plaintext must equal the writes concatenated in submission order. A duplicated or reordered ciphertext region would decrypt to a wrong or
  * duplicated plaintext (or fail to decrypt), so the equality is exactly the conservation assertion the fake's byte log made.
  *
  * Every leaf is gated by [[PosixTestSockets.assumeUring]] (cancel off Linux / where the production-depth ring cannot init; run the real ring on
  * native Linux).
  *
  * Anti-flakiness: the peer is collected by parking real recv reads through the driver (`awaitRead` on a peer handle), a real-event latch that
  * completes only when the kernel delivers bytes; the loop terminates on the decrypted-plaintext length reaching the expected total. No sleep or
  * poll-retry. The partial-first-send leaf forces a genuine kernel partial send via a shrunk SO_SNDBUF, exercising the reap-driven re-flush.
  */
class IoUringTlsWriteOrderingTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Allocate a real io_uring ring at production depth, wrap it in a recording spy, build a driver, run `body`, then close the driver. */
    private def withRecordingDriver[A](
        body: (IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("RecordingIoUringBindings", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withRecordingDriver

    /** Collect ciphertext from the peer socket, parking real recv reads through the driver until the decrypted plaintext reaches `want` bytes,
      * then return the decrypted plaintext in arrival order. Each `awaitRead` is a real-event latch that completes when the kernel delivers
      * bytes; feeding the collected ciphertext to the peer engine incrementally lets a TLS record that spans recv boundaries decrypt once it
      * is complete. The loop terminates on the conservation condition (decrypted length reached the total), never on a timer.
      */
    private def collectPlaintext(drv: IoUringDriver, peerHandle: PosixHandle, peerEngine: kyo.net.internal.TlsEngine, want: Int)(using
        Frame
    ): Array[Byte] < (Abort[Closed] & Async) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= want then Loop.done(acc)
            else
                val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                drv.awaitRead(peerHandle, p)
                p.safe.get.map {
                    case ReadOutcome.Bytes(chunk) =>
                        val more = TlsEngineLoopback.decrypt(peerEngine, chunk.toArray)
                        Loop.continue(acc ++ more)
                    case other =>
                        Loop.done(acc)
                }
        }
    end collectPlaintext

    /** Test-tree quiescence barrier for `handle`: completes once the handle is at rest (no submitted-but-unreaped op, and no TLS or raw send
      * SQE outstanding). The conservation assertions read settled accounting behind this: the driver's write is two-phase (the wire effect
      * happens on the kernel's own schedule; the local accounting, e.g. onTlsSendComplete resetting pendingCipherSent, runs only when that
      * send's CQE is reaped on a LATER reap cycle), so without an explicit barrier the assertions would race the reap carrier (#29).
      *
      * It samples inFlight/sendInFlight/rawSendInFlight through submitEngineOp (the engine FIFO is the single owner of that state, so the
      * sample cannot race the accounting it reads), and between samples parks on the recording spy's next-CQE-reap latch instead of a timer:
      * the only event that can change the answer is a send CQE reaping, so the next reap is exactly the settle signal (no sleep). The reap
      * latch is registered BEFORE each sample: a sample runs on the reap carrier, so any op it still sees in flight can only reap on a later
      * reap-carrier turn, which completes the already-registered latch; registering after the sample could miss a reap that fired in the gap.
      * Async.timeout is only the deadlock ceiling, so a handle that never quiesces fails the test loudly rather than hanging, and never
      * surfaces as a main-source Closed.
      */
    private def awaitQuiesced(drv: IoUringDriver, recording: RecordingIoUringBindings, handle: PosixHandle)(using
        Frame,
        AssertScope
    ): Unit < (Abort[Closed] & Async) =
        val settle =
            Loop.foreach {
                Sync.Unsafe.defer {
                    val reaped = recording.awaitReap()
                    val p      = Promise.Unsafe.init[Boolean, Abort[Closed]]()
                    drv.submitEngineOp { () =>
                        val quiescent =
                            Maybe(drv.inFlight.get(handle.id.packed)).getOrElse(0L) <= 0L &&
                                !handle.sendInFlight && !handle.rawSendInFlight
                        p.completeDiscard(Result.succeed(quiescent))
                    }
                    p.safe.get.map { quiescent =>
                        if quiescent then Loop.done
                        else reaped.safe.get.andThen(Loop.continue)
                    }
                }
            }
        Abort.run[Timeout | Closed](Async.timeout(30.seconds)(settle)).map {
            case Result.Success(_) => ()
            case Result.Failure(_: Timeout) =>
                fail(s"awaitQuiesced: handle ${handle.id} did not reach quiescence within the 30s deadlock ceiling")
            case other =>
                fail(s"awaitQuiesced: awaiting quiescence for handle ${handle.id} failed unexpectedly: $other")
        }
    end awaitQuiesced

    "IoUringDriver TLS write conservation across back-to-back writes (real ring, real engine)" - {

        "two back-to-back writeTls deliver each write's plaintext on the wire exactly once, in order, with no duplication" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the writes")
                withRecordingDriver { (drv, recording) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle     = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        val peerHandle = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(serverEngine)

                        val p1       = Array.tabulate[Byte](16)(i => (i + 1).toByte)
                        val p2       = Array.tabulate[Byte](32)(i => (i + 100).toByte)
                        val expected = (p1 ++ p2).toList

                        val w1 = drv.write(handle, Span.fromUnsafe(p1), 0)
                        assert(w1 == WriteResult.Done, s"write 1 result=$w1")
                        val w2 = drv.write(handle, Span.fromUnsafe(p2), 0)
                        assert(w2 == WriteResult.Done, s"write 2 result=$w2")

                        collectPlaintext(drv, peerHandle, clientEngine, expected.length).map { got =>
                            // collectPlaintext returning proves the WIRE effect (the peer decrypted every byte); it proves nothing about
                            // this driver's own ACCOUNTING of that send, which resets on a later reap cycle (onTlsSendComplete). Barrier
                            // first so the invariant below reads settled state instead of racing the reap carrier (#29).
                            awaitQuiesced(drv, recording, handle).map { _ =>
                                handle.tls = Absent
                                drv.closeHandle(handle)
                                drv.closeHandle(peerHandle)
                                assert(
                                    got.toList == expected,
                                    s"conservation: decrypted wire bytes must equal p1 ++ p2 once each in order.\n  expected (${expected.size}): $expected\n  got (${got.length}): ${got.toList}"
                                )
                                assert(
                                    handle.pendingCipher.isEmpty || handle.pendingCipherSent == 0,
                                    "no bytes may be stranded in pendingCipher after all sends reaped"
                                )
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "a partial first send re-flushes only the unsent remainder; no byte is dropped or re-sent" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the writes")
                withRecordingDriver { (drv, recording) =>
                    // Shrunk SO_SNDBUF on the driver side forces the first ciphertext send to partial-send genuinely; the driver re-flushes the
                    // unsent remainder when the send CQE reaps. Larger payloads ensure the ciphertext exceeds the small send buffer.
                    PosixTestSockets.smallBufferedPair(sndBuf = 2048, rcvBuf = 2048).map { case (driverFd, peerFd) =>
                        val handle     = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        val peerHandle = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(serverEngine)

                        val p1       = Array.tabulate[Byte](48 * 1024)(i => (i % 251).toByte)
                        val p2       = Array.tabulate[Byte](24 * 1024)(i => ((i + 7) % 251).toByte)
                        val expected = (p1 ++ p2).toList

                        val w1 = drv.write(handle, Span.fromUnsafe(p1), 0)
                        assert(w1 == WriteResult.Done, s"write 1 result=$w1")
                        val w2 = drv.write(handle, Span.fromUnsafe(p2), 0)
                        assert(w2 == WriteResult.Done, s"write 2 result=$w2")

                        collectPlaintext(drv, peerHandle, clientEngine, expected.length).map { got =>
                            // Barrier first: see the conservation leaf's comment above (#29).
                            awaitQuiesced(drv, recording, handle).map { _ =>
                                handle.tls = Absent
                                drv.closeHandle(handle)
                                drv.closeHandle(peerHandle)
                                assert(
                                    got.toList == expected,
                                    s"partial conservation: decrypted wire bytes must equal p1 ++ p2 once each in order (got ${got.length} of ${expected.size})"
                                )
                                assert(
                                    handle.pendingCipher.isEmpty || handle.pendingCipherSent == 0,
                                    "no bytes may be stranded in pendingCipher after all sends reaped"
                                )
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "three back-to-back writes preserve order and conservation across coalesced flushes" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the writes")
                withRecordingDriver { (drv, recording) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle     = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        val peerHandle = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(serverEngine)

                        val payloads = List(
                            Array.tabulate[Byte](8)(i => (i + 1).toByte),
                            Array.tabulate[Byte](20)(i => (i + 40).toByte),
                            Array.tabulate[Byte](12)(i => (i + 90).toByte)
                        )
                        val expected = payloads.flatten.toList

                        payloads.zipWithIndex.foreach { case (p, i) =>
                            val w = drv.write(handle, Span.fromUnsafe(p), 0)
                            assert(w == WriteResult.Done, s"write $i result=$w")
                        }
                        collectPlaintext(drv, peerHandle, clientEngine, expected.length).map { got =>
                            // Barrier first: see the conservation leaf's comment above (#29).
                            awaitQuiesced(drv, recording, handle).map { _ =>
                                handle.tls = Absent
                                drv.closeHandle(handle)
                                drv.closeHandle(peerHandle)
                                assert(
                                    got.toList == expected,
                                    s"three-write conservation: decrypted wire bytes must equal the three plaintexts concatenated in order (got ${got.length} of ${expected.size})"
                                )
                                assert(
                                    handle.pendingCipher.isEmpty || handle.pendingCipherSent == 0,
                                    "no bytes may be stranded in pendingCipher after all sends reaped"
                                )
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringTlsWriteOrderingTest
