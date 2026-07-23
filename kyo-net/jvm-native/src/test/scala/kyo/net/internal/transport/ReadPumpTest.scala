package kyo.net.internal.transport

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.posix.RecordingIoDriver
import kyo.net.internal.posix.SocketBindings

/** Tests for ReadPump over a real PollerIoDriver.
  *
  * Every leaf wraps the real driver in a RecordingIoDriver and drives the pump through its state machine with real kernel I/O over a real
  * loopback pair. Each wait latches on a real event:
  *   - byte-delivery leaves latch on the `onAwaitRead` hook, which fires after `channel.offer(bytes)` and before the real re-registration, so
  *     the channel is guaranteed to hold the delivered data when the latch completes;
  *   - teardown leaves latch on the `closeFn` promise, completed when the pump tears down;
  *   - the backpressure leaf asserts the delivered bytes: a slow consumer over a capacity-1 channel forces repeated park/resume cycles, and
  *     the guarantee is that no byte is lost or reordered, so the park is observed through its behavioral consequence, not an internal hook.
  *
  * Gate: `assumePoller()` cancels where no epoll (Linux) or kqueue (macOS/BSD) is available.
  */
class ReadPumpTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val transportConfig = kyo.net.NetConfig.default
    private val sock            = Ffi.load[SocketBindings]

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    /** Send `bytes` to peerFd. */
    private def sendToPeer(peerFd: Int, bytes: Array[Byte])(using AllowUnsafe): Unit =
        val buf = kyo.ffi.Buffer.alloc[Byte](bytes.length)
        bytes.zipWithIndex.foreach { case (b, i) => buf.set(i, b) }
        discard(sock.send(peerFd, buf, bytes.length.toLong, 0))
        buf.close()
    end sendToPeer

    /** One-shot latch on the next `spy.awaitRead` call.
      * Anti-flakiness: fires AFTER channel.offer(bytes) in offerToChannel, before real awaitRead.
      * When the returned promise completes, channel is guaranteed to have the delivered data.
      */
    private def awaitNextRead(spy: RecordingIoDriver)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val latch = Promise.Unsafe.init[Unit, Any]()
        spy.onAwaitRead = () => latch.completeDiscard(Result.succeed(()))
        latch
    end awaitNextRead

    /** Send `payload` to `peerFd` in pieces, retrying on a non-blocking `EAGAIN` (send returns <= 0 when the kernel send buffer is full). A slow
      * consumer backpressures the pump, which fills the kernel recv buffer, which fills this send buffer; yielding on EAGAIN lets the consumer
      * drain so the send resumes. Completes once every byte is handed to the kernel.
      */
    private def sendAll(peerFd: Int, payload: Array[Byte])(using Frame): Unit < Async =
        Loop(0) { sent =>
            if sent >= payload.length then Loop.done(())
            else
                Sync.defer {
                    val len = math.min(16 * 1024, payload.length - sent)
                    val buf = kyo.ffi.Buffer.alloc[Byte](len)
                    var i   = 0
                    while i < len do
                        buf.set(i, payload(sent + i)); i += 1
                    val n = sock.sendNow(peerFd, buf, len.toLong, 0).value
                    buf.close()
                    n
                }.map { n =>
                    if n > 0 then Loop.continue(sent + n.toInt)
                    else Async.sleep(1.millis).andThen(Loop.continue(sent))
                }
        }
    end sendAll

    /** Drain `n` bytes from the inbound channel, concatenated in delivery order. */
    private def drainAll(channel: Channel.Unsafe[Span[Byte]], n: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= n then Loop.done(acc)
            else channel.safe.take.map(span => Loop.continue(acc ++ span.toArray))
        }

    "ReadPump" - {

        // Anti-flakiness: onAwaitRead hook fires after channel.offer(bytes), before real awaitRead.
        // When latch completes, channel is guaranteed to have data.
        "bytes are forwarded to the channel (real driver)" in {
            assumePoller()
            val real = PollerIoDriver.init()
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump    = new ReadPump(handle, spy, channel, () => closed += "closed")

                pump.start()
                assert(spy.awaitReadCalls.get() == 1, "start must register first read")

                // Set hook BEFORE sending; fires when pump re-registers after data delivery.
                val deliveredLatch = awaitNextRead(spy)
                val payload        = "hello world".getBytes("UTF-8")
                sendToPeer(peerFd, payload)

                deliveredLatch.safe.get.map { _ =>
                    val polled = channel.poll()
                    assert(polled.isSuccess, s"channel must have received data, got $polled")
                    polled.getOrThrow match
                        case Present(span) =>
                            assert(span.toArray.sameElements(payload), "channel must contain exactly the sent bytes")
                        case Absent =>
                            fail("Expected data in channel but got Absent")
                    end match
                    assert(closed.isEmpty, "pump must not tear down on successful read")
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: closeFnLatch completes when teardown runs (which calls closeFn).
        // No re-registration on EOF; teardown fires inline.
        "EOF on empty span closes pump" in {
            assumePoller()
            val real        = PollerIoDriver.init()
            val spy         = new RecordingIoDriver(real)
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new ReadPump(
                    handle,
                    spy,
                    channel,
                    () =>
                        closed += "closed"
                        closedLatch.completeDiscard(Result.succeed(()))
                )

                pump.start()

                PosixTestSockets.closePeerForEof(sock, peerFd)

                // Wait for teardown (closeFn fires asynchronously on driver thread).
                closedLatch.safe.get.map { _ =>
                    assert(closed.nonEmpty, "pump must tear down on EOF")
                    spy.close()
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: two onAwaitRead latches confirm two sequential deliveries.
        "channel accepts offer and continues reading (sequential deliveries)" in {
            assumePoller()
            val real = PollerIoDriver.init()
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump    = new ReadPump(handle, spy, channel, () => closed += "closed")

                pump.start()

                val latch1 = awaitNextRead(spy)
                sendToPeer(peerFd, "chunk1".getBytes("UTF-8"))

                latch1.safe.get.map { _ =>
                    val latch2 = awaitNextRead(spy)
                    sendToPeer(peerFd, "chunk2".getBytes("UTF-8"))

                    latch2.safe.get.map { _ =>
                        assert(spy.awaitReadCalls.get() >= 3, "pump must have re-registered at least twice")
                        assert(closed.isEmpty, "pump must not tear down on sequential reads")
                        spy.close()
                        discard(sock.close(peerFd))
                        discard(sock.close(clientFd))
                        succeed
                    }
                }
            }
        }

        // A small inbound channel (capacity 1) plus a payload many readChunkSize chunks long forces the pump to park on a full channel
        // repeatedly while a slow consumer drains it one chunk at a time. The behavioral guarantee: backpressure loses no bytes and preserves
        // order across every park/resume cycle. The peer-send and the drain run concurrently so the kernel buffers cannot deadlock; the byte
        // pattern (i % 251) makes any reordering or loss observable. No park-observation hook is needed: the assertion is on the delivered bytes.
        "a slow consumer backpressures the pump with no byte loss and order preserved (park + resume)" in {
            assumePoller()
            val real = PollerIoDriver.init()
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](1)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump    = new ReadPump(handle, spy, channel, () => closed += "closed")
                val payload = Array.tabulate[Byte](128 * 1024)(i => (i % 251).toByte)

                pump.start()
                Async.zip(sendAll(peerFd, payload), drainAll(channel, payload.length)).map { case (_, got) =>
                    assert(got.length == payload.length, s"expected ${payload.length} bytes through backpressure, got ${got.length}")
                    assert(got.sameElements(payload), "backpressure must preserve every byte in order across park/resume cycles")
                    assert(closed.isEmpty, "pump must not tear down while delivering under backpressure")
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Closing the inbound channel while the pump is parked on a full channel must not tear the pump down a second time: the parked put
        // completes Failure(Closed) and the pump's backpressure callback treats that as a no-op (teardown is driven by the channel-close /
        // scope path, not the pump). The pump is driven into backpressure by prefilling the capacity-1 channel and delivering one real chunk;
        // a bounded settle lets that delivered read park before the channel is closed, then a second settle lets the closed putFiber's callback
        // run. The observable assertion: closeFn is never called by the pump on this path.
        "closing the inbound channel under backpressure does not tear the pump down" in {
            assumePoller()
            val real = PollerIoDriver.init()
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](1)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump    = new ReadPump(handle, spy, channel, () => closed += "closed")

                discard(channel.offer(Span.fromUnsafe("prefill".getBytes("UTF-8"))))
                pump.start()
                sendToPeer(peerFd, "overflow".getBytes("UTF-8"))

                // Deterministically gate on the pump having read "overflow", found the cap-1 channel full, and parked on the put
                // (pendingPuts == 1) before closing. This guarantees the close fails a PARKED put, exercising the backpressure-callback
                // Closed arm, rather than racing the read so the pump offers to an already-closed channel (the teardown path). A fixed
                // sleep left that race open under load. Closing then fails the parked put; the pump's backpressure callback treats Closed
                // as a no-op, so closeFn is never called on this path.
                assertEventually(Sync.defer(channel.pendingPuts().getOrElse(0) == 1)).andThen(Sync.defer(channel.close())).map { _ =>
                    assert(closed.isEmpty, "closing the channel under backpressure must not tear the pump down (the Closed arm is a no-op)")
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: closeFnLatch completes when teardown runs. Triggered by real ECONNRESET.
        "driver read failure causes teardown" in {
            assumePoller()
            val real        = PollerIoDriver.init()
            val spy         = new RecordingIoDriver(real)
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new ReadPump(
                    handle,
                    spy,
                    channel,
                    () =>
                        closed += "closed"
                        closedLatch.completeDiscard(Result.succeed(()))
                )

                pump.start()

                PosixTestSockets.resetPeer(sock, peerFd)

                closedLatch.safe.get.map { _ =>
                    assert(closed.nonEmpty, "pump must tear down on ECONNRESET read failure")
                    spy.close()
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: channel.close() before peer sends; channel.offer returns Failure(Closed) synchronously.
        // closeFnLatch fires when teardown completes.
        "channel closed before offer: Failure(Closed) causes teardown" in {
            assumePoller()
            val real        = PollerIoDriver.init()
            val spy         = new RecordingIoDriver(real)
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new ReadPump(
                    handle,
                    spy,
                    channel,
                    () =>
                        closed += "closed"
                        closedLatch.completeDiscard(Result.succeed(()))
                )

                pump.start()

                // Close channel before peer sends.
                discard(channel.close())

                // Send bytes; pump receives them, channel.offer fails -> teardown.
                sendToPeer(peerFd, "data".getBytes("UTF-8"))

                closedLatch.safe.get.map { _ =>
                    assert(closed.nonEmpty, "pump must tear down when channel is closed before offer")
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: onAwaitRead latches confirm each delivery; closedLatch fires when channel is drained.
        // Key assertion: both frames are readable BEFORE closedLatch fires (frames survive EOF).
        // sequence: deliver 2 frames (latches) -> assert frames in channel -> close peer (EOF) -> drain -> closedLatch
        "EOF preserves already-buffered bytes (Docker exec stderr-empty repro)" in {
            assumePoller()
            val real        = PollerIoDriver.init()
            val spy         = new RecordingIoDriver(real)
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new ReadPump(
                    handle,
                    spy,
                    channel,
                    () =>
                        closed += "closed"
                        closedLatch.completeDiscard(Result.succeed(()))
                )

                pump.start()

                val first  = "first-chunk!".getBytes("UTF-8")
                val second = "second-chunk".getBytes("UTF-8")

                // Deliver frame 1; latch fires when pump re-registers (frame is in channel).
                val latch1 = awaitNextRead(spy)
                sendToPeer(peerFd, first)

                latch1.safe.get.map { _ =>
                    // Frame 1 is in channel. Deliver frame 2.
                    val latch2 = awaitNextRead(spy)
                    sendToPeer(peerFd, second)

                    latch2.safe.get.map { _ =>
                        // Frame 2 is in channel. Both frames delivered; close peer (EOF).
                        PosixTestSockets.closePeerForEof(sock, peerFd)

                        // Assert both frames are readable NOW (before EOF processing completes).
                        val r1 = channel.poll()
                        val r2 = channel.poll()
                        assert(r1.isSuccess, s"first chunk must be readable, got $r1")
                        r1.getOrThrow match
                            case Present(span) =>
                                assert(span.toArray.sameElements(first), s"first chunk bytes mismatch: ${span.toArray.toList}")
                            case Absent => fail("first chunk must be Present")
                        end match
                        assert(r2.isSuccess, s"second chunk must be readable, got $r2")
                        r2.getOrThrow match
                            case Present(span) =>
                                assert(span.toArray.sameElements(second), s"second chunk bytes mismatch: ${span.toArray.toList}")
                            case Absent => fail("second chunk must be Present")
                        end match

                        // Wait for teardown (closedLatch fires after EOF processed + channel empty).
                        closedLatch.safe.get.map { _ =>
                            assert(closed.nonEmpty, "closeFn must fire after teardown")
                            spy.close()
                            discard(sock.close(clientFd))
                            succeed
                        }
                    }
                }
            }
        }

        // Anti-flakiness: closeFnLatch fires when teardown runs. channel.closed() is true after.
        "teardown closes channel and calls closeFn" in {
            assumePoller()
            val real        = PollerIoDriver.init()
            val spy         = new RecordingIoDriver(real)
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new ReadPump(
                    handle,
                    spy,
                    channel,
                    () =>
                        closed += "closed"
                        closedLatch.completeDiscard(Result.succeed(()))
                )

                pump.start()

                PosixTestSockets.closePeerForEof(sock, peerFd)

                closedLatch.safe.get.map { _ =>
                    assert(closed.nonEmpty, "closeFn must be called after teardown")
                    spy.close()
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        // Anti-flakiness: onAwaitRead latch fires when frame is in channel; then close peer (EOF).
        // Drain frame (consumer action) -> triggers closeAwaitEmpty to complete -> closedLatch fires.
        "EOF after data delivery causes teardown after consumer drains" in {
            assumePoller()
            val real        = PollerIoDriver.init()
            val spy         = new RecordingIoDriver(real)
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val channel = Channel.Unsafe.init[Span[Byte]](16)
                val closed  = scala.collection.mutable.ListBuffer[String]()
                val pump = new ReadPump(
                    handle,
                    spy,
                    channel,
                    () =>
                        closed += "closed"
                        closedLatch.completeDiscard(Result.succeed(()))
                )

                pump.start()

                val payload = "real data".getBytes("UTF-8")
                val latch   = awaitNextRead(spy)
                sendToPeer(peerFd, payload)

                latch.safe.get.map { _ =>
                    // Frame is in channel. Close peer (EOF).
                    PosixTestSockets.closePeerForEof(sock, peerFd)

                    // Drain the frame; this triggers closeAwaitEmpty completion -> closedLatch.
                    val polled = channel.poll()
                    assert(polled.isSuccess, s"channel must have buffered data, got $polled")
                    polled.getOrThrow match
                        case Present(span) =>
                            assert(span.toArray.sameElements(payload), "channel must contain the sent bytes")
                        case Absent =>
                            fail("Expected data in channel but got Absent")
                    end match

                    closedLatch.safe.get.map { _ =>
                        assert(closed.nonEmpty, "closeFn must fire after consumer drains")
                        spy.close()
                        discard(sock.close(clientFd))
                        succeed
                    }
                }
            }
        }
    }

end ReadPumpTest
