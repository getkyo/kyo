package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.AllowUnsafe.embrace.danger

class SpmcUnboundedUnsafeQueueTest extends UnsafeQueueBaseTest:
    def queueName                               = "SpmcUnboundedUnsafeQueue"
    def isBounded                               = false
    def nProducers                              = 1
    def nConsumers                              = 3
    def testSizes                               = Seq(8, 16, 64)
    def makeQueue[A](size: Int): UnsafeQueue[A] = new SpmcUnboundedUnsafeQueue[A](size)

    "SpmcUnboundedUnsafeQueue-specific" - {

        "chunkTransitionExact" in {
            for cs <- Seq(8, 16) do
                val q                  = new SpmcUnboundedUnsafeQueue[Int](cs)
                val effectiveChunkSize = UnsafeQueue.roundToPowerOfTwo(Math.max(8, cs))
                for i <- 0 to effectiveChunkSize do q.offer(i)
                for i <- 0 to effectiveChunkSize do
                    assert(q.poll() == Maybe(i), s"chunkSize=$cs, i=$i")
        }

        "manyChunkTransitions" in {
            val q = new SpmcUnboundedUnsafeQueue[Int](8)
            val n = 400
            for i <- 0 until n do q.offer(i)
            for i <- 0 until n do
                assert(q.poll() == Maybe(i), s"i=$i")
        }

        "singleConsumerDegenerateCase" in {
            val q = new SpmcUnboundedUnsafeQueue[Int](8)
            for i <- 0 until 1000 do q.offer(i)
            for i <- 0 until 1000 do
                assert(q.poll() == Maybe(i))
        }

        "longChainNavigation" in {
            // Force many chunk transitions (100+ chunks with chunk size 8)
            val q = new SpmcUnboundedUnsafeQueue[Int](8)
            val n = 8 * 100
            for i <- 0 until n do q.offer(i)
            for i <- 0 until n do
                assert(q.poll() == Maybe(i), s"i=$i")
            assert(q.poll().isEmpty)
        }

        "interleavedOfferPollAcrossChunks" in {
            // Interleave offer/poll to verify old chunks get released properly
            val q = new SpmcUnboundedUnsafeQueue[Int](8)
            for round <- 0 until 50 do
                // Fill one chunk plus a bit
                val n = 10
                for i <- 0 until n do q.offer(round * n + i)
                for i <- 0 until n do
                    assert(q.poll() == Maybe(round * n + i), s"round=$round, i=$i")
            end for
            assert(q.poll().isEmpty)
        }
    }

    "SpmcUnboundedUnsafeQueue-specific concurrent".notJs - {

        "noElementDuplication" in {
            val q            = new SpmcUnboundedUnsafeQueue[Long](16)
            val total        = 20000
            val start        = new CountDownLatch(1)
            val producerDone = new CountDownLatch(1)
            val consumed     = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup          = new AtomicBoolean(false)

            val producer = new Thread(() =>
                start.await()
                var i = 1
                while i <= total do
                    discard(q.offer(i.toLong))
                    i += 1
                producerDone.countDown()
            )
            producer.setDaemon(true)

            val consumers = (0 until 4).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producerDone.getCount > 0 || !q.isEmpty() do
                        q.poll() match
                            case Maybe.Present(v) =>
                                if consumed.put(v, java.lang.Boolean.TRUE) != null then
                                    dup.set(true)
                            case _ => Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producer +: consumers).foreach(_.start())
            start.countDown()
            (producer +: consumers).foreach(_.join())

            var r = q.poll()
            while r.isDefined do
                if consumed.put(r.get, java.lang.Boolean.TRUE) != null then dup.set(true)
                r = q.poll()

            assert(!dup.get(), "Detected duplicate consumption in unbounded SPMC")
            assert(consumed.size == total, s"data loss: consumed=${consumed.size}, total=$total")
        }

        "chunkRefRace" in {
            // Multiple consumers racing to advance consumerChunkRef across many chunks
            val q             = new SpmcUnboundedUnsafeQueue[Long](8)
            val total         = 20000L
            val start         = new CountDownLatch(1)
            val producerDone  = new CountDownLatch(1)
            val consumed      = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup           = new AtomicBoolean(false)
            val consumedCount = new AtomicLong(0)

            val producer = new Thread(() =>
                start.await()
                var i = 1
                while i <= total do
                    discard(q.offer(i.toLong))
                    i += 1
                producerDone.countDown()
            )
            producer.setDaemon(true)

            // More consumers than usual to maximize chunk ref contention
            val consumers = (0 until 8).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producerDone.getCount > 0 || !q.isEmpty() do
                        q.poll() match
                            case Maybe.Present(v) =>
                                consumedCount.incrementAndGet()
                                if consumed.put(v, java.lang.Boolean.TRUE) != null then
                                    dup.set(true)
                            case _ => Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producer +: consumers).foreach(_.start())
            start.countDown()
            (producer +: consumers).foreach(_.join())

            var remaining = 0L
            while q.poll().isDefined do remaining += 1

            assert(!dup.get(), "Chunk ref race produced duplicates")
            assert(
                consumedCount.get() + remaining == total,
                s"Chunk ref race data loss: consumed=${consumedCount.get()}, remaining=$remaining, total=$total"
            )
        }
    }
end SpmcUnboundedUnsafeQueueTest
