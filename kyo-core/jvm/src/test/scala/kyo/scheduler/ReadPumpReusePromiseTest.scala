package kyo.scheduler

import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import java.util.concurrent.locks.LockSupport
import kyo.*

/** B' reused-IOPromise faithful reproduction (impl-net3).
  *
  * The kyo-net ReadPump REUSES a single IOPromise across every read: the driver completes it, its `onComplete` offers the bytes into the
  * inbound channel, then it calls `becomeAvailable()` (CAS the result back to Pending) and re-arms via `awaitRead`. The B' strand symptom is
  * that a successful `channel.offer` (result=true) of the echo into the connection's OWN correct inbound channel does not complete the app's
  * parked take: the app fiber should be runnable but is never resumed (~1/2600 post-upgrade round-trips, shared across all three poller
  * backends). Every prior reduction (channel flush race, nested onComplete completion, generic cross-thread completion) used a FRESH promise
  * per read and PASSED in isolation. The single un-replicated difference is the REUSE of one IOPromise re-armed inside its own onComplete.
  *
  * This repro is the faithful reproduction the inventory's NEXT INSTRUMENT calls for: one reused IOPromise, completed from a NON-FIBER thread
  * (the poll carrier), whose onComplete offers to a channel a scheduler fiber takes from, then becomeAvailable + re-arm. Strict
  * single-offer-per-take ping-pong (the producer waits for the consumer to have taken before completing the next round) so a missed wake is
  * NOT masked by a later offer's flush, the exact kyo-net condition. Looped enough to clear the ~1/2600 window. A consumer take that never
  * completes after a result=true offer is the strand.
  */
class ReadPumpReusePromiseTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Faithful to ReadOutcome.Bytes(span): the payload the driver completes with and onComplete offers to the channel.
    private type Payload = Int

    "reused IOPromise re-armed inside onComplete delivers every offer to the parked take" in {
        // Big enough to clear the ~1/2600 strand window with margin (>3*2600 -> ~95% confident; this is far past that).
        val rounds = 500_000

        // The single reused promise, mirroring ReadPump extends IOPromise. The driver (poll carrier thread) completes it; its onComplete
        // offers to the channel and re-arms it. capacity-16 channel like the kyo-net inbound channel default, MPMC.
        val channel = Channel.Unsafe.init[Payload](16, Access.MultiProducerMultiConsumer)

        // re-arm signal: the poll carrier waits on this between rounds (set by onComplete after becomeAvailable), so completion N+1
        // never runs before the promise has been reset + re-armed for it (the awaitRead re-deposit in the real driver).
        @volatile var armed = false
        // take-acknowledgement: the consumer sets this once it has TAKEN round N's value, so the producer offers round N+1 only after the
        // take of N completed. This is the strict single-offer-per-take ping-pong (no masking by a follow-up offer's flush).
        @volatile var taken = false

        val nextValue = new JAtomicInteger(0)

        // The reused promise. onComplete mirrors ReadPump.onComplete -> offerToChannel -> requestNextRead (becomeAvailable + re-arm).
        val pump: IOPromise[Closed, Payload] =
            new IOPromise[Closed, Payload]():
                override protected def onComplete(): Unit =
                    given Frame = Frame.internal
                    this.poll() match
                        case Present(Result.Success(value)) =>
                            // Faithful to offerToChannel: a successful offer (result=true) then re-arm. We assert the offer truly accepts
                            // (capacity-16 channel, single in-flight value) so a strand is a delivery failure, not full-channel backpressure.
                            val offerResult = channel.offer(value)
                            if !offerResult.contains(true) then
                                throw new AssertionError(s"repro: offer not accepted: $offerResult")
                            // requestNextRead: becomeAvailable (CAS result -> Pending) then re-arm. We mirror the exact ordering: reset the
                            // SAME promise object, then signal the poll carrier it may complete the next round on it.
                            discard(this.becomeAvailableForReuse())
                            armed = true
                        case other =>
                            throw new AssertionError(s"repro: unexpected completion: $other")
                    end match
                end onComplete

                // Expose becomeAvailable (protected in IOPromise) for the repro, exactly the call ReadPump.requestNextRead makes.
                def becomeAvailableForReuse(): Boolean = this.becomeAvailable()
            end new
        end pump

        // The poll carrier: a raw (non-fiber) thread that drives the reused promise, mirroring the driver completing the read promise off
        // the scheduler. Strict ping-pong: for each round, wait until the promise is armed (reset for this round), wait until the previous
        // round's value was taken, then complete the promise with the next value.
        val carrier = new Thread(
            () =>
                armed = true // round 0 is armed by construction (promise starts Pending)
                taken = true // no previous take to wait for before round 0
                var round = 0
                while round < rounds do
                    // Wait for the promise to be reset + re-armed by the previous round's onComplete (the awaitRead re-deposit).
                    while !armed do LockSupport.parkNanos(1000L)
                    // Wait for the previous round's value to be TAKEN (strict single-offer-per-take; no masking follow-up offer).
                    while !taken do LockSupport.parkNanos(1000L)
                    armed = false
                    taken = false
                    discard(nextValue.getAndIncrement())
                    // Complete the reused promise: CAS Pending -> Success(value); onComplete fires synchronously (offer + becomeAvailable + arm).
                    discard(pump.complete(Result.succeed(round)))
                    round += 1
                end while
            ,
            "repro-poll-carrier"
        )
        carrier.setDaemon(true)

        // The consumer fiber: a real scheduler fiber looping on channel.take, mirroring the app's tlsConn.inbound.take. After each take it
        // sets `taken` so the carrier may drive the next round. A take that never completes after a result=true offer is the strand.
        val consume: Unit < (Async & Abort[Closed]) =
            Loop.repeat(rounds) {
                channel.safe.take.map { _ =>
                    taken = true
                }
            }

        val program =
            for
                _ <- Sync.defer(carrier.start())
                // A generous overall deadline: if the consumer strands on a take, this times out instead of hanging the suite.
                r <- Async.timeout(60.seconds)(consume)
            yield r

        // Run the consumer to completion. If a round strands (take never wakes after a result=true offer), the timeout fires and the assert
        // fails, capturing B' at the reused-IOPromise level. If it runs all rounds, the reused-promise re-arm wake is robust in isolation.
        Abort.run(program).map { result =>
            assert(
                result.isSuccess,
                s"B' REPRODUCED at reused-IOPromise level: consumer stranded (a result=true offer did not wake the parked take). " +
                    s"result=$result drivenRounds=${nextValue.get()}/$rounds"
            )
        }
    }
end ReadPumpReusePromiseTest
