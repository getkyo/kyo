package kyo.kernel.internal

import kyo.kernel.*

class SafepointTest extends kyo.test.Test[Any]:
    override def config = super.config.globallySequential(true)

    private val Period = 512

    "stop wraps the slot and stopped consumes it once" in {
        val slot = Safepoint.get()
        assert(!Safepoint.consumeStopped(slot))
        assert(Safepoint.stop(Thread.currentThread()))
        assert(Safepoint.consumeStopped(slot))
        assert(!Safepoint.consumeStopped(slot))
    }

    "a second stop while one is pending is idempotent" in {
        val slot = Safepoint.get()
        assert(Safepoint.stop(Thread.currentThread()))
        assert(Safepoint.stop(Thread.currentThread()))
        assert(Safepoint.consumeStopped(slot))
        assert(!Safepoint.consumeStopped(slot))
    }

    "get resolves the owning slot while a stop is pending" in {
        val slot = Safepoint.get()
        assert(Safepoint.stop(Thread.currentThread()))
        val slot2 = Safepoint.get()
        assert(Safepoint.consumeStopped(slot2))
    }

    "the budget flows through enter, exit, save, and restore" in {
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        assert(Safepoint.enter(slot))
        Safepoint.exit(slot)
        var entered = 0
        while Safepoint.enter(slot) do entered += 1
        assert(entered == Period)
        Safepoint.reset(slot)
        assert(Safepoint.enter(slot))
        Safepoint.exit(slot)
        Safepoint.restore(slot, saved)
    }

end SafepointTest
