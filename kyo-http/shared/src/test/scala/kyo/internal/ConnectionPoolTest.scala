package kyo.internal

import java.util.concurrent.atomic.AtomicInteger
import kyo.*

class ConnectionPoolTest extends Test:

    val key1 = HttpAddress.Tcp("host1", 80)
    val key2 = HttpAddress.Tcp("host2", 80)

    def mkPool(max: Int = 2)(using Frame): ConnectionPool[String] < Sync =
        ConnectionPool.init[String](max, kyo.Duration.Infinity, _ => Sync.defer(true), _ => Kyo.unit)

    "poll" - {
        "returns empty when no idle connections" in run {
            mkPool().map { pool =>
                pool.poll(key1).map { result =>
                    assert(result == Maybe.empty)
                }
            }
        }

        "returns released connection" in run {
            mkPool().map { pool =>
                pool.release(key1, "conn1").andThen {
                    pool.poll(key1).map { result =>
                        assert(result == Present("conn1"))
                    }
                }
            }
        }
    }

    "release" - {
        "discards when full" in run {
            val discardCount = new AtomicInteger(0)
            ConnectionPool.init[String](
                2,
                kyo.Duration.Infinity,
                _ => Sync.defer(true),
                _ => Sync.defer(discard(discardCount.incrementAndGet()))
            ).map { pool =>
                pool.release(key1, "a").andThen {
                    pool.release(key1, "b").andThen {
                        pool.release(key1, "c").andThen {
                            assert(discardCount.get() == 1)
                        }
                    }
                }
            }
        }
    }

    "tryReserve" - {
        "returns true when under limit" in run {
            mkPool().map { pool =>
                pool.tryReserve(key1).map { reserved =>
                    assert(reserved)
                }
            }
        }

        "returns false when at limit" in run {
            mkPool(2).map { pool =>
                pool.tryReserve(key1).map { r1 =>
                    assert(r1)
                    pool.tryReserve(key1).map { r2 =>
                        assert(r2)
                        pool.tryReserve(key1).map { r3 =>
                            assert(!r3)
                        }
                    }
                }
            }
        }
    }

    "close" - {
        "returns idle connections" in run {
            mkPool().map { pool =>
                pool.release(key1, "a").andThen {
                    pool.release(key1, "b").andThen {
                        pool.close().map { conns =>
                            assert(conns.size == 2)
                        }
                    }
                }
            }
        }

        "returns tracked connections" in run {
            mkPool().map { pool =>
                pool.track(key1, "tracked").andThen {
                    pool.close().map { conns =>
                        assert(conns.size == 1)
                    }
                }
            }
        }
    }

    "isAlive check during poll" in run {
        val discardCount = new AtomicInteger(0)
        ConnectionPool.init[String](
            2,
            kyo.Duration.Infinity,
            conn => Sync.defer(conn != "dead"),
            _ => Sync.defer(discard(discardCount.incrementAndGet()))
        ).map { pool =>
            pool.release(key1, "dead").andThen {
                pool.release(key1, "alive").andThen {
                    pool.poll(key1).map { result =>
                        assert(result == Present("alive"))
                        assert(discardCount.get() == 1)
                    }
                }
            }
        }
    }

end ConnectionPoolTest
