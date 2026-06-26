package kyo.net

import kyo.*
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WritePump
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.transport.WriteState

/** Verifies that a backpressured writer resumes on drain.
  *
  * A write parks in AwaitingWritable (Flushing -> AwaitingWritable) because the first write returns Partial. When the socket becomes
  * writable (modeled by completing the captured promise), the pump resumes, retries the remaining bytes, and the write completes. The
  * bytes arrive in order.
  *
  * Handoff is driven by a Promise latch (no sleep). All driver callbacks are synchronous (inline), so the writable completion, the
  * retry write, and the state transition to Idle all happen inside the completeDiscard call. The assertions run after the complete
  * returns, at which point the retry is guaranteed to have landed.
  */
class LIVE3Test extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "LIVE3" - {

        // Given: a connection whose socket send buffer is full so the write parks (AwaitingWritable)
        // When: the peer drains, the socket becomes writable (promise completed)
        // Then: the parked write resumes and the bytes are delivered in order
        "backpressured-writer-resumes-on-drain" in {
            // PartialFirstDriver: returns Partial on the first write (half the bytes), Done on the retry.
            // All callbacks are synchronous (inline) so no sleep or latch is needed to observe the retry.
            val writtenRegions                                       = scala.collection.mutable.ListBuffer[(Span[Byte], Int)]()
            var capturedPromise: Promise.Unsafe[Unit, Abort[Closed]] = null.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]]

            final class PartialFirstDriver extends IoDriver[Unit]:
                def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
                def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                    capturedPromise = promise
                def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit   = ()
                def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
                    writtenRegions += ((data, offset))
                    if writtenRegions.size == 1 then
                        // First call: partial (sent half).
                        val sent = math.max(1, data.size / 2)
                        WriteResult.Partial(data, offset + sent)
                    else
                        WriteResult.Done
                    end if
                end write
                def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit      = ()
                def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = ()
                def close()(using AllowUnsafe, Frame): Unit                   = ()
                def label: String                                             = "PartialFirstDriver"
                def handleLabel(handle: Unit): String                         = "stub"
            end PartialFirstDriver

            val driver    = new PartialFirstDriver
            val channel   = Channel.Unsafe.init[Span[Byte]](4)
            val state     = AtomicRef.Unsafe.init[WriteState](WriteState.Idle)
            val doneLatch = Promise.Unsafe.init[Unit, Any]()
            val pump = new WritePump(
                (),
                driver,
                channel,
                () =>
                    discard(channel.close())
                    doneLatch.completeDiscard(Result.succeed(()))
                ,
                state
            )

            val payload = Span.fromUnsafe(Array[Byte](10, 20, 30, 40))
            discard(channel.offer(payload))
            // start() fires onTake synchronously (the channel has data): write -> Partial -> awaitWritable sets capturedPromise.
            pump.start()

            // capturedPromise is set synchronously above (PartialFirstDriver.awaitWritable is called inline).
            assert(capturedPromise.asInstanceOf[AnyRef] != null, "pump must park on writability and capture a promise")
            assert(
                state.get().isInstanceOf[WriteState.AwaitingWritable],
                s"state must be AwaitingWritable after partial write, was ${state.get()}"
            )

            // Signal writability: the pump's onWritable callback fires inline inside completeDiscard.
            // onWritable CASes AwaitingWritable -> Flushing, calls doWrite -> Done -> Flushing -> Idle.
            // The retry write (writtenRegions size 2) completes before completeDiscard returns.
            capturedPromise.completeDiscard(Result.succeed(()))

            // All synchronous: writtenRegions.size is 2 here, no sleep needed.
            assert(writtenRegions.size >= 2, s"pump must retry after writable, got ${writtenRegions.size} write calls")
            // First write used offset 0; second write used an advancing offset.
            assert(writtenRegions.head._2 == 0, "first write must start at offset 0")
            val (retrySpan, retryOffset) = writtenRegions(1)
            assert(
                retrySpan.asInstanceOf[AnyRef] eq payload.asInstanceOf[AnyRef],
                "retry must re-present the SAME Span reference (no allocation)"
            )
            assert(retryOffset > 0, s"retry offset must be > 0 (advancing), got $retryOffset")
            succeed
        }
    }

end LIVE3Test
