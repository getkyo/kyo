package kyo.net.internal.transport

import kyo.*
import kyo.net.NetConnectionClosedException
import kyo.net.Test
import kyo.net.internal.transport.ScriptedIoDriver.Step

/** WritePump's state machine driven through a [[ScriptedIoDriver]]: every write outcome, writable signal and close is chosen by the leaf
  * and runs inline, so each leaf is one exact interleaving and nothing depends on kernel buffers or scheduling. Integration with a real
  * driver over kernel sockets is WritePumpPollerTest's.
  */
class WritePumpTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    final private class Fixture(steps: Step*)(using AllowUnsafe):
        val driver    = new ScriptedIoDriver(steps*)
        val channel   = Channel.Unsafe.init[Span[Byte]](16)
        val state     = AtomicRef.Unsafe.init[WriteState](WriteState.Idle)
        val teardowns = AtomicInt.Unsafe.init(0)
        val log       = new RecordingLog(Log.live.unsafe)
        val pump      = new WritePump((), driver, channel, () => discard(teardowns.incrementAndGet()), state, log)

        def offer(span: Span[Byte]): Result[Closed, Boolean] = channel.offer(span)

        /** Whether the writes presented exactly these span references at these offsets, in order. A copy of the span would fail it. */
        def presented(expected: (Span[Byte], Int)*): Boolean =
            driver.writes.size == expected.size && driver.writes.toSeq.zip(expected).forall {
                case ((span, offset, _), (want, wantOffset)) => (span.asInstanceOf[AnyRef] eq want.asInstanceOf[AnyRef]) &&
                    offset == wantOffset
            }

        def trace: String = driver.writes.map((span, offset, result) => s"${span.size}B@$offset -> $result").mkString(", ")
    end Fixture

    private def span(first: Int, size: Int): Span[Byte] = Span.fromUnsafe(Array.tabulate[Byte](size)(i => (first + i).toByte))

    private def bytesOf(spans: Span[Byte]*): Chunk[Byte] = Chunk.from(spans.flatMap(_.toArray))

    "WritePump" - {

        "a Done write goes back to the channel: one write per span, bytes in offer order, start is the only info log" in {
            val f     = new Fixture()
            val spans = Chunk.from((0 until 16).map(i => span(i * 16, i + 1)))
            spans.foreach(s => assert(f.offer(s) == Result.succeed(true)))
            f.pump.start()

            assert(f.presented(spans.map((_, 0))*), s"each span must be written once, from offset 0, in offer order: ${f.trace}")
            assert(f.driver.wire == bytesOf(spans*))
            assert(f.state.get() == WriteState.Idle)
            assert(f.driver.writableWaits == 0)
            assert(f.log.infoCount.get() == 1, s"the steady write path must not log at info, got ${f.log.infoCount.get()} info calls")

            val late = span(100, 4)
            assert(f.offer(late) == Result.succeed(true))
            assert(f.presented((spans :+ late).map((_, 0))*), s"the pump must keep taking after it drained the channel: ${f.trace}")
            assert(f.driver.wire == bytesOf((spans :+ late)*))
            assert(f.teardowns.get() == 0)
        }

        "an empty span is written as Done and the pump takes the next span" in {
            val f     = new Fixture()
            val empty = Span.empty[Byte]
            val next  = span(1, 3)
            assert(f.offer(empty) == Result.succeed(true))
            assert(f.offer(next) == Result.succeed(true))
            f.pump.start()

            assert(f.presented((empty, 0), (next, 0)), f.trace)
            assert(f.driver.wire == bytesOf(next))
            assert(f.state.get() == WriteState.Idle)
            assert(f.teardowns.get() == 0)
        }

        "a Partial write parks on writable and leaves later spans queued until the retry finishes" in {
            val f      = new Fixture(Step.Accept(3))
            val parked = span(0, 10)
            assert(f.offer(parked) == Result.succeed(true))
            f.pump.start()

            assert(f.state.get() == WriteState.AwaitingWritable(parked, 3))
            assert(f.driver.writableWaits == 1)
            assert(f.driver.wire == bytesOf(parked).take(3))

            val queued = span(50, 5)
            assert(f.offer(queued) == Result.succeed(true))
            assert(f.channel.size() == Result.succeed(1), "a span offered while the pump is parked must stay in the channel")
            assert(f.driver.writes.size == 1, "a parked pump must not write before the socket is writable")

            f.driver.signalWritable()
            assert(f.presented((parked, 0), (parked, 3), (queued, 0)), f.trace)
            assert(f.driver.wire == bytesOf(parked, queued))
            assert(f.channel.size() == Result.succeed(0))
            assert(f.state.get() == WriteState.Idle)
            assert(f.driver.overlappingWritableWaits == 0)
            assert(f.teardowns.get() == 0)
        }

        "every park resumes the same span at the offset the driver reported, through Partial, EAGAIN and tail backpressure" in {
            val f    = new Fixture(Step.Accept(4), Step.Accept(0), Step.TailFull, Step.Accept(3))
            val data = span(0, 12)
            assert(f.offer(data) == Result.succeed(true))
            f.pump.start()

            val parks = Chunk(
                WriteState.AwaitingWritable(data, 4),
                WriteState.AwaitingWritable(data, 4),
                WriteState.Backpressured(data, 4),
                WriteState.AwaitingWritable(data, 7)
            )
            parks.foreach { expected =>
                assert(f.state.get() == expected, s"expected the pump parked as $expected, was ${f.state.get()}")
                f.driver.signalWritable()
            }

            assert(f.presented(Chunk(0, 4, 4, 4, 7).map((data, _))*), f.trace)
            assert(f.driver.wire == bytesOf(data), "the retries must deliver every byte exactly once, in order")
            assert(f.driver.writableWaits == 4)
            assert(f.driver.overlappingWritableWaits == 0)
            assert(f.state.get() == WriteState.Idle)
            assert(f.teardowns.get() == 0)
        }

        "a write Error tears the pump down once and drops the queued spans" in {
            val f = new Fixture(Step.Fail)
            assert(f.offer(span(0, 4)) == Result.succeed(true))
            assert(f.offer(span(10, 4)) == Result.succeed(true))
            f.pump.start()

            assert(f.teardowns.get() == 1)
            assert(f.state.get() == WriteState.TornDown)
            assert(f.channel.closed(), "teardown must close the outbound channel")
            assert(f.driver.writes.size == 1, "no write may follow the Error")
            assert(f.driver.wire.isEmpty)
        }

        "a write Error on the retry after a park tears the pump down" in {
            val f      = new Fixture(Step.Accept(2), Step.Fail)
            val parked = span(0, 8)
            assert(f.offer(parked) == Result.succeed(true))
            f.pump.start()
            assert(f.state.get() == WriteState.AwaitingWritable(parked, 2))

            f.driver.signalWritable()
            assert(f.presented((parked, 0), (parked, 2)), f.trace)
            assert(f.teardowns.get() == 1)
            assert(f.state.get() == WriteState.TornDown)
            assert(f.channel.closed())
            assert(f.driver.wire == bytesOf(parked).take(2))
        }

        "closing the handle under a parked pump fails its writable wait and tears it down without another write" in {
            val f = new Fixture(Step.Accept(2))
            assert(f.offer(span(0, 8)) == Result.succeed(true))
            f.pump.start()
            assert(f.driver.holdsWritable)

            f.driver.closeHandle(())
            assert(f.teardowns.get() == 1)
            assert(f.state.get() == WriteState.TornDown)
            assert(f.channel.closed())
            assert(f.driver.writes.size == 1, "the parked tail is undeliverable once the handle is closed")
        }

        "a writable wait failed with a NetException tears the pump down" in {
            val f = new Fixture(Step.Accept(2))
            assert(f.offer(span(0, 8)) == Result.succeed(true))
            f.pump.start()

            f.driver.failWritable(NetConnectionClosedException(NetConnectionClosedException.Operation.Send))
            assert(f.teardowns.get() == 1)
            assert(f.state.get() == WriteState.TornDown)
            assert(f.channel.closed())
            assert(f.driver.writes.size == 1)
        }

        "closing the channel while the pump waits for a take tears it down without a write" in {
            val f = new Fixture()
            f.pump.start()
            assert(f.state.get() == WriteState.Idle)

            discard(f.channel.close())
            assert(f.teardowns.get() == 1)
            assert(f.state.get() == WriteState.TornDown)
            assert(f.driver.writes.isEmpty)
        }

        "a graceful close while parked delivers the parked tail and every queued span before the pump tears down" in {
            val f      = new Fixture(Step.Accept(3))
            val parked = span(0, 10)
            val queued = span(40, 6)
            assert(f.offer(parked) == Result.succeed(true))
            f.pump.start()
            assert(f.offer(queued) == Result.succeed(true))

            discard(f.channel.closeAwaitEmpty())
            assert(f.teardowns.get() == 0, "a graceful close must not tear down a pump that still holds bytes")

            f.driver.signalWritable()
            assert(f.driver.wire == bytesOf(parked, queued), "a graceful close must not drop the parked tail or a queued span")
            assert(f.teardowns.get() == 1, "the drained, closed channel must tear the pump down")
            assert(f.state.get() == WriteState.TornDown)
        }

        "a writable that wins the race with a handle close resumes onto the closed handle and tears down on the write Error" in {
            val f      = new Fixture(Step.Accept(2))
            val parked = span(0, 8)
            assert(f.offer(parked) == Result.succeed(true))
            f.pump.start()

            f.driver.onNextWrite(() => f.driver.closeHandle(()))
            f.driver.signalWritable()
            assert(f.presented((parked, 0), (parked, 2)), f.trace)
            assert(f.driver.writes.last._3 == WriteResult.Error)
            assert(f.teardowns.get() == 1)
            assert(f.state.get() == WriteState.TornDown)
            assert(f.driver.wire == bytesOf(parked).take(2))
        }

        "a handle close that lands between a Partial write and its park tears the pump down through the failed wait" in {
            val f = new Fixture(Step.Accept(2))
            assert(f.offer(span(0, 8)) == Result.succeed(true))
            f.driver.afterNextWrite(() => f.driver.closeHandle(()))
            f.pump.start()

            assert(f.driver.writableWaits == 1, "the pump must still park the tail it holds")
            assert(f.teardowns.get() == 1, "the wait registered on a closed handle must fail and tear the pump down")
            assert(f.state.get() == WriteState.TornDown)
            assert(f.driver.writes.size == 1)
        }

        "a writable that completes inside awaitWritable resumes the parked tail and the pump goes Idle" in {
            val f      = new Fixture(Step.Accept(3))
            val parked = span(0, 8)
            assert(f.offer(parked) == Result.succeed(true))
            f.driver.completeNextWaitInline()
            f.pump.start()

            assert(f.presented((parked, 0), (parked, 3)), f.trace)
            assert(f.driver.wire == bytesOf(parked))
            assert(f.driver.writableWaits == 1)
            assert(f.state.get() == WriteState.Idle, "an inline writable must not be lost to a park registered after it")
            assert(f.teardowns.get() == 0)
        }

        "a Partial at the end of the span parks, and the retry at that offset finishes the span without extra bytes" in {
            val data = span(0, 6)
            val f    = new Fixture(Step.AcceptPark(6), Step.Accept(0))
            assert(f.offer(data) == Result.succeed(true))
            f.pump.start()
            assert(f.state.get() == WriteState.AwaitingWritable(data, 6), "the driver still holds bytes it reported as Partial")

            f.driver.signalWritable()
            assert(f.presented((data, 0), (data, 6)), f.trace)
            assert(f.driver.writes.last._3 == WriteResult.Done)
            assert(f.driver.wire == bytesOf(data), "the retry at the span's end must add no bytes")
            assert(f.state.get() == WriteState.Idle)
        }

        "a hard channel close while parked still delivers the parked tail, then the failed take tears the pump down" in {
            val f      = new Fixture(Step.Accept(3))
            val parked = span(0, 10)
            assert(f.offer(parked) == Result.succeed(true))
            f.pump.start()
            assert(f.offer(span(50, 5)) == Result.succeed(true))

            discard(f.channel.close())
            assert(f.teardowns.get() == 0, "a closed channel reaches a parked pump only at its next take")

            f.driver.signalWritable()
            assert(
                f.driver.wire == bytesOf(parked),
                "the span the pump already took must be finished; the queued one was dropped by the close"
            )
            assert(f.teardowns.get() == 1)
            assert(f.state.get() == WriteState.TornDown)
        }

        // Nothing in the pump can tear it down while it is Flushing, since no take or writable is pending then, so this lost CAS is a defensive
        // branch with no reachable trigger. Setting TornDown inside the write stands in for a teardown there; the reachable close race is the
        // close between a Partial and its park above.
        "a teardown forced during a Partial write drops the captured tail: no park and no further write" in {
            val f = new Fixture(Step.Accept(2))
            assert(f.offer(span(0, 8)) == Result.succeed(true))
            f.driver.onNextWrite(() => f.state.set(WriteState.TornDown))
            f.pump.start()

            assert(f.driver.writes.size == 1)
            assert(f.driver.writableWaits == 0, "a pump whose CAS lost to teardown must not park the undeliverable tail")
            assert(f.state.get() == WriteState.TornDown, "a lost CAS must not resurrect the pump")
            assert(f.offer(span(20, 4)) == Result.succeed(true))
            assert(f.driver.writes.size == 1, "a torn-down pump must not take or write again")
        }

        // The same defensive branch on the Done side.
        "a teardown forced during a Done write keeps the pump torn down: no further take" in {
            val f = new Fixture()
            assert(f.offer(span(0, 8)) == Result.succeed(true))
            f.driver.onNextWrite(() => f.state.set(WriteState.TornDown))
            f.pump.start()

            assert(f.driver.writes.size == 1)
            assert(f.state.get() == WriteState.TornDown, "a lost CAS must not resurrect the pump")
            assert(f.offer(span(20, 4)) == Result.succeed(true))
            assert(f.driver.writes.size == 1, "a torn-down pump must not take or write again")
        }
    }

end WritePumpTest
