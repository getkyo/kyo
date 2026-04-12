package kyo.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
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
        "manyProducersSingleConsumer" in runNotJS {
            val q        = new MpscUnsafeQueue[Long](64)
            val stop     = new AtomicBoolean(false)
            val start    = new CountDownLatch(1)
            val offered  = Array.fill(8)(new AtomicLong(0))
            val consumed = new java.util.concurrent.ConcurrentLinkedQueue[Long]()

            val producers = (0 until 8).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
                        val v = pid * 1000000L + offered(pid).incrementAndGet()
                        if !q.offer(v) then Thread.`yield`()
                )
                t.setDaemon(true)
                t
            }
            val consumer = new Thread(() =>
                start.await()
                while !stop.get() do
                    q.poll() match
                        case Maybe.Present(v) => discard(consumed.add(v))
                        case _                => Thread.`yield`()
                end while
            )
            consumer.setDaemon(true)

            (producers :+ consumer).foreach(_.start())
            start.countDown()
            Thread.sleep(1000)
            stop.set(true)
            (producers :+ consumer).foreach(_.join(10000))

            // Drain remaining
            var r = q.poll()
            while r.isDefined do
                consumed.add(r.get)
                r = q.poll()

            // Verify per-producer FIFO
            val byProducer = new Array[scala.collection.mutable.ArrayBuffer[Long]](8)
            for i <- 0 until 8 do byProducer(i) = scala.collection.mutable.ArrayBuffer()
            val it = consumed.iterator()
            while it.hasNext do
                val v   = it.next()
                val pid = (v / 1000000L).toInt
                byProducer(pid) += v % 1000000
            end while

            for pid <- 0 until 8 do
                val seqs = byProducer(pid)
                for i <- 1 until seqs.size do
                    assert(seqs(i) > seqs(i - 1), s"Producer $pid: FIFO violation at $i")
            end for
        }

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

        "capacity2Concurrent" in runNotJS {
            val q        = new MpscUnsafeQueue[Long](2)
            val stop     = new AtomicBoolean(false)
            val start    = new CountDownLatch(1)
            val offered  = new AtomicLong(0)
            val consumed = new AtomicLong(0)

            val producers = (0 until 4).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var v = pid.toLong * 100000000L
                    while !stop.get() do
                        v += 1
                        if q.offer(v) then discard(offered.incrementAndGet())
                        else Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }
            val consumer = new Thread(() =>
                start.await()
                while !stop.get() do
                    q.poll() match
                        case Maybe.Present(_) => discard(consumed.incrementAndGet())
                        case _                => Thread.`yield`()
                end while
            )
            consumer.setDaemon(true)

            (producers :+ consumer).foreach(_.start())
            start.countDown()
            Thread.sleep(200)
            stop.set(true)
            (producers :+ consumer).foreach(_.join(10000))

            var remaining = 0L
            while q.poll().isDefined do remaining += 1

            assert(
                consumed.get() + remaining == offered.get(),
                s"Capacity-2 data loss: offered=${offered.get()}, consumed=${consumed.get()}, remaining=$remaining"
            )
        }
    }
end MpscUnsafeQueueTest
