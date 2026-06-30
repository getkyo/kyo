package kyo.scheduler

import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import java.util.concurrent.atomic.AtomicLong as JAtomicLong
import java.util.concurrent.locks.LockSupport
import kyo.*

/** B' reused-IOPromise faithful reproduction UNDER CONCURRENT SCHEDULER LOAD (impl-net3).
  *
  * Companion to ReadPumpReusePromiseTest. That repro ran ONE reused-promise/consumer pair on an otherwise idle scheduler and passed 500k
  * rounds. The kyo-net suite that surfaces B' runs ~17+ connections CONCURRENTLY, so the woken app fiber must be re-enqueued
  * (`Scheduler.get.schedule(this)` in IOTask's Async.Join onComplete) onto a BUSY scheduler. A wake that is dropped only when the scheduler is
  * contended would be invisible to the idle single-pair repro. This repro runs N concurrent pairs, each: a reused IOPromise driven by its own
  * raw poll-carrier thread, whose onComplete offers to its own channel and re-arms (becomeAvailable), and its own scheduler fiber looping on
  * that channel's take. Each pair keeps the strict single-offer-per-take ping-pong (a missed wake is not masked), but the many pairs keep the
  * scheduler busy and the wake-enqueue contended. A take that never completes after a result=true offer is the strand.
  */
class ReadPumpReusePromiseConcurrentTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private type Payload = Int

    "N concurrent reused-IOPromise pairs each deliver every offer to the parked take" in {
        val pairs         = 24    // mirror the concurrent-connection count of the surfacing suite (~17+), with margin
        val roundsPerPair = 60000 // 24 * 60000 = 1.44M total rounds, far past 3*2600

        val totalDriven = new JAtomicLong(0L)
        val totalTaken  = new JAtomicLong(0L)

        // Per-pair state. Each pair is fully independent: its own channel, reused promise, carrier thread, consumer fiber.
        final class Pair(val idx: Int):
            val channel              = Channel.Unsafe.init[Payload](16, Access.MultiProducerMultiConsumer)
            @volatile var armed      = false
            @volatile var taken      = false
            val driven               = new JAtomicInteger(0)
            @volatile var carrierThr = null.asInstanceOf[Thread]

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

            def startCarrier(): Unit =
                val t = new Thread(
                    () =>
                        armed = true
                        taken = true
                        var round = 0
                        while round < roundsPerPair do
                            while !armed do LockSupport.parkNanos(1000L)
                            while !taken do LockSupport.parkNanos(1000L)
                            armed = false
                            taken = false
                            driven.incrementAndGet()
                            totalDriven.incrementAndGet()
                            discard(pump.complete(Result.succeed(round)))
                            round += 1
                        end while
                    ,
                    s"repro-poll-carrier-$idx"
                )
                t.setDaemon(true)
                carrierThr = t
                t.start()
            end startCarrier

            val consume: Unit < (Async & Abort[Closed]) =
                Loop.repeat(roundsPerPair) {
                    channel.safe.take.map { _ =>
                        totalTaken.incrementAndGet()
                        taken = true
                    }
                }
        end Pair

        val allPairs = (0 until pairs).map(new Pair(_)).toIndexedSeq

        // Start every carrier, then race every consumer fiber concurrently under one overall deadline.
        val program =
            for
                _ <- Sync.defer(allPairs.foreach(_.startCarrier()))
                _ <- Async.timeout(90.seconds)(Async.foreach(allPairs, concurrency = pairs)(_.consume))
            yield ()

        Abort.run(program).map { result =>
            val perPairDriven = allPairs.map(p => s"${p.idx}:${p.driven.get()}").mkString(",")
            assert(
                result.isSuccess,
                s"B' REPRODUCED under concurrent load: a consumer stranded (a result=true offer did not wake the parked take). " +
                    s"result=$result totalDriven=${totalDriven.get()} totalTaken=${totalTaken.get()} perPairDriven=[$perPairDriven]"
            )
            assert(
                totalTaken.get() == pairs.toLong * roundsPerPair,
                s"expected ${pairs.toLong * roundsPerPair} takes, got ${totalTaken.get()}"
            )
        }
    }
end ReadPumpReusePromiseConcurrentTest
