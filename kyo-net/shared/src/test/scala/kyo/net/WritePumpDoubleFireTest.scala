package kyo.net

import kyo.*
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.WritePump
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.transport.WriteState

/** Reproduces the write-pump stale double-fire: a distinct writable that fires after the pump's state has already advanced must not
  * re-send.
  *
  * Exercises the invariant that a stale writable completion (a distinct onWritable invocation that fires after the pump already advanced
  * past AwaitingWritable) LOSES the CAS and no-ops. The stale writable fires onWritable, finds the state is no longer AwaitingWritable,
  * and the CAS fails, so no second write is submitted. Exactly one write per span.
  *
  * The scenario: the pump parks on writability (Flushing -> AwaitingWritable), the state is then advanced past AwaitingWritable by a
  * concurrent winner (simulated by manually advancing the test's state cell), and the now-stale writable promise is completed. Without the
  * CAS guard, onWritable would unconditionally call doWrite and produce an extra write. With the CAS, onWritable reads the advanced state,
  * loses the match, and no-ops.
  */
class WritePumpDoubleFireTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "WritePump" - {

        // Given: the pump parks on writability (Flushing -> AwaitingWritable) after a partial write
        // When: the state is advanced past AwaitingWritable before the writable promise fires
        // Then: the now-stale writable completion reaches onWritable, loses the CAS (state is not
        //       AwaitingWritable), and no extra write is submitted; writeCalls stays at 1
        //
        // Fail-before evidence: if onWritable had no CAS and always called doWrite, writeCalls would
        // reach 2 (the stale write) and the final assertion would fail. The CAS on the stored
        // AwaitingWritable reference is the single-winner guard; here the test advances state first so
        // the CAS expected-value no longer matches, proving the guard fires.
        "stale-writable-loses-CAS-and-no-ops-after-state-advanced" in {
            val writeCalls = AtomicInt.Unsafe.init(0)
            // Latch: completed by the pump's first write (partial). Drives handoff without sleep.
            val parkedLatch = Promise.Unsafe.init[Unit, Any]()
            // Writable promise captured when the driver receives awaitWritable.
            var capturedWritable: Promise.Unsafe[Unit, Abort[Closed]] = null.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]]

            final class AlwaysPartialDriver extends IoDriver[Unit]:
                def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
                def awaitRead(handle: Unit, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                    capturedWritable = promise
                    // Signal the test that the pump has parked (state is AwaitingWritable).
                    parkedLatch.completeDiscard(Result.succeed(()))
                end awaitWritable
                def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit   = ()
                def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
                    // Always return Partial (never Done): the pump parks after the first write and never
                    // advances to Idle on its own, giving the test full control over when state moves.
                    discard(writeCalls.incrementAndGet())
                    WriteResult.Partial(data, offset + 1)
                end write
                def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit      = ()
                def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = ()
                def close()(using AllowUnsafe, Frame): Unit                   = ()
                def label: String                                             = "AlwaysPartialDriver"
                def handleLabel(handle: Unit): String                         = "stub"
            end AlwaysPartialDriver

            val driver  = new AlwaysPartialDriver
            val channel = Channel.Unsafe.init[Span[Byte]](4)
            val state   = AtomicRef.Unsafe.init[WriteState](WriteState.Idle)
            val pump    = new WritePump((), driver, channel, () => discard(channel.close()), state)

            val payload = Span.fromUnsafe(Array[Byte](1, 2, 3, 4))
            discard(channel.offer(payload))
            pump.start()

            // Await the parked signal: the pump took the span, called write -> Partial, and called
            // awaitWritable. No sleep; parkedLatch is the definite event.
            parkedLatch.safe.get.map { _ =>
                assert(writeCalls.get() == 1, s"exactly one write (Partial) must have occurred before parking, got ${writeCalls.get()}")
                assert(capturedWritable.asInstanceOf[AnyRef] != null, "driver must have captured the writable promise")

                // Read the stored AwaitingWritable reference: this is the exact instance the pump
                // stored via CAS (Flushing -> AwaitingWritable). The CAS in onWritable compares
                // against this reference with AtomicReference.compareAndSet (reference equality).
                val parkedState = state.get()
                assert(
                    parkedState.isInstanceOf[WriteState.AwaitingWritable],
                    s"state must be AwaitingWritable before advancing, was $parkedState"
                )

                // Advance the state past AwaitingWritable: simulate what a concurrent write-winner or
                // teardown would do. This makes the captured writable promise STALE: its associated
                // AwaitingWritable state is no longer current.
                //
                // Fail-before reasoning: if onWritable had no CAS and just called doWrite unconditionally,
                // firing the writable now would call driver.write a SECOND time (writeCalls == 2). The
                // test's final assertion (writeCalls == 1) would fail, proving the CAS is load-bearing.
                state.set(WriteState.TornDown)

                // Now complete the writable promise: onWritable fires with the advanced (TornDown) state.
                // onWritable reads state.get() = TornDown -> hits the `case _ => ()` arm -> no CAS, no doWrite.
                capturedWritable.completeDiscard(Result.succeed(()))

                // Allow any synchronous onWritable execution to complete (it runs inline on the onComplete
                // callback chain, so it has already returned by the time completeDiscard returns).
                val writesAfterStale = writeCalls.get()
                assert(
                    writesAfterStale == 1,
                    s"stale writable must not drive an extra write: writeCalls must be 1, got $writesAfterStale"
                )
                succeed
            }
        }

        // Given: the pump parks on writability after a partial write
        // When: the writable promise is legitimately completed (state is still AwaitingWritable)
        // Then: the CAS succeeds, doWrite is called, and the write completes
        // (sanity-check that the non-stale path still works after the CAS was added)
        "legitimate-writable-wins-CAS-and-drives-write" in {
            val writeCalls                                            = AtomicInt.Unsafe.init(0)
            val parkedLatch                                           = Promise.Unsafe.init[Unit, Any]()
            val doneLatch                                             = Promise.Unsafe.init[Unit, Any]()
            var capturedWritable: Promise.Unsafe[Unit, Abort[Closed]] = null.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]]

            final class PartialThenDoneDriver extends IoDriver[Unit]:
                def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
                def awaitRead(handle: Unit, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                    capturedWritable = promise
                    parkedLatch.completeDiscard(Result.succeed(()))
                def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit   = ()
                def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
                    val n = writeCalls.incrementAndGet()
                    if n == 1 then WriteResult.Partial(data, offset + 1)
                    else
                        doneLatch.completeDiscard(Result.succeed(()))
                        WriteResult.Done
                    end if
                end write
                def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit      = ()
                def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = ()
                def close()(using AllowUnsafe, Frame): Unit                   = ()
                def label: String                                             = "PartialThenDoneDriver"
                def handleLabel(handle: Unit): String                         = "stub"
            end PartialThenDoneDriver

            val driver  = new PartialThenDoneDriver
            val channel = Channel.Unsafe.init[Span[Byte]](4)
            val state   = AtomicRef.Unsafe.init[WriteState](WriteState.Idle)
            val pump    = new WritePump((), driver, channel, () => discard(channel.close()), state)

            val payload = Span.fromUnsafe(Array[Byte](10, 20, 30, 40))
            discard(channel.offer(payload))
            pump.start()

            parkedLatch.safe.get.map { _ =>
                assert(writeCalls.get() == 1, "first write (Partial) must have occurred")
                assert(capturedWritable.asInstanceOf[AnyRef] != null, "writable promise must be captured")

                // Complete the writable legitimately (state is still AwaitingWritable).
                capturedWritable.completeDiscard(Result.succeed(()))

                // Await the Done write via the doneLatch; no sleep.
                doneLatch.safe.get.map { _ =>
                    assert(
                        writeCalls.get() == 2,
                        s"second write (Done) must have occurred after legitimate writable, got ${writeCalls.get()}"
                    )
                    succeed
                }
            }
        }
    }

end WritePumpDoubleFireTest
