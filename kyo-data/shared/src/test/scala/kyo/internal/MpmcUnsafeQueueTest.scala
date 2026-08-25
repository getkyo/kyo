package kyo.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.AllowUnsafe.embrace.danger

class MpmcUnsafeQueueTest extends UnsafeQueueBaseTest:
    def queueName                               = "MpmcUnsafeQueue"
    def isBounded                               = true
    def nProducers                              = 3
    def nConsumers                              = 3
    def testSizes                               = Seq(4, 16, 128)
    def makeQueue[A](size: Int): UnsafeQueue[A] = new MpmcUnsafeQueue[A](size)

    "MpmcUnsafeQueue-specific" - {
        "minCapacityIsTwo" in {
            val q = new MpmcUnsafeQueue[Int](1)
            assert(q.capacity >= 2)
        }

        "capacity2EdgeCase" in {
            val q = new MpmcUnsafeQueue[Int](2)
            assert(q.capacity == 2)
            q.offer(1)
            q.offer(2)
            assert(!q.offer(3))
            assert(q.poll() == Maybe(1))
            assert(q.poll() == Maybe(2))
            assert(q.poll().isEmpty)
        }

        "batchDrainSequential" in {
            for cap <- Seq(4, 8, 16, 64) do
                val q = new MpmcUnsafeQueue[Int](cap)
                val n = q.capacity
                for i <- 0 until n do q.offer(i)
                val buf = scala.collection.mutable.ArrayBuffer[Int]()
                val r   = q.drain(v => buf += v, n)
                assert(r == n, s"cap=$cap: drained $r, expected $n")
                assert(buf.toSeq == (0 until n), s"cap=$cap: FIFO violation")
                assert(q.isEmpty())
        }

        "capacity2WrapAround" in {
            val q = new MpmcUnsafeQueue[Int](2)
            assert(q.capacity == 2)
            // Wrap around at minimum capacity multiple times
            for round <- 0 until 100 do
                q.offer(round * 2)
                q.offer(round * 2 + 1)
                assert(!q.offer(999), s"round=$round: should be full")
                assert(q.isFull(), s"round=$round: should be full")
                assert(q.poll() == Maybe(round * 2), s"round=$round first")
                assert(q.poll() == Maybe(round * 2 + 1), s"round=$round second")
                assert(q.isEmpty(), s"round=$round: should be empty")
            end for
        }
    }

    "MpmcUnsafeQueue-specific concurrent".notJs - {
        "highContentionMPMC" in {
            val q             = new MpmcUnsafeQueue[Long](16)
            val perProducer   = 5000
            val producerCount = 4
            val total         = perProducer.toLong * producerCount
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val offered       = new AtomicLong(0)
            val consumed      = new AtomicLong(0)

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var i = 0
                    while i < perProducer do
                        if q.offer(pid.toLong * 10000000L + i) then
                            i += 1
                            discard(offered.incrementAndGet())
                        else Thread.`yield`()
                    end while
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 4).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producersDone.getCount > 0 do
                        q.poll() match
                            case Maybe.Present(_) => discard(consumed.incrementAndGet())
                            case _                => Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            (producers ++ consumers).foreach(_.join())

            var remaining = 0L
            while q.poll().isDefined do remaining += 1

            assert(offered.get() == total, s"expected $total offered, got ${offered.get()}")
            assert(
                consumed.get() + remaining == total,
                s"data loss: consumed=${consumed.get()}, remaining=$remaining, total=$total"
            )
        }

        "batchDrainConcurrent" in {
            val q             = new MpmcUnsafeQueue[Long](16)
            val perProducer   = 5000
            val producerCount = 4
            val total         = perProducer.toLong * producerCount
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val offered       = new AtomicLong(0)
            val consumed      = new AtomicLong(0)

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var i = 0
                    while i < perProducer do
                        if q.offer(pid.toLong * 100000000L + i) then
                            i += 1
                            discard(offered.incrementAndGet())
                        else Thread.`yield`()
                    end while
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 2).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producersDone.getCount > 0 do
                        val n = q.drain(_ => (), 4)
                        consumed.addAndGet(n)
                        if n == 0 then Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            (producers ++ consumers).foreach(_.join())

            var remaining = 0L
            while q.poll().isDefined do remaining += 1

            assert(offered.get() == total, s"expected $total offered, got ${offered.get()}")
            assert(
                consumed.get() + remaining == total,
                s"Batch drain data loss: consumed=${consumed.get()}, remaining=$remaining, total=$total"
            )
        }
    }
end MpmcUnsafeQueueTest
