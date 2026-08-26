package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.AllowUnsafe.embrace.danger

class SpmcUnsafeQueueTest extends UnsafeQueueBaseTest:
    def queueName                               = "SpmcUnsafeQueue"
    def isBounded                               = true
    def nProducers                              = 1
    def nConsumers                              = 3
    def testSizes                               = Seq(4, 16, 128)
    def makeQueue[A](size: Int): UnsafeQueue[A] = new SpmcUnsafeQueue[A](size)

    "SpmcUnsafeQueue-specific" - {
        "capacity2Sequential" in {
            val q = new SpmcUnsafeQueue[Int](2)
            assert(q.capacity == 2)
            // Fill/drain cycles at minimum capacity
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

    "SpmcUnsafeQueue-specific concurrent".notJs - {
        "noElementDuplication" in {
            val q            = new SpmcUnsafeQueue[Long](128)
            val total        = 20000
            val start        = new CountDownLatch(1)
            val producerDone = new CountDownLatch(1)
            val consumed     = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup          = new AtomicBoolean(false)

            val producer = new Thread(() =>
                start.await()
                var i = 0
                while i < total do
                    if q.offer(i.toLong) then i += 1
                    else Thread.`yield`()
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

            assert(!dup.get(), "Detected duplicate consumption")
            assert(consumed.size == total, s"data loss: consumed=${consumed.size}, total=$total")
        }

        "capacity2Concurrent" in {
            val q            = new SpmcUnsafeQueue[Long](2)
            val total        = 20000
            val start        = new CountDownLatch(1)
            val producerDone = new CountDownLatch(1)
            val consumed     = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup          = new AtomicBoolean(false)

            val producer = new Thread(() =>
                start.await()
                var i = 0
                while i < total do
                    if q.offer(i.toLong) then i += 1
                    else Thread.`yield`()
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

            assert(!dup.get(), "Capacity-2 concurrent produced duplicates")
            assert(consumed.size == total, s"data loss: consumed=${consumed.size}, total=$total")
        }

        "producerBubbleSpin" in {
            // Exercise the producer bubble spin: consumer CAS'd but hasn't nulled slot yet.
            // Use capacity=2 to maximize the chance of hitting this path.
            val q            = new SpmcUnsafeQueue[Long](2)
            val total        = 20000L
            val start        = new CountDownLatch(1)
            val producerDone = new CountDownLatch(1)
            val consumed     = new AtomicLong(0)

            val producer = new Thread(() =>
                start.await()
                var i = 0
                while i < total do
                    if q.offer(i.toLong) then i += 1
                    else Thread.`yield`()
                producerDone.countDown()
            )
            producer.setDaemon(true)

            val consumers = (0 until 4).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producerDone.getCount > 0 || !q.isEmpty() do
                        q.poll() match
                            case Maybe.Present(_) => discard(consumed.incrementAndGet())
                            case _                => Thread.`yield`()
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

            assert(
                consumed.get() + remaining == total,
                s"Bubble spin data loss: consumed=${consumed.get()}, remaining=$remaining, total=$total"
            )
        }
    }
end SpmcUnsafeQueueTest
