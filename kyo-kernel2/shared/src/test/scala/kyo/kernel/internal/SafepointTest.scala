package kyo.kernel.internal

import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class SafepointTest extends AnyFreeSpec:

    "stop wraps the slot and stopped consumes it once" in {
        val slot = Safepoint.get()
        assert(!Safepoint.stopped(slot))
        assert(Safepoint.stop(Thread.currentThread()))
        assert(Safepoint.stopped(slot))
        assert(!Safepoint.stopped(slot))
    }

    "a second stop while one is pending is idempotent" in {
        val slot = Safepoint.get()
        assert(Safepoint.stop(Thread.currentThread()))
        assert(Safepoint.stop(Thread.currentThread()))
        assert(Safepoint.stopped(slot))
        assert(!Safepoint.stopped(slot))
    }

    "get resolves the owning slot while a stop is pending" in {
        val slot = Safepoint.get()
        assert(Safepoint.stop(Thread.currentThread()))
        val slot2 = Safepoint.get()
        assert(Safepoint.stopped(slot2))
    }

    "stop misses a thread that never evaluated" in {
        assert(!Safepoint.stop(new Thread()))
    }

    "the budget flows through enter, exit, save, and restore" in {
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        assert(Safepoint.enter(slot))
        Safepoint.exit(slot)
        var entered = 0
        while Safepoint.enter(slot) do entered += 1
        assert(entered == Safepoint.Period)
        Safepoint.restore(slot, 0L)
        assert(Safepoint.enter(slot))
        Safepoint.exit(slot)
        Safepoint.restore(slot, saved)
    }

end SafepointTest
