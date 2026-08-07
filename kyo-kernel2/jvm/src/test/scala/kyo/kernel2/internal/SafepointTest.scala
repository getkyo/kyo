package kyo.kernel2.internal

import kyo.kernel2.*
import kyo.test.Test
import language.implicitConversions

class SafepointTest extends Test[Any]:

    // under suite parallelism a fresh thread can transiently fail to claim a slot and land on
    // Overflow, where every frame refuses by design; assertions about claimed-slot behavior
    // retry on fresh threads until one owns a slot
    def onClaimedThread(body: => Unit)(using kyo.test.AssertScope): Unit =
        var ran      = false
        var attempts = 0
        while !ran && attempts < 64 do
            val t = new Thread(() =>
                val _ = Safepoint.get
                if Safepoint.owned then
                    body
                    ran = true
            )
            t.start()
            t.join()
            attempts += 1
        end while
        assert(ran)
    end onClaimedThread

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
        onClaimedThread {
            val _ = (1: Int < Any).map(_ + 1).eval
            fresh = Safepoint.owned
        }
        assert(fresh)
    }

    "enter refuses at the depth budget and recovers on exit" in {
        var budget    = 0
        var recovered = false
        onClaimedThread {
            budget = 0
            val safepoint = Safepoint.get
            while safepoint.enter() do budget += 1
            var i = budget
            while i > 0 do
                safepoint.exit()
                i -= 1
            recovered = safepoint.enter()
            safepoint.exit()
        }
        assert(budget == 512)
        assert(recovered)
    }

    "preempt request lifecycle" in {
        var initiallyClear    = false
        var clearOnEmpty      = true
        var visibleAfterSet   = false
        var consumed          = false
        var clearAfterConsume = false
        var secondConsume     = true
        var restoredSame      = false
        onClaimedThread {
            val safepoint = Safepoint.get
            initiallyClear = !Safepoint.preempted
            clearOnEmpty = Safepoint.clearPreempt()
            safepoint.preempt()
            visibleAfterSet = Safepoint.preempted
            consumed = Safepoint.clearPreempt()
            clearAfterConsume = !Safepoint.preempted
            secondConsume = Safepoint.clearPreempt()
            restoredSame = Safepoint.get eq safepoint
        }
        assert(initiallyClear)
        assert(!clearOnEmpty)
        assert(visibleAfterSet)
        assert(consumed)
        assert(clearAfterConsume)
        assert(!secondConsume)
        assert(restoredSame)
    }

    "a pending request refuses fresh frames without touching in-flight depth" in {
        var first              = false
        var parkedRefuses      = false
        var inFlightUnaffected = false
        var consumed           = false
        var budget             = 0
        var reusable           = false
        onClaimedThread {
            val safepoint = Safepoint.get
            first = safepoint.enter()
            safepoint.preempt()
            parkedRefuses = !Safepoint.get.enter()
            inFlightUnaffected = safepoint.enter()
            consumed = Safepoint.clearPreempt()
            budget = 2
            while safepoint.enter() do budget += 1
            var i = budget
            while i > 0 do
                safepoint.exit()
                i -= 1
            reusable = safepoint.enter()
            safepoint.exit()
        }
        assert(first)
        assert(parkedRefuses)
        assert(inFlightUnaffected)
        assert(consumed)
        assert(budget == 512)
        assert(reusable)
    }

    "a request from another thread reaches a running frame loop" in {
        var parked   = false
        var attempts = 0
        while !parked && attempts < 16 do
            @volatile var workerSafepoint: Safepoint = Safepoint.Overflow
            @volatile var ready                      = false
            @volatile var claimed                    = false
            val t = new Thread(() =>
                workerSafepoint = Safepoint.get
                claimed = Safepoint.owned
                ready = true
                if claimed then
                    var proceeding = true
                    var iterations = 0L
                    while proceeding && iterations < 1_000_000_000L do
                        val sp = Safepoint.get
                        if sp.enter() then sp.exit()
                        else proceeding = false
                        iterations += 1
                    end while
                    parked = !proceeding
                    val _ = Safepoint.clearPreempt()
                end if
            )
            t.start()
            while !ready do ()
            if claimed then workerSafepoint.preempt()
            t.join()
            attempts += 1
        end while
        assert(parked)
    }

    "a pending request bounces eager evaluation to the trampoline" in {
        var bounced   = false
        var evaluated = 0
        onClaimedThread {
            Safepoint.get.preempt()
            val v = (1: Int < Any).map(_ + 1)
            bounced = (v: Any) match
                case _: Kyo[?, ?] => true
                case _            => false
            val _ = Safepoint.clearPreempt()
            evaluated = v.eval
        }
        assert(bounced)
        assert(evaluated == 2)
    }

    "a request on the overflow safepoint is a no-op" in {
        Safepoint.Overflow.preempt()
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
        var claimed      = 0
        var i            = 0
        while i < 1024 do
            val t = new Thread(() =>
                if Safepoint.preempted then anyPreempted = true
                // a thread that could not claim a slot runs on Overflow and bounces by design;
                // the eager assertion applies only to claimants, who must never inherit a park
                if Safepoint.owned then
                    claimed += 1
                    val v = (1: Int < Any).map(_ + 1)
                    (v: Any) match
                        case _: Kyo[?, ?] => allEager = false
                        case _            => ()
                end if
            )
            t.start()
            t.join()
            i += 1
        end while
        assert(!anyPreempted)
        assert(allEager)
        assert(claimed > 0)
    }
end SafepointTest
