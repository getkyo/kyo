package kyo.kernel.internal

import kyo.kernel.*

class SafepointTest extends kyo.test.Test[Any]:
    // asserts on this thread's stop channel; time slicing writes into it
    override def config = super.config.globallySequential(true).timeSliced(false)

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

    "an addressed stop is honored only while its slice runs" in {
        val slot  = Safepoint.get()
        val slice = new AnyRef
        val prev  = Safepoint.beginSlice(slot, slice)
        assert(Safepoint.stop(Thread.currentThread(), slice))
        assert(Safepoint.stopped(slot))
        assert(Safepoint.consumeStopped(slot))
        Safepoint.endSlice(slot, prev)
        val prev2 = Safepoint.beginSlice(slot, new AnyRef)
        // delivered against a slice that no longer holds the slot: observed as no stop, and the
        // consume takes the stale sentinel out so the channel is clean for the next request
        assert(Safepoint.stop(Thread.currentThread(), slice))
        assert(!Safepoint.stopped(slot))
        assert(!Safepoint.consumeStopped(slot))
        assert(!Safepoint.consumeStopped(slot))
        Safepoint.endSlice(slot, prev2)
    }

    "a wildcard stop is honored inside a slice" in {
        val slot = Safepoint.get()
        val prev = Safepoint.beginSlice(slot, new AnyRef)
        assert(Safepoint.stop(Thread.currentThread()))
        assert(Safepoint.stopped(slot))
        assert(Safepoint.consumeStopped(slot))
        Safepoint.endSlice(slot, prev)
    }

    "the slice boundary drops its own late stop" in {
        val slot  = Safepoint.get()
        val slice = new AnyRef
        val prev  = Safepoint.beginSlice(slot, slice)
        assert(Safepoint.stop(Thread.currentThread(), slice))
        Safepoint.endSlice(slot, prev)
        assert(!Safepoint.consumeStopped(slot))
    }

    "a stop addressed to a departed slice does not short-circuit the next evaluation" in {
        val slot  = Safepoint.get()
        val slice = new AnyRef
        Safepoint.endSlice(slot, Safepoint.beginSlice(slot, slice))
        // the delivery raced the boundary: the slice is gone when the sentinel lands
        assert(Safepoint.stop(Thread.currentThread(), slice))
        var steps        = 0
        var v: Int < Any = Effect.defer(0)
        (1 to 100).foreach { _ =>
            v = v.map { x =>
                steps += 1
                x + 1
            }
        }
        assert(Eval.partial(v).evalNow == kyo.Maybe(100))
        assert(steps == 100)
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
