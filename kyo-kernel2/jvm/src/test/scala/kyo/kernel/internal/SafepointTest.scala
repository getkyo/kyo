package kyo.kernel.internal

import kyo.Maybe
import kyo.kernel.*
import kyo.test.Test
import language.implicitConversions

class SafepointTest extends Test[Any]:

    // every thread gets a working safepoint (a claimed slot or the detached cell
    // fallback), so bodies run on plain fresh threads with no retry machinery
    def onFreshThread(body: => Unit): Unit =
        val t = new Thread(() => body)
        t.start()
        t.join()
    end onFreshThread

    "every thread gets a working safepoint" in {
        var owned     = false
        var evaluated = 0
        onFreshThread {
            evaluated = (1: Int < Any).map(_ + 1).eval
            owned = Safepoint.owned
        }
        assert(owned)
        assert(evaluated == 2)
    }

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
        onFreshThread {
            val _ = (1: Int < Any).map(_ + 1).eval
            fresh = Safepoint.owned
        }
        assert(fresh)
    }

    "enter refuses at the depth budget and recovers on exit" in {
        var budget    = 0
        var recovered = false
        onFreshThread {
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
        onFreshThread {
            val safepoint = Safepoint.get
            initiallyClear = !Safepoint.pollPreempt()
            clearOnEmpty = Safepoint.clearPreempt()
            safepoint.preempt()
            visibleAfterSet = Safepoint.pollPreempt()
            consumed = Safepoint.clearPreempt()
            clearAfterConsume = !Safepoint.pollPreempt()
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
        onFreshThread {
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
        @volatile var workerSafepoint: Maybe[Safepoint] = Maybe.Absent
        @volatile var ready                             = false
        @volatile var parked                            = false
        val t = new Thread(() =>
            workerSafepoint = Maybe(Safepoint.get)
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
            val _ = Safepoint.clearPreempt()
        )
        t.start()
        while !ready do ()
        workerSafepoint match
            case Maybe.Present(sp) => sp.preempt()
            case Maybe.Absent      => ()
        t.join()
        assert(parked)
    }

    "a pending request bounces eager evaluation to the trampoline" in {
        var bounced   = false
        var evaluated = 0
        onFreshThread {
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

    "masked regions absorb and re-issue requests" in {
        var absorbed = false
        var reissued = false
        var consumed = false
        onFreshThread {
            Safepoint.get.preempt()
            Safepoint.maskPreempt()
            absorbed = !Safepoint.pollPreempt()
            Safepoint.unmaskPreempt()
            reissued = Safepoint.pollPreempt()
            consumed = Safepoint.clearPreempt()
        }
        assert(absorbed)
        assert(reissued)
        assert(consumed)
    }

    "an expired slice deadline becomes a request" in {
        var fired    = false
        var consumed = false
        var disarmed = false
        onFreshThread {
            val sp = Safepoint.beginSlice(java.lang.System.currentTimeMillis() - 1)
            fired = Safepoint.pollPreempt()
            consumed = Safepoint.clearPreempt()
            sp.endSlice()
            disarmed = !Safepoint.pollPreempt()
        }
        assert(fired)
        assert(consumed)
        assert(disarmed)
    }

    "an unexpired slice deadline stays quiet" in {
        var quiet = false
        onFreshThread {
            val sp = Safepoint.beginSlice(java.lang.System.currentTimeMillis() + 60000)
            quiet = !Safepoint.pollPreempt()
            sp.endSlice()
        }
        assert(quiet)
    }

    "a reclaimed slot does not inherit a pending request" in {
        @volatile var staleSafepoint: Maybe[Safepoint] = Maybe.Absent
        val a = new Thread(() =>
            val _ = (1: Int < Any).map(_ + 1).eval
            staleSafepoint = Maybe(Safepoint.get)
        )
        a.start()
        a.join()
        staleSafepoint match
            case Maybe.Present(sp) => sp.preempt()
            case Maybe.Absent      => ()
        var anyPreempted = false
        var allEager     = true
        var i            = 0
        while i < 1024 do
            val t = new Thread(() =>
                if Safepoint.pollPreempt() then anyPreempted = true
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
