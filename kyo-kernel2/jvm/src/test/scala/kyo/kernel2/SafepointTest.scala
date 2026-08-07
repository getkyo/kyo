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
        var recovered = false
        val t = new Thread(() =>
            val safepoint = Safepoint.get
            while safepoint.enter() do budget += 1
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
        assert(recovered)
    }

    "preempt request lifecycle" in {
        var initiallyClear    = false
        var clearOnEmpty      = true
        var staleStillClear   = false
        var visibleAfterSet   = false
        var consumed          = false
        var clearAfterConsume = false
        var secondConsume     = true
        var restoredSame      = false
        val t = new Thread(() =>
            val safepoint = Safepoint.get
            initiallyClear = !safepoint.preempted
            clearOnEmpty = Safepoint.get.clearPreempt()
            safepoint.preempt()
            staleStillClear = !safepoint.preempted
            visibleAfterSet = Safepoint.get.preempted
            consumed = Safepoint.get.clearPreempt()
            clearAfterConsume = !Safepoint.get.preempted
            secondConsume = Safepoint.get.clearPreempt()
            restoredSame = Safepoint.get eq safepoint
        )
        t.start()
        t.join()
        assert(initiallyClear)
        assert(!clearOnEmpty)
        assert(staleStillClear)
        assert(visibleAfterSet)
        assert(consumed)
        assert(clearAfterConsume)
        assert(!secondConsume)
        assert(restoredSame)
    }

    "a pending request refuses fresh frames without touching in-flight depth" in {
        var first              = false
        var wrapperRefuses     = false
        var inFlightUnaffected = false
        var consumed           = false
        var budget             = 0
        var reusable           = false
        val t = new Thread(() =>
            val safepoint = Safepoint.get
            first = safepoint.enter()
            safepoint.preempt()
            wrapperRefuses = !Safepoint.get.enter()
            inFlightUnaffected = safepoint.enter()
            consumed = Safepoint.get.clearPreempt()
            budget = 2
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
        assert(wrapperRefuses)
        assert(inFlightUnaffected)
        assert(consumed)
        assert(budget == 512)
        assert(reusable)
    }

    "a request from another thread reaches a running frame loop" in {
        @volatile var workerSafepoint: Safepoint = Safepoint.Overflow
        @volatile var ready                      = false
        var parked                               = false
        val t = new Thread(() =>
            workerSafepoint = Safepoint.get
            ready = true
            var proceeding = true
            var iterations = 0L
            while proceeding && iterations < 1_000_000_000L do
                val sp = Safepoint.get
                if sp.enter() then sp.exit()
                else proceeding = false
                iterations += 1
            end while
            parked = !proceeding
            val _ = Safepoint.get.clearPreempt()
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
            Safepoint.get.preempt()
            val v = (1: Int < Any).map(_ + 1)
            bounced = (v: Any) match
                case _: Kyo[?, ?] => true
                case _            => false
            val _ = Safepoint.get.clearPreempt()
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

    "a reclaimed slot does not inherit a pending request" in {
        var staleSafepoint: Safepoint = Safepoint.Overflow
        val a = new Thread(() =>
            val _ = (1: Int < Any).map(_ + 1).eval
            staleSafepoint = Safepoint.get
        )
        a.start()
        a.join()
        staleSafepoint.preempt()
        var anyPreempted = false
        var allEager     = true
        var i            = 0
        while i < 1024 do
            val t = new Thread(() =>
                if Safepoint.get.preempted then anyPreempted = true
                val v = (1: Int < Any).map(_ + 1)
                (v: Any) match
                    case _: Kyo[?, ?] => allEager = false
                    case _            => ()
            )
            t.start()
            t.join()
            i += 1
        end while
        assert(!anyPreempted)
        assert(allEager)
    }
end SafepointTest
