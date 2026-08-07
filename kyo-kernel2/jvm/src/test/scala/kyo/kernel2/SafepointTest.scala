package kyo.kernel2

import kyo.test.Test
import language.implicitConversions

class SafepointTest extends Test[Any]:

    "depth slots are reclaimed from dead threads" in {
        var i = 0
        while i < 768 do
            val t = new Thread(() =>
                val _ = (1: Int < Any).map(_ + 1).eval
            )
            t.start()
            t.join()
            i += 1
        end while
        var fresh = false
        val probe = new Thread(() =>
            val _ = (1: Int < Any).map(_ + 1).eval
            fresh = Safepoint.owned
        )
        probe.start()
        probe.join()
        assert(fresh)
    }

    "preempt request lifecycle" in {
        var initiallyClear    = false
        var clearOnEmpty      = true
        var visibleAfterSet   = false
        var consumed          = false
        var clearAfterConsume = false
        var secondConsume     = true
        val t = new Thread(() =>
            val slot = Safepoint.slot()
            initiallyClear = !Safepoint.preempted(slot)
            clearOnEmpty = Safepoint.clearPreempt(slot)
            Safepoint.preempt(slot)
            visibleAfterSet = Safepoint.preempted(slot)
            consumed = Safepoint.clearPreempt(slot)
            clearAfterConsume = !Safepoint.preempted(slot)
            secondConsume = Safepoint.clearPreempt(slot)
        )
        t.start()
        t.join()
        assert(initiallyClear)
        assert(!clearOnEmpty)
        assert(visibleAfterSet)
        assert(consumed)
        assert(clearAfterConsume)
        assert(!secondConsume)
    }

    "pending request reads as depth at limit and suppresses the depth store" in {
        var observed = List.empty[Long]
        val t = new Thread(() =>
            val slot = Safepoint.slot()
            observed = List(
                Safepoint.increase(slot), {
                    Safepoint.preempt(slot)
                    Safepoint.increase(slot)
                },
                Safepoint.increase(slot), {
                    val _ = Safepoint.clearPreempt(slot)
                    Safepoint.increase(slot)
                }, {
                    Safepoint.decrease(slot)
                    Safepoint.decrease(slot)
                    Safepoint.increase(slot)
                }
            )
            Safepoint.decrease(slot)
        )
        t.start()
        t.join()
        val expected = List(
            0L,                   // clean claim, writes depth 1
            Safepoint.Limit + 1L, // depth 1 with the flag folded in, no store
            Safepoint.Limit + 1L, // still no store while pending
            1L,                   // consumed: depth untouched by the flagged calls, writes 2
            0L                    // both writes paired back down
        )
        assert(observed == expected)
    }

    "a request from another thread reaches a running increase loop" in {
        val slotRef  = new java.util.concurrent.atomic.AtomicInteger(-1)
        val observed = new java.util.concurrent.atomic.AtomicLong(-1L)
        val t = new Thread(() =>
            val slot = Safepoint.slot()
            slotRef.set(slot)
            var d          = Safepoint.increase(slot)
            var iterations = 0L
            while d < Safepoint.Limit && iterations < 1_000_000_000L do
                Safepoint.decrease(slot)
                d = Safepoint.increase(slot)
                iterations += 1
            end while
            observed.set(d)
            val _ = Safepoint.clearPreempt(slot)
        )
        t.start()
        while slotRef.get() == -1 do ()
        Safepoint.preempt(slotRef.get())
        t.join()
        assert(observed.get() >= Safepoint.Limit)
    }

    "a pending request bounces eager evaluation to the trampoline" in {
        var bounced   = false
        var evaluated = 0
        val t = new Thread(() =>
            val slot = Safepoint.slot()
            Safepoint.preempt(slot)
            val v = (1: Int < Any).map(_ + 1)
            bounced = (v: Any) match
                case _: Kyo[?, ?] => true
                case _            => false
            val _ = Safepoint.clearPreempt(slot)
            evaluated = v.eval
        )
        t.start()
        t.join()
        assert(bounced)
        assert(evaluated == 2)
    }

    "a request on the overflow slot is a no-op" in {
        Safepoint.preempt(Safepoint.OverflowSlot)
        assert(!Safepoint.preempted(Safepoint.OverflowSlot))
    }

    "claiming a slot wipes a stale request" in {
        var staleSlot = -1
        val a = new Thread(() =>
            val _ = (1: Int < Any).map(_ + 1).eval
            staleSlot = Safepoint.slot()
        )
        a.start()
        a.join()
        Safepoint.preempt(staleSlot)
        assert(Safepoint.preempted(staleSlot))
        var i = 0
        while Safepoint.preempted(staleSlot) && i < 4096 do
            val t = new Thread(() =>
                val _ = Safepoint.slot()
            )
            t.start()
            t.join()
            i += 1
        end while
        assert(!Safepoint.preempted(staleSlot))
    }
end SafepointTest
