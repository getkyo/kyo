package kyo.net.internal.client

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.net.NetAddress
import kyo.net.Test

class ConnectionPoolTest extends Test:

    import AllowUnsafe.embrace.danger

    val key1 = NetAddress.Tcp("host1", 80)
    val key2 = NetAddress.Tcp("host2", 80)

    def mkPool(max: Int = 2): ConnectionPool[String] =
        ConnectionPool.init[String](max, Duration.Infinity, _ => true, _ => ())

    "poll" - {
        "returns empty when no idle connections" in {
            val pool   = mkPool()
            val result = pool.poll(key1)
            assert(result == Maybe.empty)
        }

        "returns released connection" in {
            val pool = mkPool()
            pool.release(key1, "conn1")
            val result = pool.poll(key1)
            assert(result == Present("conn1"))
        }
    }

    "release" - {
        "discards when full" in {
            val discardCount = new AtomicInteger(0)
            val pool = ConnectionPool.init[String](
                2,
                Duration.Infinity,
                _ => true,
                _ => discard(discardCount.incrementAndGet())
            )
            pool.release(key1, "a")
            pool.release(key1, "b")
            pool.release(key1, "c")
            assert(discardCount.get() == 1)
        }
    }

    "tryReserve" - {
        "returns true when under limit" in {
            val pool     = mkPool()
            val reserved = pool.tryReserve(key1)
            assert(reserved)
        }

        "returns false when at limit" in {
            val pool = mkPool(2)
            val r1   = pool.tryReserve(key1)
            assert(r1)
            val r2 = pool.tryReserve(key1)
            assert(r2)
            val r3 = pool.tryReserve(key1)
            assert(!r3)
        }
    }

    "unreserve" - {
        // kyo-net adds unreserve (not in the kyo-http surface): releasing an in-flight slot frees
        // capacity so a subsequent tryReserve succeeds again.
        "frees a reserved slot so tryReserve succeeds again" in {
            val pool = mkPool(2)
            assert(pool.tryReserve(key1))
            assert(pool.tryReserve(key1))
            // At the limit now: the next reserve must fail.
            assert(!pool.tryReserve(key1))
            // Release one in-flight slot.
            pool.unreserve(key1)
            // Capacity freed: a reserve must now succeed.
            assert(pool.tryReserve(key1))
            // And we are at the limit again.
            assert(!pool.tryReserve(key1))
        }
    }

    "close" - {
        "returns idle connections" in {
            val pool = mkPool()
            pool.release(key1, "a")
            pool.release(key1, "b")
            val conns = pool.close()
            assert(conns.size == 2)
        }

        "returns empty when no idle connections" in {
            val pool  = mkPool()
            val conns = pool.close()
            assert(conns.size == 0)
        }
    }

    "isAlive check during poll" in {
        val discardCount = new AtomicInteger(0)
        val pool = ConnectionPool.init[String](
            2,
            Duration.Infinity,
            conn => conn != "dead",
            _ => discard(discardCount.incrementAndGet())
        )
        pool.release(key1, "dead")
        pool.release(key1, "alive")
        val result = pool.poll(key1)
        assert(result == Present("alive"))
        assert(discardCount.get() == 1)
    }

    "idle-timeout eviction during poll" in {
        // Deterministic without sleep: a zero idle timeout means any positive elapsed time evicts.
        // nanoTime is monotonic, so the elapsed between release (timestamp) and poll (re-read) is
        // strictly positive, reliably exceeding the zero timeout. The expired conn is discarded and
        // poll continues to the next slot.
        val discardCount = new AtomicInteger(0)
        val pool = ConnectionPool.init[String](
            2,
            Duration.Zero,
            _ => true,
            _ => discard(discardCount.incrementAndGet())
        )
        pool.release(key1, "stale1")
        pool.release(key1, "stale2")
        // Both released conns are immediately past the zero idle timeout: poll evicts+discards each
        // and finds no live conn left, returning empty.
        val result = pool.poll(key1)
        assert(result == Maybe.empty)
        assert(discardCount.get() == 2)
    }

end ConnectionPoolTest
