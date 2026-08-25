package kyo.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.AllowUnsafe.embrace.danger

class MpscUnsafeQueueTest extends UnsafeQueueBaseTest:
    def queueName                               = "MpscUnsafeQueue"
    def isBounded                               = true
    def nProducers                              = 3
    def nConsumers                              = 1
    def testSizes                               = Seq(4, 16, 128)
    def makeQueue[A](size: Int): UnsafeQueue[A] = new MpscUnsafeQueue[A](size)

    "MpscUnsafeQueue-specific" - {
        "capacity2Sequential" in {
            val q = new MpscUnsafeQueue[Int](2)
            assert(q.capacity == 2)
            for round <- 0 until 50 do
                q.offer(round * 2)
                q.offer(round * 2 + 1)
                assert(!q.offer(999), s"round=$round: should be full")
                assert(q.poll() == Maybe(round * 2), s"round=$round")
                assert(q.poll() == Maybe(round * 2 + 1), s"round=$round")
                assert(q.poll().isEmpty)
            end for
        }
    }

    "MpscUnsafeQueue-specific concurrent".notJs - {
        "manyProducersSingleConsumer" in {
            val q             = new MpscUnsafeQueue[Long](64)
            val perProducer   = 2000
            val producerCount = 8
            val total         = perProducer * producerCount
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val consumed      = new java.util.concurrent.ConcurrentLinkedQueue[Long]()

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var seq = 1
                    while seq <= perProducer do
                        if q.offer(pid * 1000000L + seq) then seq += 1
                        else Thread.`yield`()
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumer = new Thread(() =>
                start.await()
                while producersDone.getCount > 0 do
                    q.poll() match
                        case Maybe.Present(v) => discard(consumed.add(v))
                        case _                => Thread.`yield`()
                end while
            )
            consumer.setDaemon(true)

            (producers :+ consumer).foreach(_.start())
            start.countDown()
            (producers :+ consumer).foreach(_.join())

            var r = q.poll()
            while r.isDefined do
                consumed.add(r.get)
                r = q.poll()

            // Per-producer FIFO: each producer's items must be consumed strictly in offer order, and all present.
            val byProducer = Array.fill(producerCount)(scala.collection.mutable.ArrayBuffer.empty[Long])
            val it         = consumed.iterator()
            while it.hasNext do
                val v = it.next()
                byProducer((v / 1000000L).toInt) += v % 1000000
            for pid <- 0 until producerCount do
                val seqs = byProducer(pid)
                assert(seqs.size == perProducer, s"Producer $pid: expected $perProducer items, got ${seqs.size}")
                for i <- 1 until seqs.size do
                    assert(seqs(i) > seqs(i - 1), s"Producer $pid: FIFO violation at $i")
            end for
            assert(consumed.size == total, s"data loss: consumed=${consumed.size}, total=$total")
        }

        "capacity2Concurrent" in {
            val q             = new MpscUnsafeQueue[Long](2)
            val perProducer   = 5000
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
                        if q.offer(pid.toLong * 100000000L + i) then i += 1
                        else Thread.`yield`()
                    end while
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumer = new Thread(() =>
                start.await()
                while producersDone.getCount > 0 do
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
                s"Capacity-2 data loss: consumed=${consumed.get()}, remaining=$remaining, total=$total"
            )
        }
    }
end MpscUnsafeQueueTest
