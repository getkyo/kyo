package kyo.ffi.internal

import java.util.concurrent.CountDownLatch
import kyo.discard
import kyo.ffi.Test

/** The transient callback frame belongs to the thread and is found by the thread's identity; the ThreadLocal is only a cache of that
  * lookup. On Scala Native a ThreadLocal entry can vanish between two reads on one thread (the table drops it on a rehash), and with a
  * per-shape ThreadLocal the pop after an FFI call read a fresh, empty deque. These leaves drop the cache between a push and its pop
  * and require the pushed callback to still be there.
  */
class CallbackRegistryTransientTest extends Test:

    // The frame map and the cache seam are process-global, so the leaves run alone.
    override def config = super.config.sequential

    "a pushed callback survives the cache being dropped before the trampoline fires and the pop" in {
        var fired = -1
        CallbackRegistry.pushTransient_I_U("kyo.ffi.internal.Spec", "survives", (x: Int) => fired = x)
        CallbackRegistry.transientCacheEvictForTest()
        CallbackRegistry.trampolineT_I_U(7)
        CallbackRegistry.popTransient_I_U()
        assert(fired == 7)
    }

    "re-entrant pushes of one shape stay in LIFO order across a dropped cache" in {
        val seen = new java.util.ArrayList[Int]()
        CallbackRegistry.pushTransient_I_U("kyo.ffi.internal.Spec", "outer", (x: Int) => discard(seen.add(x)))
        CallbackRegistry.pushTransient_I_U("kyo.ffi.internal.Spec", "inner", (x: Int) => discard(seen.add(x * 10)))
        CallbackRegistry.transientCacheEvictForTest()
        CallbackRegistry.trampolineT_I_U(1)
        CallbackRegistry.popTransient_I_U()
        CallbackRegistry.transientCacheEvictForTest()
        CallbackRegistry.trampolineT_I_U(2)
        CallbackRegistry.popTransient_I_U()
        assert(seen.toString == "[10, 2]")
    }

    "shapes do not share a deque" in {
        var a = -1
        var b = -1L
        CallbackRegistry.pushTransient_I_U("kyo.ffi.internal.Spec", "a", (x: Int) => a = x)
        CallbackRegistry.pushTransient_J_U("kyo.ffi.internal.Spec", "b", (x: Long) => b = x)
        CallbackRegistry.transientCacheEvictForTest()
        CallbackRegistry.trampolineT_I_U(3)
        CallbackRegistry.trampolineT_J_U(4L)
        CallbackRegistry.popTransient_I_U()
        CallbackRegistry.popTransient_J_U()
        assert(a == 3 && b == 4L)
    }

    "a frame is the thread's own, and a dead thread's frame is swept when another thread registers" in {
        val before  = CallbackRegistry.transientFrameCountForTest
        var onOther = -1
        val pushed  = new CountDownLatch(1)
        val release = new CountDownLatch(1)
        val other = new Thread(() =>
            CallbackRegistry.pushTransient_I_U("kyo.ffi.internal.Spec", "other", (x: Int) => onOther = x)
            pushed.countDown()
            release.await()
            CallbackRegistry.trampolineT_I_U(5)
            CallbackRegistry.popTransient_I_U()
        )
        other.start()
        pushed.await()
        // the other thread's frame is registered and is not this thread's
        assert(CallbackRegistry.transientFrameCountForTest == before + 1)
        release.countDown()
        other.join()
        assert(onOther == 5)
        // a registration from a fresh thread sweeps the dead one's frame
        val fresh = new Thread(() =>
            CallbackRegistry.pushTransient_I_U("kyo.ffi.internal.Spec", "fresh", (_: Int) => ())
            CallbackRegistry.popTransient_I_U()
        )
        fresh.start()
        fresh.join()
        assert(CallbackRegistry.transientFrameCountForTest == before + 1)
    }

end CallbackRegistryTransientTest
