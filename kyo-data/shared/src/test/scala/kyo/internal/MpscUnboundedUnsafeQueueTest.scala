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
        "resizeUnderContention" in runNotJS {
            val q     = new MpscUnboundedUnsafeQueue[Long](4)
            val stop  = new AtomicBoolean(false)
            val start = new CountDownLatch(1)
            val total = new AtomicLong(0)

            val producers = (0 until 3).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var i = 0L
                    while !stop.get() do
                        q.offer(pid * 1000000L + i)
                        total.incrementAndGet()
                        i += 1
                    end while
                )
                t.setDaemon(true)
                t
            }

            producers.foreach(_.start())
            start.countDown()
            Thread.sleep(300)
            stop.set(true)
            producers.foreach(_.join(10000))

            // Drain and count
            var count = 0L
            while q.poll().isDefined do count += 1
            assert(count == total.get(), s"Lost elements: expected ${total.get()}, got $count")
        }

        "singleProducerDegenerateCase" in {
            // MPSC queue with only 1 producer should still work
            val q = new MpscUnboundedUnsafeQueue[Int](4)
            for i <- 0 until 1000 do q.offer(i)
            for i <- 0 until 1000 do
                assert(q.poll() == Maybe(i))
        }

        "concurrentResizeRaceNoDuplicates" in runNotJS {
            val q        = new MpscUnboundedUnsafeQueue[Long](2)
            val stop     = new AtomicBoolean(false)
            val start    = new CountDownLatch(1)
            val consumed = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup      = new AtomicBoolean(false)
            val counter  = new AtomicLong(0)

            // Many producers with tiny chunk size to maximize resize contention
            val producers = (0 until 6).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
                        discard(q.offer(counter.incrementAndGet()))
                )
                t.setDaemon(true)
                t
            }
            val consumer = new Thread(() =>
                start.await()
                while !stop.get() do
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
            Thread.sleep(200)
            stop.set(true)
            (producers :+ consumer).foreach(_.join(10000))

            // Drain remaining
            while q.poll() match
                    case Maybe.Present(v) =>
                        if consumed.put(v, java.lang.Boolean.TRUE) != null then
                            dup.set(true)
                        true
                    case _ => false
            do ()
            end while

            assert(!dup.get(), "Concurrent resize produced duplicates")
        }

        "concurrentResizeNoDataLoss" in runNotJS {
            val q        = new MpscUnboundedUnsafeQueue[Long](4)
            val stop     = new AtomicBoolean(false)
            val start    = new CountDownLatch(1)
            val offered  = new AtomicLong(0)
            val consumed = new AtomicLong(0)

            val producers = (0 until 4).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
                        discard(q.offer(offered.incrementAndGet()))
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
                s"Resize data loss: offered=${offered.get()}, consumed=${consumed.get()}, remaining=$remaining"
            )
        }
    }
end MpscUnboundedUnsafeQueueTest
