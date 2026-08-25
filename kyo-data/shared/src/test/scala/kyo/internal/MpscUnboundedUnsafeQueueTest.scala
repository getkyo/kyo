package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.AllowUnsafe.embrace.danger

class MpscUnboundedUnsafeQueueTest extends UnsafeQueueBaseTest:
    def queueName                               = "MpscUnboundedUnsafeQueue"
    def isBounded                               = false
    def nProducers                              = 3
    def nConsumers                              = 1
    def testSizes                               = Seq(2, 4, 8, 64)
    def makeQueue[A](size: Int): UnsafeQueue[A] = new MpscUnboundedUnsafeQueue[A](size)

    "MpscUnboundedUnsafeQueue-specific" - {
        "singleProducerDegenerateCase" in {
            // MPSC queue with only 1 producer should still work
            val q = new MpscUnboundedUnsafeQueue[Int](4)
            for i <- 0 until 1000 do q.offer(i)
            for i <- 0 until 1000 do
                assert(q.poll() == Maybe(i))
        }
    }

    "MpscUnboundedUnsafeQueue-specific concurrent".notJs - {
        "resizeUnderContention" in {
            val q             = new MpscUnboundedUnsafeQueue[Long](4)
            val perProducer   = 20000
            val producerCount = 3
            val total         = perProducer.toLong * producerCount
            val start         = new CountDownLatch(1)

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var i = 0
                    while i < perProducer do
                        discard(q.offer(pid * 1000000L + i))
                        i += 1
                    end while
                )
                t.setDaemon(true)
                t
            }

            producers.foreach(_.start())
            start.countDown()
            producers.foreach(_.join())

            var count = 0L
            while q.poll().isDefined do count += 1
            assert(count == total, s"Lost elements: expected $total, got $count")
        }

        "concurrentResizeRaceNoDuplicates" in {
            val q             = new MpscUnboundedUnsafeQueue[Long](2)
            val perProducer   = 10000
            val producerCount = 6
            val total         = perProducer * producerCount
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val consumed      = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup           = new AtomicBoolean(false)

            // Many producers with tiny chunk size to maximize resize contention
            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var seq = 0
                    while seq < perProducer do
                        discard(q.offer(pid * 1000000L + seq))
                        seq += 1
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumer = new Thread(() =>
                start.await()
                while producersDone.getCount > 0 || !q.isEmpty() do
                    q.poll() match
                        case Maybe.Present(v) =>
                            if consumed.put(v, java.lang.Boolean.TRUE) != null then
                                dup.set(true)
                        case _ => Thread.`yield`()
                end while
            )
            consumer.setDaemon(true)

            (producers :+ consumer).foreach(_.start())
            start.countDown()
            (producers :+ consumer).foreach(_.join())

            var r = q.poll()
            while r.isDefined do
                if consumed.put(r.get, java.lang.Boolean.TRUE) != null then dup.set(true)
                r = q.poll()

            assert(!dup.get(), "Concurrent resize produced duplicates")
            assert(consumed.size == total, s"data loss: consumed=${consumed.size}, total=$total")
        }

        "concurrentResizeNoDataLoss" in {
            val q             = new MpscUnboundedUnsafeQueue[Long](4)
            val perProducer   = 20000
            val producerCount = 4
            val total         = perProducer.toLong * producerCount
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val consumed      = new AtomicLong(0)

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var i = 0
                    while i < perProducer do
                        discard(q.offer(pid * 100000000L + i))
                        i += 1
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumer = new Thread(() =>
                start.await()
                while producersDone.getCount > 0 || !q.isEmpty() do
                    q.poll() match
                        case Maybe.Present(_) => discard(consumed.incrementAndGet())
                        case _                => Thread.`yield`()
                end while
            )
            consumer.setDaemon(true)

            (producers :+ consumer).foreach(_.start())
            start.countDown()
            (producers :+ consumer).foreach(_.join())

            var remaining = 0L
            while q.poll().isDefined do remaining += 1

            assert(
                consumed.get() + remaining == total,
                s"Resize data loss: consumed=${consumed.get()}, remaining=$remaining, total=$total"
            )
        }
    }
end MpscUnboundedUnsafeQueueTest
