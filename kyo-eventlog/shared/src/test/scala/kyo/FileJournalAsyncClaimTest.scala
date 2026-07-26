package kyo

import kyo.internal.ClaimSeam
import kyo.internal.StreamState

/** Deterministic behavioral test of the Async per-stream permit ([[ClaimSeam.async]]): a second
  * contender parks on an empty capacity-1 channel while the first holds the sole token, and can
  * only resume once the first offers the token back. Every ordering assertion here is structural
  * (a zero-capacity rendezvous channel and a capacity-1 permit channel), never a sleep or a
  * wall-clock race.
  */
class FileJournalAsyncClaimTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("valid identifier"))
    private val streamId = valid(Event.StreamId("claim-stream"))

    "parked claim" - {
        "a second acquire resumes only after the first's release, observing the first's published write and the next consecutive offset" in {
            import AllowUnsafe.embrace.danger
            val claim = ClaimSeam.async()
            for
                ref <- Sync.Unsafe.defer(AtomicRef.Unsafe.init(StreamState.empty))
                // Zero-capacity: a rendezvous. The offering fiber cannot proceed past `arrived.put`
                // until this test fiber calls `arrived.take`, so reaching that point proves the second
                // fiber's very next step (the permit's `Channel.take`) is about to run.
                arrived <- Channel.initUnscoped[Unit](0)
                // Capacity 2: big enough to hold both events without blocking either writer.
                order <- Channel.initUnscoped[String](2)
                first <- Sync.Unsafe.defer(claim.acquire(streamId, ref))
                // Simulates the first appender's write landing while it still holds the claim.
                _ <- Sync.Unsafe.defer(ref.set(first.copy(lastOffset = 0L)))
                fiber2 <- Fiber.initUnscoped(
                    arrived.put(()).andThen(
                        Sync.Unsafe.defer(claim.acquire(streamId, ref)).map { secondState =>
                            order.put("second-acquired").andThen(
                                // Simulates the second appender's write, consecutive on the first's.
                                Sync.Unsafe.defer(ref.set(secondState.copy(lastOffset = secondState.lastOffset + 1L)))
                                    .andThen(secondState)
                            )
                        }
                    )
                )
                _                <- arrived.take
                _                <- order.put("first-releasing")
                _                <- Sync.Unsafe.defer(claim.release(streamId, ref))
                observedBySecond <- fiber2.get
                firstEvent       <- order.take
                secondEvent      <- order.take
                finalState       <- Sync.Unsafe.defer(ref.get())
            yield
                assert(firstEvent == "first-releasing")
                assert(secondEvent == "second-acquired")
                assert(observedBySecond.lastOffset == 0L)
                assert(finalState.lastOffset == 1L)
            end for
        }
    }
end FileJournalAsyncClaimTest
