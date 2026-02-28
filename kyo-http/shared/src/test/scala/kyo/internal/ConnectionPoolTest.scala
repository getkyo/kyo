package kyo.internal

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import scala.annotation.tailrec

class ConnectionPoolTest extends Test:

    import AllowUnsafe.embrace.danger
    import ConnectionPool.HostKey

    val key1 = HostKey("host1", 80)
    val key2 = HostKey("host2", 80)

    def mkPool(max: Int = 2): ConnectionPool[String] =
        val discarded = new AtomicInteger(0)
        ConnectionPool.init[String](max, kyo.Duration.Infinity, _ => true, _ => discard(discarded.incrementAndGet()))

    def mkPoolWith(
        max: Int = 2,
        isAlive: String => Boolean = _ => true,
        discarded: AtomicInteger = new AtomicInteger(0)
    ): (ConnectionPool[String], AtomicInteger) =
        (ConnectionPool.init[String](max, kyo.Duration.Infinity, isAlive, _ => discard(discarded.incrementAndGet())), discarded)

    "poll" - {

        "returns empty when no idle connections" in {
            val pool = mkPool()
            assert(pool.poll(key1) == Maybe.empty)
        }

        "returns released connection" in {
            val pool = mkPool()
            pool.release(key1, "conn1")
            assert(pool.poll(key1) == Present("conn1"))
        }

        "returns empty after all consumed" in {
            val pool = mkPool()
            pool.release(key1, "conn1")
            pool.poll(key1)
            assert(pool.poll(key1) == Maybe.empty)
        }

        "discards stale connections" in {
            val (pool, discarded) = mkPoolWith(isAlive = _ != "stale")
            pool.release(key1, "stale")
            pool.release(key1, "fresh")
            assert(pool.poll(key1) == Present("fresh"))
            assert(discarded.get() == 1)
        }

        "discards all stale returns empty" in {
            val (pool, discarded) = mkPoolWith(isAlive = _ => false)
            pool.release(key1, "a")
            pool.release(key1, "b")
            assert(pool.poll(key1) == Maybe.empty)
            assert(discarded.get() == 2)
        }

        "isolates hosts" in {
            val pool = mkPool()
            pool.release(key1, "conn1")
            pool.release(key2, "conn2")
            assert(pool.poll(key1) == Present("conn1"))
            assert(pool.poll(key2) == Present("conn2"))
            assert(pool.poll(key1) == Maybe.empty)
        }
    }

    "tryReserve" - {

        "succeeds when under limit" in {
            val pool = mkPool(max = 2)
            assert(pool.tryReserve(key1))
            assert(pool.tryReserve(key1))
        }

        "fails when at limit" in {
            val pool = mkPool(max = 2)
            assert(pool.tryReserve(key1))
            assert(pool.tryReserve(key1))
            assert(!pool.tryReserve(key1))
        }

        "considers idle connections in limit" in {
            val pool = mkPool(max = 2)
            pool.release(key1, "idle1")
            assert(pool.tryReserve(key1))
            assert(!pool.tryReserve(key1))
        }

        "independent per host" in {
            val pool = mkPool(max = 2)
            assert(pool.tryReserve(key1))
            assert(pool.tryReserve(key1))
            assert(pool.tryReserve(key2))
            assert(pool.tryReserve(key2))
            assert(!pool.tryReserve(key1))
            assert(!pool.tryReserve(key2))
        }
    }

    "unreserve" - {

        "frees a slot" in {
            val pool = mkPool(max = 2)
            assert(pool.tryReserve(key1))
            assert(pool.tryReserve(key1))
            assert(!pool.tryReserve(key1))
            pool.unreserve(key1)
            assert(pool.tryReserve(key1))
        }
    }

    "release" - {

        "connection can be polled back" in {
            val pool = mkPool()
            pool.release(key1, "conn1")
            assert(pool.poll(key1) == Present("conn1"))
        }

        "FIFO order" in {
            val pool = mkPool()
            pool.release(key1, "first")
            pool.release(key1, "second")
            assert(pool.poll(key1) == Present("first"))
            assert(pool.poll(key1) == Present("second"))
        }
    }

    "close" - {

        "returns all idle connections" in {
            val pool = mkPool()
            pool.release(key1, "a")
            pool.release(key1, "b")
            pool.release(key2, "c")
            val conns = pool.close()
            assert(conns.size == 3)
        }

        "pool is empty after close" in {
            val pool = mkPool()
            pool.release(key1, "a")
            discard(pool.close())
            assert(pool.poll(key1) == Maybe.empty)
        }

        "operations are rejected after close" in {
            val pool = mkPool(max = 2)
            discard(pool.close())
            assert(pool.poll(key1) == Maybe.empty)
            assert(!pool.tryReserve(key1))
        }
    }

    "reserve + release lifecycle" - {

        "reserve then release frees slot for new reserve" in {
            val pool = mkPool(max = 2)
            assert(pool.tryReserve(key1))
            pool.unreserve(key1)
            pool.release(key1, "conn1")
            pool.release(key1, "conn2")
            // idle=2, inFlight=0, total=2 → at limit
            assert(!pool.tryReserve(key1))
            // poll removes from idle → idle=1
            pool.poll(key1)
            assert(pool.tryReserve(key1))
        }

        "concurrent scenario: reserve + release + poll" in {
            val pool = mkPool(max = 2)
            // Reserve two slots
            assert(pool.tryReserve(key1))
            assert(pool.tryReserve(key1))
            assert(!pool.tryReserve(key1))
            // Unreserve both (simulating connect completion)
            pool.unreserve(key1)
            pool.unreserve(key1)
            // Release connections to idle
            pool.release(key1, "conn1")
            pool.release(key1, "conn2")
            // idle=2, inFlight=0 → at limit
            assert(!pool.tryReserve(key1))
            // Poll one
            assert(pool.poll(key1) == Present("conn1"))
            // idle=1, inFlight=0 → can reserve
            assert(pool.tryReserve(key1))
        }
    }

    "idle timeout eviction" - {

        def mkPoolWithTimeout(
            max: Int = 2,
            timeoutNanos: Long = 1000000L, // 1ms
            discarded: AtomicInteger = new AtomicInteger(0)
        ): (ConnectionPool[String], AtomicInteger) =
            (
                ConnectionPool.init[String](
                    max,
                    kyo.Duration.fromNanos(timeoutNanos),
                    _ => true,
                    _ => discard(discarded.incrementAndGet())
                ),
                discarded
            )

        "evicts expired connection on poll" in run {
            val (pool, discarded) = mkPoolWithTimeout(timeoutNanos = 1L) // 1 nanosecond
            pool.release(key1, "old")
            Async.sleep(1.millis).andThen { // ensure at least 1ms passes
                assert(pool.poll(key1) == Maybe.empty)
                assert(discarded.get() == 1)
            }
        }

        "returns fresh connection within timeout" in {
            val (pool, discarded) = mkPoolWithTimeout(timeoutNanos = 60000000000L) // 60 seconds
            pool.release(key1, "fresh")
            assert(pool.poll(key1) == Present("fresh"))
            assert(discarded.get() == 0)
        }

        "evicts expired, returns fresh (FIFO order)" in run {
            val (pool2, discarded2) = mkPoolWithTimeout(max = 2, timeoutNanos = 5000000000L) // 5 seconds
            pool2.release(key1, "old")
            Async.sleep(50.millis).andThen { // 50ms — well within 5s timeout
                pool2.release(key1, "new")
                // Both should be fresh
                assert(pool2.poll(key1) == Present("old"))
                assert(pool2.poll(key1) == Present("new"))
            }
        }

        "Duration.Infinity means no eviction" in run {
            val pool = mkPool(max = 2) // uses Duration.Infinity
            pool.release(key1, "conn")
            Async.sleep(10.millis).andThen {
                assert(pool.poll(key1) == Present("conn"))
            }
        }

        "all expired returns empty" in run {
            val (pool, discarded) = mkPoolWithTimeout(max = 2, timeoutNanos = 1L)
            pool.release(key1, "a")
            pool.release(key1, "b")
            Async.sleep(1.millis).andThen {
                assert(pool.poll(key1) == Maybe.empty)
                assert(discarded.get() == 2)
            }
        }
    }

    "ring buffer capacity" - {

        "minimum capacity" in {
            val pool = mkPool(max = 2)
            pool.release(key1, "a")
            assert(pool.poll(key1) == Present("a"))
            assert(pool.poll(key1) == Maybe.empty)
        }

        "rejects capacity < 2" in {
            assertThrows[IllegalArgumentException] {
                ConnectionPool.init[String](1, kyo.Duration.Infinity, _ => true, _ => ())
            }
        }

        "discards when ring is full" in {
            val (pool, discarded) = mkPoolWith(max = 2)
            pool.release(key1, "a")
            pool.release(key1, "b")
            pool.release(key1, "c") // ring is full, "c" should be discarded
            assert(discarded.get() == 1)
            assert(pool.poll(key1) == Present("a"))
            assert(pool.poll(key1) == Present("b"))
        }

        "ring wraps around correctly" in {
            val pool = mkPool(max = 2)
            // Fill and drain multiple times to advance head/tail beyond capacity
            for _ <- 1 to 10 do
                pool.release(key1, "a")
                pool.release(key1, "b")
                assert(pool.poll(key1) == Present("a"))
                assert(pool.poll(key1) == Present("b"))
            end for
            assert(pool.poll(key1) == Maybe.empty)
        }

        "ring wraps many times maintaining FIFO" in {
            val pool = mkPool(max = 3)
            for i <- 1 to 100 do
                pool.release(key1, s"conn-$i")
                assert(pool.poll(key1) == Present(s"conn-$i"))
            succeed
        }

        "max cap validation" in {
            assertThrows[IllegalArgumentException] {
                ConnectionPool.init[String](1025, kyo.Duration.Infinity, _ => true, _ => ())
            }
        }
    }

    "close with inFlight" - {

        "close returns tracked in-flight connections" in {
            val pool = mkPool(max = 4)
            assert(pool.tryReserve(key1))
            pool.track(key1, "inflight1")
            pool.track(key1, "inflight2")
            val conns = pool.close()
            assert(conns.toSet == Set("inflight1", "inflight2"))
        }

        "close returns both idle and in-flight" in {
            val pool = mkPool(max = 4)
            pool.release(key1, "idle")
            pool.track(key1, "inflight")
            val conns = pool.close()
            assert(conns.toSet == Set("idle", "inflight"))
        }
    }

    "concurrency" - {

        val nFibers     = 8
        val opsPerFiber = 10000

        "multi-producer: no connections lost" in run {
            val capacity  = 64
            val discarded = new AtomicInteger(0)
            val pool  = ConnectionPool.init[String](capacity, kyo.Duration.Infinity, _ => true, _ => discard(discarded.incrementAndGet()))
            val total = nFibers * opsPerFiber

            Async.fill(nFibers, nFibers) {
                Sync.Unsafe.defer {
                    for i <- 0 until opsPerFiber do
                        pool.release(key1, s"$i")
                }
            }.map { _ =>
                @tailrec def drain(count: Int): Int =
                    pool.poll(key1) match
                        case Present(_) => drain(count + 1)
                        case _          => count
                val polled = drain(0)
                assert(polled + discarded.get() == total)
                assert(polled <= capacity)
            }
        }

        "multi-consumer: no duplicates" in run {
            val capacity  = 64
            val discarded = new AtomicInteger(0)
            val pool   = ConnectionPool.init[String](capacity, kyo.Duration.Infinity, _ => true, _ => discard(discarded.incrementAndGet()))
            val seen   = Collections.newSetFromMap(new ConcurrentHashMap[String, java.lang.Boolean]())
            val polled = new AtomicInteger(0)

            for i <- 0 until capacity do
                pool.release(key1, s"conn-$i")

            Async.fill(nFibers, nFibers) {
                Sync.Unsafe.defer {
                    @tailrec def loop(): Unit =
                        pool.poll(key1) match
                            case Present(v) =>
                                if !seen.add(v) then throw new AssertionError(s"duplicate: $v")
                                discard(polled.incrementAndGet())
                                loop()
                            case _ => ()
                    loop()
                }
            }.map { _ =>
                assert(polled.get() == capacity)
            }
        }

        "mixed producer-consumer stress" in run {
            val capacity  = 32
            val discarded = new AtomicInteger(0)
            val pool = ConnectionPool.init[String](capacity, kyo.Duration.Infinity, _ => true, _ => discard(discarded.incrementAndGet()))
            val produced = new AtomicInteger(0)
            val consumed = new AtomicInteger(0)

            val producers = Async.fill(nFibers / 2, nFibers / 2) {
                Sync.Unsafe.defer {
                    for i <- 0 until opsPerFiber do
                        pool.release(key1, s"$i")
                        discard(produced.incrementAndGet())
                }
            }

            val consumers = Async.fill(nFibers / 2, nFibers / 2) {
                Sync.Unsafe.defer {
                    for _ <- 0 until opsPerFiber do
                        pool.poll(key1) match
                            case Present(_) => discard(consumed.incrementAndGet())
                            case _          => ()
                }
            }

            producers.map { _ =>
                consumers.map { _ =>
                    @tailrec def drain(count: Int): Int =
                        pool.poll(key1) match
                            case Present(_) => drain(count + 1)
                            case _          => count
                    val remaining = drain(0)
                    assert(produced.get() == consumed.get() + remaining + discarded.get())
                }
            }
        }
    }

    "edge cases" - {

        "release after close discards connection" in {
            val (pool, discarded) = mkPoolWith(max = 2)
            pool.release(key1, "before")
            discard(pool.close())
            pool.release(key1, "after")
            assert(discarded.get() == 1) // "after" discarded by release on closed pool
            assert(pool.poll(key1) == Maybe.empty)
        }

        "multiple close calls are idempotent" in {
            val pool = mkPool(max = 2)
            pool.release(key1, "a")
            pool.release(key1, "b")
            val first  = pool.close()
            val second = pool.close()
            val third  = pool.close()
            assert(first.size == 2)
            assert(second.isEmpty)
            assert(third.isEmpty)
        }

        "capacity 2 full fill-drain cycles" in {
            val (pool, discarded) = mkPoolWith(max = 2)
            for _ <- 1 to 50 do
                pool.release(key1, "a")
                pool.release(key1, "b")
                // Ring full — "c" should be discarded
                pool.release(key1, "c")
                assert(pool.poll(key1) == Present("a"))
                assert(pool.poll(key1) == Present("b"))
                assert(pool.poll(key1) == Maybe.empty)
            end for
            assert(discarded.get() == 50)
        }
    }

end ConnectionPoolTest
