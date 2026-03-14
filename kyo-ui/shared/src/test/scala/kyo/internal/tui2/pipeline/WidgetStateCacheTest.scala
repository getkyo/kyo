package kyo.internal.tui2.pipeline

import kyo.*
import kyo.Test

class WidgetStateCacheTest extends Test:

    import AllowUnsafe.embrace.danger

    "WidgetStateCache" - {
        "getOrCreate creates on first call, returns same on second" in {
            val cache = new WidgetStateCache
            val key   = WidgetKey(Frame.derive, Chunk.empty)
            val v1    = cache.getOrCreate(key, "hello")
            val v2    = cache.getOrCreate(key, "world")
            assert(v1 eq v2)
            assert(v1 == "hello")
        }

        "getOrCreate with different keys returns different instances" in {
            val cache = new WidgetStateCache
            val k1    = WidgetKey(Frame.derive, Chunk("a"))
            val k2    = WidgetKey(Frame.derive, Chunk("b"))
            val v1    = cache.getOrCreate(k1, "one")
            val v2    = cache.getOrCreate(k2, "two")
            assert(v1 == "one")
            assert(v2 == "two")
        }

        "get returns Absent for missing, Present for existing" in {
            val cache = new WidgetStateCache
            val key   = WidgetKey(Frame.derive, Chunk.empty)
            assert(cache.get[String](key) == Absent)
            cache.getOrCreate(key, "val")
            assert(cache.get[String](key) == Maybe("val"))
        }

        "sweep removes entries not accessed since beginFrame" in {
            val cache = new WidgetStateCache
            val k1    = WidgetKey(Frame.derive, Chunk("a"))
            val k2    = WidgetKey(Frame.derive, Chunk("b"))
            cache.getOrCreate(k1, "one")
            cache.getOrCreate(k2, "two")

            cache.beginFrame()
            cache.getOrCreate(k1, "one") // access k1 only
            cache.sweep()

            assert(cache.get[String](k1) == Maybe("one"))
            assert(cache.get[String](k2) == Absent)
        }

        "sweep preserves entries accessed since beginFrame" in {
            val cache = new WidgetStateCache
            val key   = WidgetKey(Frame.derive, Chunk.empty)
            cache.getOrCreate(key, "val")

            cache.beginFrame()
            cache.getOrCreate(key, "val")
            cache.sweep()

            assert(cache.get[String](key) == Maybe("val"))
        }

        "full frame cycle: accessed survive, others evicted" in {
            val cache = new WidgetStateCache
            val k1    = WidgetKey(Frame.derive, Chunk("keep"))
            val k2    = WidgetKey(Frame.derive, Chunk("evict"))

            // Frame 1: create both
            cache.beginFrame()
            cache.getOrCreate(k1, 1)
            cache.getOrCreate(k2, 2)
            cache.sweep()
            assert(cache.get[Int](k1) == Maybe(1))
            assert(cache.get[Int](k2) == Maybe(2))

            // Frame 2: only access k1
            cache.beginFrame()
            cache.getOrCreate(k1, 1)
            cache.sweep()
            assert(cache.get[Int](k1) == Maybe(1))
            assert(cache.get[Int](k2) == Absent)
        }

        "multiple frames: entry evicted after not accessed" in {
            val cache = new WidgetStateCache
            val key   = WidgetKey(Frame.derive, Chunk.empty)

            // Frame 1: create
            cache.beginFrame()
            cache.getOrCreate(key, 42)
            cache.sweep()
            assert(cache.get[Int](key) == Maybe(42))

            // Frame 2: not accessed
            cache.beginFrame()
            cache.sweep()
            assert(cache.get[Int](key) == Absent)
        }
    }

end WidgetStateCacheTest
