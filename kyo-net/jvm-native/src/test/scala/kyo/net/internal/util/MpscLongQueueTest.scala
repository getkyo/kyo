package kyo.net.internal.util

import kyo.*
import kyo.net.Test

/** Correctness tests for the unboxed MPSC long FIFO that backs the poller's interest-change queue.
  *
  * The single-consumer paths (FIFO order, the empty sentinel, node recycling) are exercised directly; the multi-producer / single-consumer
  * concurrency is stressed with real threads (the queue is a plain data structure, not an effect, and its consumer is one fiber/thread).
  */
class MpscLongQueueTest extends Test:

    "empty queue polls the Empty sentinel" in {
        val q = new MpscLongQueue()
        assert(q.poll() == MpscLongQueue.Empty)
        assert(!q.peekNonEmpty())
        succeed
    }

    "single offer then poll returns the value, then Empty" in {
        val q = new MpscLongQueue()
        q.offer(42L)
        assert(q.peekNonEmpty())
        assert(q.poll() == 42L)
        assert(q.poll() == MpscLongQueue.Empty)
        assert(!q.peekNonEmpty())
        succeed
    }

    "preserves FIFO order across many offers" in {
        val q = new MpscLongQueue()
        val n = 1000
        var i = 0
        while i < n do
            q.offer(i.toLong)
            i += 1
        var k  = 0
        var ok = true
        while k < n do
            if q.poll() != k.toLong then ok = false
            k += 1
        assert(ok, "values must come out in offer order")
        assert(q.poll() == MpscLongQueue.Empty)
        succeed
    }

    "interleaved offer/poll preserves FIFO and drains fully" in {
        val q   = new MpscLongQueue()
        val out = scala.collection.mutable.ArrayBuffer.empty[Long]
        // Offer 0,1 then poll one; offer 2,3 then poll one; etc. The consumer always trails the producer.
        var i = 0L
        while i < 10L do
            q.offer(i)
            q.offer(i + 1)
            out += q.poll()
            i += 2
        end while
        var p = q.poll()
        while p != MpscLongQueue.Empty do
            out += p
            p = q.poll()
        assert(out.toList == (0L until 10L).toList, s"expected 0..9 in order, got ${out.toList}")
        succeed
    }

    "recycles nodes: a long offer/poll churn does not lose or reorder values" in {
        // Drives many rounds so the free list is exercised: every poll recycles a node that the next offer reuses. A correctness bug in the
        // recycle (stale next, double-free of a node into the chain) would surface as a wrong value or a missing/duplicated entry.
        val q       = new MpscLongQueue()
        val rounds  = 5000
        var r       = 0
        var ok      = true
        var expectV = 0L
        while r < rounds do
            q.offer(expectV)
            q.offer(expectV + 1)
            if q.poll() != expectV then ok = false
            if q.poll() != expectV + 1 then ok = false
            expectV += 2
            r += 1
        end while
        assert(ok, "churn must preserve every value in FIFO order")
        assert(q.poll() == MpscLongQueue.Empty, "queue must be empty after balanced churn")
        succeed
    }

    "multi-producer single-consumer: every offered value is dequeued exactly once" in {
        // Real-thread stress: P producer threads each offer a disjoint contiguous block of longs; one consumer thread drains until it has
        // seen every value. Asserts no value is lost, duplicated, or corrupted (the MPSC contract). Per-producer order is also checked: the
        // values from any single producer must appear in the consumer's stream in the order that producer offered them.
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

    "a warm node pool never hands the same node to two producers" in {
        // The leaf above starts with an empty pool, so most claims allocate and the reuse path is barely exercised. Warming the pool first
        // makes every offer claim a recycled node, which is where a pool that can hand one node to two producers, or hand out a node still
        // linked in the FIFO, corrupts the chain: the symptom is a lost or duplicated value here.
        val producers   = 16
        val perProducer = 20000
        val total       = producers * perProducer

        val q = new MpscLongQueue()
        var w = 0
        while w < 64 do
            q.offer(w.toLong)
            w += 1
        w = 0
        while w < 64 do
            discard(q.poll())
            w += 1

        val seen                = new java.util.concurrent.atomic.AtomicReferenceArray[Boolean](total)
        val producersDone       = new java.util.concurrent.atomic.AtomicInteger(0)
        @volatile var duplicate = false
        var drained             = 0

        val consumer = new Thread(() =>
            var idle = 0L
            var lost = false
            while drained < total && !lost do
                val v = q.poll()
                if v != MpscLongQueue.Empty then
                    if seen.getAndSet(v.toInt, true) then duplicate = true
                    drained += 1
                    idle = 0L
                else
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

        assert(drained == total, s"consumer must drain all $total values from a warm pool, got $drained")
        assert(!duplicate, "no value may be dequeued twice")
        var i      = 0
        var allHit = true
        while i < total do
            if !seen.get(i) then allHit = false
            i += 1
        assert(allHit, "every offered value must be dequeued exactly once")
        succeed
    }

end MpscLongQueueTest
