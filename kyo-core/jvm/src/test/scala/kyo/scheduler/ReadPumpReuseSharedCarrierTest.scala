package kyo.scheduler

import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import java.util.concurrent.atomic.AtomicLong as JAtomicLong
import java.util.concurrent.locks.LockSupport
import kyo.*

/** B' reused-IOPromise faithful reproduction with ONE SHARED poll carrier feeding many reused promises (impl-net3).
  *
  * The closest reduction to the kyo-net topology. The real driver has a SINGLE poll-loop carrier thread that completes EVERY connection's
  * reused read promise serially; each completion's onComplete offers to that connection's channel and re-arms, and each delivered offer must
  * wake that connection's app fiber by `Scheduler.get.schedule(this)` issued from the ONE carrier thread. The per-pair-thread companion
  * (ReadPumpReusePromiseConcurrentTest) issued the wake-enqueues from N distinct threads; this one issues them all from ONE non-fiber thread,
  * the exact "many fibers woken from one poll carrier" condition where a contended/dropped schedule-enqueue would hide.
  *
  * One carrier thread round-robins across N reused promises. For each, on its turn: if the promise is armed (reset by its last onComplete) and
  * its last value was taken (strict single-offer-per-take, no masking follow-up offer), complete it with the next value. N scheduler fibers
  * each loop on their own channel's take. A take that never completes after a result=true offer is the strand.
  */
class ReadPumpReuseSharedCarrierTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private type Payload = Int

    "one shared poll carrier feeding N reused promises wakes every parked take" in {
        val pairs         = 24
        val roundsPerPair = 60000 // 1.44M total rounds, far past 3*2600

        val totalTaken = new JAtomicLong(0L)

        final class Pair(val idx: Int):
            val channel         = Channel.Unsafe.init[Payload](16, Access.MultiProducerMultiConsumer)
            @volatile var armed = true // round 0 armed by construction (promise starts Pending)
            @volatile var taken = true // no previous take before round 0
            val driven          = new JAtomicInteger(0)

            val pump: IOPromise[Closed, Payload] =
                new IOPromise[Closed, Payload]():
                    override protected def onComplete(): Unit =
                        given Frame = Frame.internal
                        this.poll() match
                            case Present(Result.Success(value)) =>
                                val offerResult = channel.offer(value)
                                if !offerResult.contains(true) then
                                    throw new AssertionError(s"pair $idx: offer not accepted: $offerResult")
                                discard(this.becomeAvailable())
                                armed = true
                            case other =>
                                throw new AssertionError(s"pair $idx: unexpected completion: $other")
                        end match
                    end onComplete
                end new
            end pump

            val consume: Unit < (Async & Abort[Closed]) =
                Loop.repeat(roundsPerPair) {
                    channel.safe.take.map { _ =>
                        totalTaken.incrementAndGet()
                        taken = true
                    }
                }
        end Pair

        val allPairs = (0 until pairs).map(new Pair(_)).toIndexedSeq

        // The single shared poll carrier: round-robin across all reused promises, completing the next round of any pair that is armed + taken.
        // Runs until every pair has driven roundsPerPair rounds.
        val carrier = new Thread(
            () =>
                var done = false
                while !done do
                    done = true
                    var i = 0
                    while i < pairs do
                        val p = allPairs(i)
                        val d = p.driven.get()
                        if d < roundsPerPair then
                            done = false
                            if p.armed && p.taken then
                                p.armed = false
                                p.taken = false
                                p.driven.incrementAndGet()
                                discard(p.pump.complete(Result.succeed(d)))
                            end if
                        end if
                        i += 1
                    end while
                    // Avoid a pure busy-spin starving the scheduler workers: a brief park when nothing was ready this sweep.
                    LockSupport.parkNanos(1000L)
                end while
            ,
            "repro-shared-poll-carrier"
        )
        carrier.setDaemon(true)

        val program =
            for
                _ <- Sync.defer(carrier.start())
                _ <- Async.timeout(120.seconds)(Async.foreach(allPairs, concurrency = pairs)(_.consume))
            yield ()

        Abort.run(program).map { result =>
            val perPairDriven = allPairs.map(p => s"${p.idx}:${p.driven.get()}").mkString(",")
            assert(
                result.isSuccess,
                s"B' REPRODUCED with a shared carrier: a consumer stranded (a result=true offer did not wake the parked take). " +
                    s"result=$result totalTaken=${totalTaken.get()} perPairDriven=[$perPairDriven]"
            )
            assert(
                totalTaken.get() == pairs.toLong * roundsPerPair,
                s"expected ${pairs.toLong * roundsPerPair} takes, got ${totalTaken.get()}"
            )
        }
    }
end ReadPumpReuseSharedCarrierTest
