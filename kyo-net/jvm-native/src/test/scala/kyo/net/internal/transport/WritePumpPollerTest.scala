package kyo.net.internal.transport

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.posix.RecordingIoDriver
import kyo.net.internal.posix.SocketBindings

/** WritePump over a real PollerIoDriver and kernel sockets, for what only the real driver can show: bytes reach the peer, a real park
  * resumes on real writability, a real reset and a real handle close tear the pump down.
  *
  * The kernel decides how much each send takes and when room opens, so no leaf asserts on write counts, on which write parks, or on
  * buffer sizes; each asserts only an outcome every kernel must reach. The pump's own transitions are pinned by WritePumpTest.
  */
class WritePumpPollerTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    // Lazy: Ffi.load opens the library, which exists only where assumePoller lets a leaf run.
    private lazy val sock = Ffi.load[SocketBindings]

    private def span(fill: Int, size: Int): Span[Byte] = Span.fromUnsafe(Array.fill[Byte](size)(fill.toByte))

    private def bytesOf(spans: Chunk[Span[Byte]]): Chunk[Byte] = Chunk.from(spans.toSeq.flatMap(_.toArray))

    /** Feeds `channel` until the pump closes it, which its teardown does. After a reset the client's kernel can still take a few writes
      * before it reports the RST, so no fixed burst is sure to reach it.
      */
    private def floodUntilClosed(channel: Channel.Unsafe[Span[Byte]]): Unit < Async =
        Abort.run[Closed](Loop.forever(channel.safe.put(span(7, 8)))).unit

    /** Offers 128 KiB spans to the started pump until it awaits writable, returning them in offer order, each filled with a distinct byte.
      * The peer never reads, so finite socket buffers make some write park on every kernel. An offer runs the pump's take and write
      * inline on this carrier, so when it returns the pump has either parked or gone back to Idle.
      */
    private def feedUntilParked(
        spy: RecordingIoDriver,
        pump: WritePump[PosixHandle],
        channel: Channel.Unsafe[Span[Byte]]
    )(using kyo.test.AssertScope): Chunk[Span[Byte]] =
        @scala.annotation.tailrec
        def loop(offered: Chunk[Span[Byte]]): Chunk[Span[Byte]] =
            if spy.awaitWritableCalls.get() > 0 then offered
            else
                assert(pump.current == WriteState.Idle, s"the pump must be Idle between inline writes, was ${pump.current}")
                val next = span(offered.size % 251 + 1, 128 * 1024)
                assert(channel.offer(next) == Result.succeed(true), "an offer to the pump's waiting take must be accepted")
                loop(offered.append(next))
        loop(Chunk.empty)
    end feedUntilParked

    private def newPump(
        driver: RecordingIoDriver,
        clientFd: Int,
        channel: Channel.Unsafe[Span[Byte]],
        closeFn: () => Unit
    ): (WritePump[PosixHandle], AtomicRef.Unsafe[WriteState]) =
        val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
        val state  = AtomicRef.Unsafe.init[WriteState](WriteState.Idle)
        (new WritePump(handle, driver, channel, closeFn, state), state)
    end newPump

    "WritePump over PollerIoDriver" - {

        "spans reach the peer in offer order" in {
            PosixTestSockets.assumePoller()
            val spy = new RecordingIoDriver(PollerIoDriver.init())
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val channel   = Channel.Unsafe.init[Span[Byte]](32)
                val teardowns = AtomicInt.Unsafe.init(0)
                val (pump, _) = newPump(spy, clientFd, channel, () => discard(teardowns.incrementAndGet()))
                val spans     = Chunk.from((1 to 16).map(i => span(i, i)))
                spans.foreach(s => assert(channel.offer(s) == Result.succeed(true)))
                pump.start()

                val expected = bytesOf(spans)
                PosixTestSockets.drainCollect(spy, peerFd, expected.size).map { received =>
                    assert(received == expected, "the peer must receive every span's bytes in offer order")
                    assert(teardowns.get() == 0)
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        "a pump parked on a full socket resumes as the peer drains and delivers every byte in order" in {
            PosixTestSockets.assumePoller()
            val spy = new RecordingIoDriver(PollerIoDriver.init())
            discard(spy.start())
            PosixTestSockets.smallBufferedPair(4096, 4096).map { case (clientFd, peerFd) =>
                val channel   = Channel.Unsafe.init[Span[Byte]](16)
                val teardowns = AtomicInt.Unsafe.init(0)
                val (pump, _) = newPump(spy, clientFd, channel, () => discard(teardowns.incrementAndGet()))
                pump.start()
                val spans    = feedUntilParked(spy, pump, channel)
                val expected = bytesOf(spans)

                PosixTestSockets.drainCollect(spy, peerFd, expected.size).map { received =>
                    assert(received.size == expected.size, s"the peer must receive ${expected.size} bytes, got ${received.size}")
                    assert(received == expected, "the parked span's tail must follow its head, after every earlier span")
                    assert(teardowns.get() == 0)
                    spy.close()
                    discard(sock.close(peerFd))
                    discard(sock.close(clientFd))
                    succeed
                }
            }
        }

        "a real peer reset surfaces as a write Error and tears the pump down" in {
            PosixTestSockets.assumePoller()
            val spy         = new RecordingIoDriver(PollerIoDriver.init())
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                val channel       = Channel.Unsafe.init[Span[Byte]](64)
                val (pump, state) = newPump(spy, clientFd, channel, () => closedLatch.completeDiscard(Result.succeed(())))
                val first         = span(1, 8)
                assert(channel.offer(first) == Result.succeed(true))
                pump.start()

                PosixTestSockets.drainCollect(spy, peerFd, first.size).map { received =>
                    assert(received == Chunk.from(first.toArray))
                    PosixTestSockets.resetPeer(sock, peerFd)
                    floodUntilClosed(channel).andThen(closedLatch.safe.get).map { _ =>
                        assert(state.get() == WriteState.TornDown)
                        assert(channel.closed())
                        spy.close()
                        discard(sock.close(clientFd))
                        succeed
                    }
                }
            }
        }

        // A race, not a fixed sequence: the hook closes the handle and the channel the instant the parked pump registers its writable wait, as
        // a Connection teardown does, while the poll carrier may deliver a real writable event on either side of the close. A close first fails
        // the wait Closed. A writable first resumes the tail on the poll carrier, and that write may fail on the closed handle, finish and go
        // Idle on the closed channel, or park again on a fresh wait that the close must still fail. Every interleaving must terminate in
        // teardown.
        "closing the handle and the channel under a parked pump tears it down whichever lands first, the close or a writable event" in {
            PosixTestSockets.assumePoller()
            val spy         = new RecordingIoDriver(PollerIoDriver.init())
            val closedLatch = Promise.Unsafe.init[Unit, Any]()
            discard(spy.start())
            PosixTestSockets.smallBufferedPair(2048, 2048).map { case (clientFd, peerFd) =>
                val channel       = Channel.Unsafe.init[Span[Byte]](16)
                val (pump, state) = newPump(spy, clientFd, channel, () => closedLatch.completeDiscard(Result.succeed(())))
                spy.onAwaitWritable = h =>
                    spy.closeHandle(h)
                    discard(channel.close())

                pump.start()
                discard(feedUntilParked(spy, pump, channel))

                closedLatch.safe.get.map { _ =>
                    assert(state.get() == WriteState.TornDown)
                    spy.close()
                    // closeHandle closed clientFd; only peerFd remains to close.
                    discard(sock.close(peerFd))
                    succeed
                }
            }
        }
    }

end WritePumpPollerTest
