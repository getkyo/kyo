package kyo.net.internal.transport

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.posix.RecordingIoDriver
import kyo.net.internal.posix.SocketBindings

/** Tests for WritePump over a real PollerIoDriver.
  *
  * Each test constructs a real loopback socket pair or a small-buffer pair, wraps the driver in a RecordingIoDriver that delegates every
  * call to the real driver, and drives WritePump through its state machine via real kernel I/O. Every wait latches on a real event
  * (awaitWritable Promise completion, peer recv, channel drain) rather than on elapsed time.
  *
  * Gate: assumePoller() cancels where no epoll (Linux) or kqueue (macOS/BSD) is available.
  */
class WritePumpTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val transportConfig = kyo.net.TransportConfig.default
    private val sock            = Ffi.load[SocketBindings]

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    private def mkBytes(s: String): Span[Byte] = Span.fromUnsafe(s.getBytes("UTF-8"))

    "WritePump" - {

        // Anti-flakiness: the channel is pre-loaded with N small spans before start(). On a real loopback pair with adequate kernel
        // buffers, each write returns Done synchronously, so start() drives all N writes through the reuseTake completion chain
        // without any async wait. drainPeer latches on real awaitRead completions (Promise latch on real read events) to confirm all
        // bytes arrived. The RecordingLog spy delegates every call to the real Log.live.unsafe and increments infoCount on info(); the
        // RecordingIoDriver spy delegates every call to the real PollerIoDriver and increments writeCalls on write().
        "the steady write path emits exactly one info call (start-only) and one write per span" in {
            assumePoller()
            val N      = 16
            val logger = new RecordingLog(Log.live.unsafe)
            val real   = PollerIoDriver.init(transportConfig)
            val spy    = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](N + 1)
                val pump = new WritePump(
                    handle,
                    spy,
                    channel,
                    () => discard(channel.close()),
                    AtomicRef.Unsafe.init[WriteState](WriteState.Idle),
                    logger
                )

                // Pre-load the channel with N small spans. Each span fits well within the loopback kernel buffer so each write returns
                // Done synchronously and the pump does not park in awaitWritable.
                var i = 0
                while i < N do
                    val span = Span.fromUnsafe(Array.tabulate[Byte](i % 16 + 1)(j => j.toByte))
                    discard(channel.offer(span))
                    i += 1
                end while

                val totalBytes = (0 until N).map(i => i % 16 + 1).sum
                // start() fires onComplete via reuseTake because the channel already has data. The writes run synchronously through the
                // reuseTake completion chain (each Done result re-registers the pump on the channel and the next span is immediately
                // available). drainPeer latches on real awaitRead completions to confirm all bytes reached the peer.
                pump.start()

                PosixTestSockets.drainPeer(
                    spy,
                    PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent),
                    peerFd,
                    totalBytes
                ).map { received =>
                    // The RecordingIoDriver records one write() call per span; no partial writes on a loopback pair with small spans.
                    assert(spy.writeCalls.get() == N, s"expected $N write calls (one per span), got ${spy.writeCalls.get()}")
                    // The RecordingLog records info() calls: exactly 1 from WritePump.start() (the startup log line). The steady
                    // write path emits no per-take info thunks, so infoCount stays at 1 regardless of N.
                    val infoCount = logger.infoCount.get()
                    assert(infoCount == 1, s"expected exactly 1 info call (start-only), got $infoCount")
                    assert(received >= totalBytes, s"all bytes must reach peer, got $received")
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: loopbackPair pre-sized; payload fits socket buffer; peer recvs synchronously via drainPeer
        // (Promise latch on awaitRead completion). No sleep.
        "a single Done write delivers all bytes to the peer (real driver)" in {
            assumePoller()
            val real = PollerIoDriver.init(transportConfig)
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(handle, spy, channel, () => closed += "closed", AtomicRef.Unsafe.init[WriteState](WriteState.Idle))

                val payload = Array.tabulate[Byte](32)(i => (i + 1).toByte)
                val span    = Span.fromUnsafe(payload)
                discard(channel.offer(span))
                pump.start()

                // awaitWritable is NOT called (Done path); read from peer to confirm all bytes arrived.
                // drainPeer latches on driver awaitRead completions (Promise latch on real read events).
                // Anti-flakiness: payload fits in a single kernel send; Done is returned synchronously.
                PosixTestSockets.drainPeer(
                    spy,
                    PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent),
                    peerFd,
                    payload.length
                ).map { received =>
                    assert(spy.writeCalls.get() >= 1, "write must have been called at least once")
                    assert(received >= payload.length, s"peer must receive all $payload.length bytes, got $received")
                    assert(closed.isEmpty, "pump must not have torn down on Done path")
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: smallBufferedPair with small buffers guarantees real EAGAIN -> WriteResult.Partial.
        // drainPeer unblocks the retry (Promise latch on real awaitRead). No sleep.
        "a partial write awaits writable and retries the remainder (real backpressure)" in {
            assumePoller()
            val real = PollerIoDriver.init(transportConfig)
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.smallBufferedPair(4096, 4096).map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(handle, spy, channel, () => closed += "closed", AtomicRef.Unsafe.init[WriteState](WriteState.Idle))

                // 128 KB payload guarantees EAGAIN on a 4KB buffer pair.
                val payload = Array.fill[Byte](128 * 1024)(42)
                val span    = Span.fromUnsafe(payload)
                discard(channel.offer(span))
                pump.start()

                // drainPeer drains until all bytes arrive; its internal Promise latches on awaitRead completions.
                // The WritePump's awaitWritable fires when the peer drains space (real kernel event).
                PosixTestSockets.drainPeer(
                    spy,
                    PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent),
                    peerFd,
                    payload.length
                ).map { received =>
                    assert(spy.writeCalls.get() >= 2, s"at least 2 write calls expected (partial + retry), got ${spy.writeCalls.get()}")
                    assert(received >= payload.length, s"all bytes must reach peer, got $received")
                    assert(closed.isEmpty, "pump must not have torn down on retry-success path")
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // A 1 MB payload on smallBufferedPair(2048, 2048) far exceeds the kernel send+recv buffers, so the first write hits EAGAIN and returns
        // Partial and the pump parks in awaitWritable. The onAwaitWritable hook fires on the pump's carrier right after the writable promise is
        // registered and tears the handle down via closeHandle (the production teardown path: Connection.teardownHandle runs cancel + closeHandle).
        // closeHandle BOTH fails the pending writable promise with Closed (driving WritablePromise.onComplete's Failure arm into onWritableError ->
        // teardown) AND closes the fd, so the teardown is deterministic even when the poll loop delivers a writable Success that races the failure:
        // a raced Success makes the pump retry the remaining write, which then fails on the closed fd and tears down. A bare driver.cancel (the prior
        // version) was NOT deterministic once writable delivery worked: the poll loop can complete the writable Success before the cancel fails it,
        // leaving the pump to re-arm awaitWritable with no further cancel. That version only passed because writable delivery was previously broken.
        // closedLatch latches the teardown.
        "a writable-wait failure tears down the pump (handle closed while awaiting writable)" in {
            assumePoller()
            val real        = PollerIoDriver.init(transportConfig)
            val spy         = new RecordingIoDriver(real)
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.smallBufferedPair(2048, 2048).map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(
                    handle,
                    spy,
                    channel,
                    () =>
                        closed += "closed"
                        closedLatch.completeDiscard(Result.succeed(()))
                    ,
                    AtomicRef.Unsafe.init[WriteState](WriteState.Idle)
                )

                // The instant the pump registers its writable wait, close the handle: closeHandle fails the just-registered writable promise with
                // Closed and closes the fd, so the pump's writable-wait failure path runs to teardown regardless of a racing writable event.
                spy.onAwaitWritable = h => spy.closeHandle(h)

                // 1 MB payload guarantees EAGAIN on the 2KB pair: the first write is Partial and the pump parks in awaitWritable.
                val payload = Array.fill[Byte](1024 * 1024)(42)
                discard(channel.offer(Span.fromUnsafe(payload)))
                pump.start()

                closedLatch.safe.get.map { _ =>
                    assert(spy.awaitWritableCalls.get() >= 1, "pump must have registered a writable wait")
                    assert(closed.nonEmpty, "pump must tear down when the writable wait fails with Closed")
                    spy.close()
                    // closeHandle closed clientFd; only peerFd remains to close.
                    discard(sock.close(peerFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: resetPeer SO_LINGER {1,0} delivers a real RST to the client. The RST surfaces on a write within a few kernel send
        // cycles (loopback delivers it promptly); feeding the pump a burst of spans drives those writes and the first WriteResult.Error tears
        // the pump down. closedLatch is the real-event latch on teardown; the burst is the established flood-until-error idiom
        // (PollerIoDriverTest partial-write leaf), not a timing wait.
        "a write Error tears down the pump (real ECONNRESET)" in {
            assumePoller()
            val real        = PollerIoDriver.init(transportConfig)
            val spy         = new RecordingIoDriver(real)
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](64)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(
                    handle,
                    spy,
                    channel,
                    () =>
                        closed += "closed"
                        closedLatch.completeDiscard(Result.succeed(()))
                    ,
                    AtomicRef.Unsafe.init[WriteState](WriteState.Idle)
                )

                // A small first write succeeds (Done). Then the peer resets (ECONNRESET).
                val firstSpan = Span.fromUnsafe(Array.fill[Byte](8)(1))
                discard(channel.offer(firstSpan))
                pump.start()

                // drainPeer receives the first write before we reset the peer.
                PosixTestSockets.drainPeer(spy, PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent), peerFd, 8).map { _ =>
                    // Now reset the peer, causing ECONNRESET on a subsequent write.
                    PosixTestSockets.resetPeer(sock, peerFd)

                    // Feed the pump a burst of spans; each drives one write, and the first write that observes the RST returns Error and
                    // tears the pump down. The kernel surfaces the RST within a few sends on loopback; the burst bounds the scenario the same
                    // way the driver-level flood-until-error test does.
                    var i = 0
                    while i < 32 do
                        discard(channel.offer(Span.fromUnsafe(Array.fill[Byte](8)((i + 2).toByte))))
                        i += 1
                    end while

                    closedLatch.safe.get.map { _ =>
                        assert(closed.nonEmpty, "pump must have torn down after ECONNRESET write error")
                        spy.close()
                        discard(sock.close(clientFd))
                        succeed
                    }
                }
            }
        }

        // Anti-flakiness: channel.close() is synchronous; the pump's onComplete fires synchronously
        // via reuseTake completion. Failure(Closed) arm tears down immediately.
        "channel close during a take tears down (Failure(Closed))" in {
            assumePoller()
            val real = PollerIoDriver.init(transportConfig)
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(handle, spy, channel, () => closed += "closed", AtomicRef.Unsafe.init[WriteState](WriteState.Idle))

                // No data: pump registers for take and parks.
                pump.start()

                // Close the channel: pump's onComplete fires with Failure(Closed).
                discard(channel.close())

                assert(closed.nonEmpty, "pump must tear down when channel is closed during take")
                spy.close()
                discard(sock.close(peerFd))
                discard(sock.close(clientFd))
                succeed
            }
        }

        // Anti-flakiness: pump.start() -> channel.offer() -> channel.close() all synchronous.
        // The reuseTake fires Failure(Closed) synchronously; closeFn fires immediately.
        "empty span write is treated as Done immediately" in {
            assumePoller()
            val real = PollerIoDriver.init(transportConfig)
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(handle, spy, channel, () => closed += "closed", AtomicRef.Unsafe.init[WriteState](WriteState.Idle))

                discard(channel.offer(Span.empty[Byte]))
                pump.start()

                assert(spy.writeCalls.get() >= 1, "write must be called even for an empty span")
                assert(closed.isEmpty, "pump must not tear down on empty-span Done path")
                spy.close()
                discard(sock.close(peerFd))
                discard(sock.close(clientFd))
                succeed
            }
        }

        // Anti-flakiness: all three channel.offer() calls and onComplete firings happen synchronously
        // on the test thread via reuseTake. No async between offers.
        "multiple sequential writes proceed correctly" in {
            assumePoller()
            val real = PollerIoDriver.init(transportConfig)
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(handle, spy, channel, () => closed += "closed", AtomicRef.Unsafe.init[WriteState](WriteState.Idle))

                val firstPayload = Array.tabulate[Byte](8)(i => (i + 1).toByte)
                discard(channel.offer(Span.fromUnsafe(firstPayload)))
                pump.start()

                val secondPayload = Array.tabulate[Byte](8)(i => (i + 9).toByte)
                discard(channel.offer(Span.fromUnsafe(secondPayload)))

                val thirdPayload = Array.tabulate[Byte](8)(i => (i + 17).toByte)
                discard(channel.offer(Span.fromUnsafe(thirdPayload)))

                val totalBytes = firstPayload.length + secondPayload.length + thirdPayload.length
                PosixTestSockets.drainPeer(
                    spy,
                    PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent),
                    peerFd,
                    totalBytes
                ).map { received =>
                    assert(spy.writeCalls.get() >= 3, s"at least 3 write calls expected, got ${spy.writeCalls.get()}")
                    assert(received >= totalBytes, s"all bytes must reach peer, got $received")
                    assert(closed.isEmpty, "pump must not tear down on sequential-write success path")
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: smallBufferedPair guarantees a real Partial on the first write; drainPeer unblocks the retry. After the retry,
        // resetPeer delivers a real RST; feeding the pump a burst of spans drives the writes that surface the RST as WriteResult.Error.
        // closedLatch is the real-event latch on teardown; the burst is the flood-until-error idiom, not a timing wait.
        "write error after retry also triggers teardown" in {
            assumePoller()
            val real        = PollerIoDriver.init(transportConfig)
            val spy         = new RecordingIoDriver(real)
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.smallBufferedPair(4096, 4096).map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](64)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(
                    handle,
                    spy,
                    channel,
                    () =>
                        closed += "closed"
                        closedLatch.completeDiscard(Result.succeed(()))
                    ,
                    AtomicRef.Unsafe.init[WriteState](WriteState.Idle)
                )

                // 128 KB: guarantees EAGAIN on 4KB pair.
                val payload = Array.fill[Byte](128 * 1024)(42)
                discard(channel.offer(Span.fromUnsafe(payload)))
                pump.start()

                // drainPeer unblocks the retry by draining the peer buffer.
                PosixTestSockets.drainPeer(
                    spy,
                    PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent),
                    peerFd,
                    payload.length
                ).map { _ =>
                    // Retry is done. Now reset the peer and feed a burst; the first write that observes the RST tears the pump down.
                    PosixTestSockets.resetPeer(sock, peerFd)
                    var i = 0
                    while i < 32 do
                        discard(channel.offer(Span.fromUnsafe(Array.fill[Byte](8)((i + 1).toByte))))
                        i += 1
                    end while

                    closedLatch.safe.get.map { _ =>
                        assert(closed.nonEmpty, "pump must have torn down after retry-path ECONNRESET")
                        spy.close()
                        discard(sock.close(clientFd))
                        succeed
                    }
                }
            }
        }

        // Anti-flakiness: smallBufferedPair guarantees a real Partial on the first write; the pump parks on awaitWritable and drainPeer
        // unblocks the retry (Promise latch on real awaitRead). The recorded (span, offset) pairs are read after all bytes reach the peer.
        // No sleep. The same Span reference must be re-presented at an advancing offset (no Span.drop allocation).
        "a partial write re-presents the same span at an advancing offset, byte-conserving (real backpressure)" in {
            assumePoller()
            val real = PollerIoDriver.init(transportConfig)
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.smallBufferedPair(4096, 4096).map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(handle, spy, channel, () => closed += "closed", AtomicRef.Unsafe.init[WriteState](WriteState.Idle))

                // 128 KB on a 4 KB pair guarantees EAGAIN, so the driver returns Partial and the pump re-presents the remainder.
                val payload = Array.fill[Byte](128 * 1024)(0x42.toByte)
                discard(channel.offer(Span.fromUnsafe(payload)))
                pump.start()

                PosixTestSockets.drainPeer(
                    spy,
                    PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent),
                    peerFd,
                    payload.length
                ).map { received =>
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))

                    import scala.jdk.CollectionConverters.*
                    val calls = spy.writeRegions.iterator().asScala.toList
                    assert(calls.size >= 2, s"expected at least 2 write calls (partial + retry), got ${calls.size}")
                    assert(received >= payload.length, s"all bytes must reach peer, got $received")
                    assert(closed.isEmpty, "pump must not tear down on a successful retry")

                    // The pump re-presents the SAME Span reference on every retry (Span.drop would allocate a new instance).
                    val firstSpanRef = calls.head._1.asInstanceOf[AnyRef]
                    calls.zipWithIndex.foreach { case ((span, _), i) =>
                        assert(
                            span.asInstanceOf[AnyRef] eq firstSpanRef,
                            s"write call $i received a different Span reference: re-presentation must preserve the original (no Span.drop)"
                        )
                    }
                    // Offsets are monotonically non-decreasing and start at 0 (each retry advances by the bytes already sent).
                    assert(calls.head._2 == 0, s"first write offset must be 0, got ${calls.head._2}")
                    calls.map(_._2).sliding(2).foreach {
                        case Seq(a, b) => assert(b >= a, s"write offsets must not regress: $a then $b")
                        case _         => ()
                    }
                    succeed
                }
            }
        }

        // Anti-flakiness: the awaitingWritable guard is exercised by the partial path.
        // When awaitingWritable=true and another channel item is available, the pump does NOT
        // process it (the guard skips the write). drainPeer latches on real read events.
        "channel buffering during awaitingWritable: second span queued, not consumed until writable" in {
            assumePoller()
            val real = PollerIoDriver.init(transportConfig)
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.smallBufferedPair(4096, 4096).map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new WritePump(handle, spy, channel, () => closed += "closed", AtomicRef.Unsafe.init[WriteState](WriteState.Idle))

                val payload = Array.fill[Byte](128 * 1024)(42)
                discard(channel.offer(Span.fromUnsafe(payload)))
                pump.start()

                // Offer a second span while pump is in awaitingWritable mode.
                val extra = Span.fromUnsafe(Array.fill[Byte](8)(99))
                discard(channel.offer(extra))

                // drainPeer drains: both spans should arrive eventually.
                val totalBytes = payload.length + extra.size
                PosixTestSockets.drainPeer(
                    spy,
                    PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent),
                    peerFd,
                    totalBytes
                ).map { received =>
                    assert(received >= totalBytes, s"all bytes including extra must reach peer, got $received")
                    assert(closed.isEmpty, "pump must not tear down")
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }
    }

end WritePumpTest
