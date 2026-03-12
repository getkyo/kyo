package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.AllowUnsafe.embrace.danger

class MpmcUnboundedUnsafeQueueTest extends UnsafeQueueBaseTest:
    def queueName  = "MpmcUnboundedUnsafeQueue"
    def isBounded  = false
    def nProducers = 3
    def nConsumers = 3
    def testSizes  = Seq(8, 16, 64)
    // Use non-pooled for standard concurrent tests: pooled mode has producer spin-waits
    // that require consumer liveness, which conflicts with the test stop mechanism.
    // Pooled mode is tested separately below.
    def makeQueue[A](size: Int): UnsafeQueue[A] = new MpmcUnboundedUnsafeQueue[A](size, maxPooledChunks = 0)

    "MpmcUnboundedUnsafeQueue-specific" - {

        "noPooling" in {
            val q = new MpmcUnboundedUnsafeQueue[Int](8, maxPooledChunks = 0)
            for i <- 0 until 100 do q.offer(i)
            for i <- 0 until 100 do
                assert(q.poll() == Maybe(i))
        }

        "withPooling" in {
            val q = new MpmcUnboundedUnsafeQueue[Int](8, maxPooledChunks = 4)
            // Fill and drain multiple rounds to exercise pooling
            for round <- 0 until 5 do
                for i <- 0 until 20 do q.offer(round * 20 + i)
                for i <- 0 until 20 do
                    assert(q.poll() == Maybe(round * 20 + i), s"round=$round, i=$i")
            end for
        }

        "xaddUniqueness" in runNotJS {
            val q        = new MpmcUnboundedUnsafeQueue[Long](8)
            val stop     = new AtomicBoolean(false)
            val start    = new CountDownLatch(1)
            val consumed = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup      = new AtomicBoolean(false)
            val counter  = new AtomicLong(0)

            val producers = (0 until 3).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
                        discard(q.offer(counter.incrementAndGet()))
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 3).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
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

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            Thread.sleep(200)
            stop.set(true)
            (producers ++ consumers).foreach(_.join(10000))

            // Drain remaining
            while q.poll().isDefined do ()

            assert(!dup.get(), "XADD produced duplicate elements")
        }

        "allPooledCombinations" in {
            for cs <- Seq(8, 16, 32) do
                for mp <- Seq(0, 1, 2, 4) do
                    val q = new MpmcUnboundedUnsafeQueue[Int](cs, maxPooledChunks = mp)
                    for i <- 0 until 100 do q.offer(i)
                    for i <- 0 until 100 do
                        assert(q.poll() == Maybe(i), s"chunkSize=$cs, maxPooled=$mp, i=$i")
        }

        "chunkSizeSmall" in {
            // Small chunkSize gets rounded to 8 (min)
            val q = new MpmcUnboundedUnsafeQueue[Int](1)
            for i <- 0 until 100 do q.offer(i)
            for i <- 0 until 100 do
                assert(q.poll() == Maybe(i))
        }

        "pooledConcurrentNoDuplicates" in runNotJS {
            val q        = new MpmcUnboundedUnsafeQueue[Long](8, maxPooledChunks = 4)
            val stop     = new AtomicBoolean(false)
            val start    = new CountDownLatch(1)
            val consumed = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup      = new AtomicBoolean(false)
            val counter  = new AtomicLong(0)

            val producers = (0 until 4).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
                        discard(q.offer(counter.incrementAndGet()))
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 4).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
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

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            Thread.sleep(200)
            stop.set(true)
            (producers ++ consumers).foreach(_.join(10000))

            while q.poll().isDefined do ()
            assert(!dup.get(), "Pooled mode produced duplicate elements")
        }

        "pooledConcurrentNoDataLoss" in runNotJS {
            val q        = new MpmcUnboundedUnsafeQueue[Long](8, maxPooledChunks = 4)
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
            val consumers = (0 until 4).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
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
            Thread.sleep(200)
            stop.set(true)
            (producers ++ consumers).foreach(_.join(10000))

            var remaining = 0L
            while q.poll().isDefined do remaining += 1

            assert(
                consumed.get() + remaining == offered.get(),
                s"Data loss: offered=${offered.get()}, consumed=${consumed.get()}, remaining=$remaining"
            )
        }

        "pooledPeekConsistency" in runNotJS {
            val q       = new MpmcUnboundedUnsafeQueue[Long](8, maxPooledChunks = 4)
            val stop    = new AtomicBoolean(false)
            val start   = new CountDownLatch(1)
            val failure = new AtomicBoolean(false)
            val counter = new AtomicLong(0)

            val producers = (0 until 3).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
                        discard(q.offer(counter.incrementAndGet()))
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 3).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
                        q.peek() match
                            case Maybe.Present(v) =>
                                if v <= 0 then failure.set(true)
                            case _ =>
                        end match
                        q.poll()
                        Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            Thread.sleep(200)
            stop.set(true)
            (producers ++ consumers).foreach(_.join(10000))

            assert(!failure.get(), "Peek returned invalid value in pooled mode")
        }

        "rotationLockContention" in runNotJS {
            // Many producers forcing concurrent chunk allocation via ROTATION lock
            val q        = new MpmcUnboundedUnsafeQueue[Long](8, maxPooledChunks = 0)
            val stop     = new AtomicBoolean(false)
            val start    = new CountDownLatch(1)
            val consumed = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup      = new AtomicBoolean(false)
            val counter  = new AtomicLong(0)

            val producers = (0 until 8).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
                        discard(q.offer(counter.incrementAndGet()))
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 4).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while !stop.get() do
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

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            Thread.sleep(200)
            stop.set(true)
            (producers ++ consumers).foreach(_.join(10000))

            while q.poll().isDefined do ()
            assert(!dup.get(), "Rotation lock contention caused duplicates")
        }

        "backwardWalkProducerChunk" in {
            // Force backward walk by filling multiple chunks sequentially
            for mp <- Seq(0, 2) do
                val q = new MpmcUnboundedUnsafeQueue[Int](8, maxPooledChunks = mp)
                // Fill across 5 chunks (40 elements for chunk size 8)
                for i <- 0 until 40 do q.offer(i)
                for i <- 0 until 40 do
                    assert(q.poll() == Maybe(i), s"maxPooled=$mp, i=$i")
                assert(q.poll().isEmpty)
        }

        "nullElementThrowsNPE" in {
            val q = new MpmcUnboundedUnsafeQueue[String](8)
            assertThrows[NullPointerException] {
                q.offer(null)
            }
        }
    }
end MpmcUnboundedUnsafeQueueTest
