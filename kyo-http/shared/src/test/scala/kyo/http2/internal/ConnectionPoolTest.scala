package kyo.http2.internal

import java.util.concurrent.atomic.AtomicInteger
import kyo.AllowUnsafe
import kyo.Maybe
import kyo.Present
import kyo.Test
import kyo.discard

class ConnectionPoolTest extends Test:

    import AllowUnsafe.embrace.danger
    import ConnectionPool.HostKey

    val key1 = HostKey("host1", 80)
    val key2 = HostKey("host2", 80)

    def mkPool(max: Int = 2): ConnectionPool[String] =
        val discarded = new AtomicInteger(0)
        ConnectionPool.init[String](max, _ => true, _ => discard(discarded.incrementAndGet()))

    def mkPoolWith(
        max: Int = 2,
        isAlive: String => Boolean = _ => true,
        discarded: AtomicInteger = new AtomicInteger(0)
    ): (ConnectionPool[String], AtomicInteger) =
        (ConnectionPool.init[String](max, isAlive, _ => discard(discarded.incrementAndGet())), discarded)

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
            assert(pool.tryReserve(key1) == true)
            assert(pool.tryReserve(key1) == true)
        }

        "fails when at limit" in {
            val pool = mkPool(max = 2)
            pool.tryReserve(key1)
            pool.tryReserve(key1)
            assert(pool.tryReserve(key1) == false)
        }

        "considers idle connections in limit" in {
            val pool = mkPool(max = 2)
            pool.release(key1, "idle1")
            pool.tryReserve(key1)
            assert(pool.tryReserve(key1) == false)
        }

        "independent per host" in {
            val pool = mkPool(max = 1)
            assert(pool.tryReserve(key1) == true)
            assert(pool.tryReserve(key2) == true)
            assert(pool.tryReserve(key1) == false)
            assert(pool.tryReserve(key2) == false)
        }
    }

    "unreserve" - {

        "frees a slot" in {
            val pool = mkPool(max = 1)
            pool.tryReserve(key1)
            assert(pool.tryReserve(key1) == false)
            pool.unreserve(key1)
            assert(pool.tryReserve(key1) == true)
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

    "closeAll" - {

        "discards all idle connections" in {
            val (pool, discarded) = mkPoolWith()
            pool.release(key1, "a")
            pool.release(key1, "b")
            pool.release(key2, "c")
            pool.closeAll()
            assert(discarded.get() == 3)
        }

        "pool is empty after closeAll" in {
            val pool = mkPool()
            pool.release(key1, "a")
            pool.closeAll()
            assert(pool.poll(key1) == Maybe.empty)
        }

        "reserves reset after closeAll" in {
            val pool = mkPool(max = 1)
            pool.tryReserve(key1)
            pool.closeAll()
            assert(pool.tryReserve(key1) == true)
        }
    }

    "reserve + release lifecycle" - {

        "reserve then release frees slot for new reserve" in {
            val pool = mkPool(max = 1)
            pool.tryReserve(key1)
            pool.unreserve(key1)
            pool.release(key1, "conn")
            // idle=1, inFlight=0, total=1 → at limit
            assert(pool.tryReserve(key1) == false)
            // poll removes from idle → idle=0
            pool.poll(key1)
            assert(pool.tryReserve(key1) == true)
        }

        "concurrent scenario: reserve + release + poll" in {
            val pool = mkPool(max = 2)
            // Reserve two slots
            pool.tryReserve(key1)
            pool.tryReserve(key1)
            assert(pool.tryReserve(key1) == false)
            // Unreserve both (simulating connect completion)
            pool.unreserve(key1)
            pool.unreserve(key1)
            // Release connections to idle
            pool.release(key1, "conn1")
            pool.release(key1, "conn2")
            // idle=2, inFlight=0 → at limit
            assert(pool.tryReserve(key1) == false)
            // Poll one
            assert(pool.poll(key1) == Present("conn1"))
            // idle=1, inFlight=0 → can reserve
            assert(pool.tryReserve(key1) == true)
        }
    }

end ConnectionPoolTest
