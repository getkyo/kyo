package kyo.ffi.internal

import java.util.concurrent.CountDownLatch
import kyo.ffi.Test

class ThreadOwnedTest extends Test:

    // The seams count threads process-wide, so the leaves run alone.
    override def config = super.config.sequential

    "the value is created once and read back after the cache is dropped" in {
        val owned = new ThreadOwned[Object](_ => new Object)
        val first = owned.get()
        owned.evictCacheForTest()
        assert(owned.get() eq first)
        owned.evictCacheForTest()
        assert(owned.get() eq first)
    }

    "set replaces the value, and the replacement survives the cache being dropped" in {
        val owned = new ThreadOwned[String](_ => "initial")
        assert(owned.get() == "initial")
        owned.set("replaced")
        owned.evictCacheForTest()
        assert(owned.get() == "replaced")
    }

    "init receives the owning thread" in {
        val owned = new ThreadOwned[Thread](t => t)
        assert(owned.get() eq Thread.currentThread())
    }

    "each thread has its own value, and a dead thread's value is swept when another thread registers" in {
        val owned               = new ThreadOwned[Object](_ => new Object)
        val mine                = owned.get()
        val before              = owned.sizeForTest
        var seen: Object | Null = null
        val ready               = new CountDownLatch(1)
        val release             = new CountDownLatch(1)
        val other = new Thread(() =>
            seen = owned.get()
            ready.countDown()
            release.await()
        )
        other.start()
        ready.await()
        assert(seen ne null)
        assert(seen ne mine)
        assert(owned.sizeForTest == before + 1)
        release.countDown()
        other.join()
        // the dead thread's value stays until a new thread registers, which sweeps it
        val fresh = new Thread(() =>
            val _ = owned.get()
        )
        fresh.start()
        fresh.join()
        assert(owned.sizeForTest == before + 1)
        assert(owned.get() eq mine)
    }

end ThreadOwnedTest
