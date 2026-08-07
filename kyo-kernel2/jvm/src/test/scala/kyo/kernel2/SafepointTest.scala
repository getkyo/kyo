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

    "enter refuses at the depth budget and recovers on exit" in {
        var budget    = 0
        var refused   = false
        var recovered = false
        val t = new Thread(() =>
            val safepoint = Safepoint.get
            while safepoint.enter() do budget += 1
            refused = true
            var i = budget
            while i > 0 do
                safepoint.exit()
                i -= 1
            recovered = safepoint.enter()
            safepoint.exit()
        )
        t.start()
        t.join()
        assert(budget == 512)
        assert(refused)
        assert(recovered)
    }

    "preempt request lifecycle" in {
        var initiallyClear    = false
        var clearOnEmpty      = true
        var visibleAfterSet   = false
        var consumed          = false
        var clearAfterConsume = false
        var secondConsume     = true
        val t = new Thread(() =>
            val safepoint = Safepoint.get
            initiallyClear = !safepoint.preempted
            clearOnEmpty = safepoint.clearPreempt()
            safepoint.preempt()
            visibleAfterSet = safepoint.preempted
            consumed = safepoint.clearPreempt()
            clearAfterConsume = !safepoint.preempted
            secondConsume = safepoint.clearPreempt()
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

    "a pending request refuses enter without consuming depth budget" in {
        var first         = false
        var whilePending1 = true
        var whilePending2 = true
        var consumed      = false
        var budget        = 0
        var reusable      = false
        val t = new Thread(() =>
            val safepoint = Safepoint.get
            first = safepoint.enter()
            safepoint.preempt()
            whilePending1 = safepoint.enter()
            whilePending2 = safepoint.enter()
            consumed = safepoint.clearPreempt()
            budget = 1
            while safepoint.enter() do budget += 1
            var i = budget
            while i > 0 do
                safepoint.exit()
                i -= 1
            reusable = safepoint.enter()
            safepoint.exit()
        )
        t.start()
        t.join()
        assert(first)
        assert(!whilePending1)
        assert(!whilePending2)
        assert(consumed)
        assert(budget == 512)
        assert(reusable)
    }

    "a request from another thread reaches a running enter loop" in {
        @volatile var workerSafepoint: Safepoint = Safepoint.Overflow
        @volatile var ready                      = false
        var parked                               = false
        val t = new Thread(() =>
            val safepoint = Safepoint.get
            workerSafepoint = safepoint
            ready = true
            var proceeding = true
            var iterations = 0L
            while proceeding && iterations < 1_000_000_000L do
                if safepoint.enter() then safepoint.exit()
                else proceeding = false
                iterations += 1
            end while
            parked = !proceeding
            val _ = safepoint.clearPreempt()
        )
        t.start()
        while !ready do ()
        workerSafepoint.preempt()
        t.join()
        assert(parked)
    }

    "a pending request bounces eager evaluation to the trampoline" in {
        var bounced   = false
        var evaluated = 0
        val t = new Thread(() =>
            val safepoint = Safepoint.get
            safepoint.preempt()
            val v = (1: Int < Any).map(_ + 1)
            bounced = (v: Any) match
                case _: Kyo[?, ?] => true
                case _            => false
            val _ = safepoint.clearPreempt()
            evaluated = v.eval
        )
        t.start()
        t.join()
        assert(bounced)
        assert(evaluated == 2)
    }

    "a request on the overflow safepoint is a no-op" in {
        Safepoint.Overflow.preempt()
        assert(!Safepoint.Overflow.preempted)
        assert(!Safepoint.Overflow.enter())
    }

    "claiming a slot wipes a stale request" in {
        var staleSafepoint: Safepoint = Safepoint.Overflow
        val a = new Thread(() =>
            val _ = (1: Int < Any).map(_ + 1).eval
            staleSafepoint = Safepoint.get
        )
        a.start()
        a.join()
        staleSafepoint.preempt()
        assert(staleSafepoint.preempted)
        var i = 0
        while staleSafepoint.preempted && i < 4096 do
            val t = new Thread(() =>
                val _ = Safepoint.get
            )
            t.start()
            t.join()
            i += 1
        end while
        assert(!staleSafepoint.preempted)
    }
end SafepointTest
