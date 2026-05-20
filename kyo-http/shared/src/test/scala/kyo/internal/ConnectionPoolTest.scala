package kyo.internal

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.internal.client.*

class ConnectionPoolTest extends Test:

    import AllowUnsafe.embrace.danger

    val key1 = HttpAddress.Tcp("host1", 80)
    val key2 = HttpAddress.Tcp("host2", 80)

    def mkPool(max: Int = 2): ConnectionPool[String] =
        ConnectionPool.init[String](max, kyo.Duration.Infinity, _ => true, _ => ())

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
                kyo.Duration.Infinity,
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
            kyo.Duration.Infinity,
            conn => conn != "dead",
            _ => discard(discardCount.incrementAndGet())
        )
        pool.release(key1, "dead")
        pool.release(key1, "alive")
        val result = pool.poll(key1)
        assert(result == Present("alive"))
        assert(discardCount.get() == 1)
    }

end ConnectionPoolTest
