package kyo.net.internal.util

import kyo.*
import kyo.net.Test

/** Multi-producer contract for the unboxed MPSC long FIFO that backs the poller's interest-change queue.
  *
  * Stressed with real threads, which is why these leaves are not in the shared [[MpscLongQueueTest]] beside the single-consumer ones: the
  * queue is a plain data structure rather than an effect, and its consumer is one fiber/thread, so the contention that matters is between
  * OS threads. JVM and Native only, the platforms that have them.
  */
class MpscLongQueueConcurrencyTest extends Test:

    "multi-producer single-consumer: every offered value is dequeued exactly once" in {
        // Real-thread stress: P producer threads each offer a disjoint contiguous block of longs; one consumer thread drains until it has
        // seen every value. Asserts no value is lost, duplicated, or corrupted (the MPSC contract). Per-producer order is also checked: the
        // values from any single producer must appear in the consumer's stream in the order that producer offered them. The producers
        // outrun the single consumer, so chunks fill and are linked repeatedly while every other producer is mid-offer, which is the
        // contended path the deterministic link leaf in the shared suite cannot reach.
        val q           = new MpscLongQueue()
        val producers   = 8
        val perProducer = 20000
        val total       = producers * perProducer

        val seen      = new java.util.concurrent.atomic.AtomicReferenceArray[Boolean](total)
        val seenCount = new java.util.concurrent.atomic.AtomicInteger(0)
        // Track last-seen value per producer to verify per-producer FIFO. Producer p owns values [p*perProducer, (p+1)*perProducer).
        val lastSeen = new Array[Long](producers)
        java.util.Arrays.fill(lastSeen, -1L)
        @volatile var orderViolation = false
        @volatile var duplicate      = false
        val producersDone            = new java.util.concurrent.atomic.AtomicInteger(0)

        val consumer = new Thread(() =>
            var drained = 0
            var idle    = 0L
            var lost    = false
            while drained < total && !lost do
                val v = q.poll()
                if v != MpscLongQueue.Empty then
                    val idx = v.toInt
                    if seen.getAndSet(idx, true) then duplicate = true
                    val p = idx / perProducer
                    if v <= lastSeen(p) then orderViolation = true
                    lastSeen(p) = v
                    drained += 1
                    idle = 0L
                    discard(seenCount.incrementAndGet())
                else
                    // Every producer has returned, so a long run of empty polls means values were lost rather than still in flight. Stopping
                    // here makes that a counted failure below; without it the drain loop spins until the suite timeout and reports nothing.
                    idle += 1L
                    if idle > 20000000L && producersDone.get() == producers then lost = true
                end if
            end while
        )
        consumer.start()

        val producerThreads = (0 until producers).map { p =>
            val t = new Thread(() =>
                val base = p.toLong * perProducer
                var j    = 0
                while j < perProducer do
                    q.offer(base + j)
                    j += 1
                discard(producersDone.incrementAndGet())
            )
            t.start()
            t
        }
        producerThreads.foreach(_.join())
        consumer.join()

        assert(seenCount.get() == total, s"consumer must drain all $total values, got ${seenCount.get()}")
        assert(!duplicate, "no value may be dequeued twice")
        assert(!orderViolation, "each producer's values must stay in FIFO order in the consumer's stream")
        var i      = 0
        var allHit = true
        while i < total do
            if !seen.get(i) then allHit = false
            i += 1
        assert(allHit, "every offered value must be dequeued exactly once")
        succeed
    }

    "a ring slot is never handed to two producers" in {
        // The leaf above lets producers outrun the consumer, so it spends most of its time on freshly linked chunks. Here the consumer
        // drains each round before the next begins, so the producers stay on one chunk and contend over slots the consumer has just
        // cleared, wrapping the ring thousands of times. A reservation that handed one slot to two producers, or a slot read before its
        // store landed, shows up as a duplicated or lost value.
        val producers  = 8
        val perRound   = 8 // well inside one chunk, so every round reuses slots the previous rounds already carried
        val rounds     = 20000
        val total      = rounds * perRound
        val q          = new MpscLongQueue()
        val seen       = new java.util.concurrent.atomic.AtomicReferenceArray[Boolean](total)
        val roundStart = new java.util.concurrent.CyclicBarrier(producers + 1)
        val roundEnd   = new java.util.concurrent.CyclicBarrier(producers + 1)

        @volatile var duplicate = false
        @volatile var lost      = false
        var drained             = 0

        val producerThreads = (0 until producers).map { p =>
            val t = new Thread(() =>
                var r = 0
                while r < rounds do
                    roundStart.await()
                    // The round's perRound values are split across the producers, so every round drains to exactly perRound values.
                    var j = p
                    while j < perRound do
                        q.offer((r.toLong * perRound) + j)
                        j += producers
                    roundEnd.await()
                    r += 1
                end while
            )
            t.start()
            t
        }

        var r = 0
        while r < rounds && !lost do
            roundStart.await()
            roundEnd.await()
            // Every offer of the round has returned, so every value is stored and the round drains without meeting a reserved slot.
            var got = 0
            while got < perRound && !lost do
                val v = q.poll()
                if v != MpscLongQueue.Empty then
                    if seen.getAndSet(v.toInt, true) then duplicate = true
                    got += 1
                    drained += 1
                else lost = true
                end if
            end while
            r += 1
        end while
        producerThreads.foreach(_.join())

        assert(!lost, s"a round polled empty with its offers already returned, so a value was lost after $drained of $total")
        assert(drained == total, s"consumer must drain all $total values, got $drained")
        assert(!duplicate, "no value may be dequeued twice")
        var i      = 0
        var allHit = true
        while i < total do
            if !seen.get(i) then allHit = false
            i += 1
        assert(allHit, "every offered value must be dequeued exactly once")
        succeed
    }

end MpscLongQueueConcurrencyTest
