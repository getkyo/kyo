package kyo.kernel.internal

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import kyo.kernel.Bracket
import kyo.kernel.Effect
import org.scalatest.freespec.AnyFreeSpec

class SafepointTest extends AnyFreeSpec:

    private val Period = Safepoint.period()

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    private def requestStop(): Unit =
        discard(Safepoint.get())
        discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
    end requestStop

    private def silenced[A](f: => A): A =
        val thread   = Thread.currentThread()
        val previous = thread.getUncaughtExceptionHandler()
        thread.setUncaughtExceptionHandler((_, _) => ())
        try f
        finally thread.setUncaughtExceptionHandler(previous)
    end silenced

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
        assert(Safepoint.stop(Thread.currentThread(), slice))
        var steps        = 0
        var v: Int < Any = Effect.defer(0)
        (1 to 100).foreach { _ =>
            v = v.map { x =>
                steps += 1
                x + 1
            }
        }
        assert(Eval.partial(v).evalNow == Maybe(100))
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

    "a throwing release in a nested eval does not disarm the enclosing slice" in silenced {
        val inner: Int < Ask =
            Bracket(Effect.defer(1))(_ => ask.map(_ + 1))((_, _) => throw new IllegalStateException("release"))
        val dropped: Int < Any =
            ArrowEffect.handleCont(Tag[Ask], inner)([C] => (_, _) => -1, a => a)
        var built            = 0
        val outer: Int < Any =
            Effect.defer {
                discard(dropped.eval)
                0
            }.map { z =>
                var acc: Int < Any = z
                var i              = 0
                while i < 100 do
                    acc = acc.map { x =>
                        built += 1
                        if built == 50 then requestStop()
                        x + 1
                    }
                    i += 1
                end while
                acc
            }
        val p = Eval.partial(outer)
        assert(p.evalNow.isEmpty)
        assert(built >= 50 && built <= 52, s"built=$built")
        assert(p.eval == 100)
        assert(built == 100)
    }

    "a throwing release on the completing path leaves the caller's safepoint state intact" in silenced {
        val v: Int < Ask =
            Bracket(Effect.defer(1))(_ => ask.map(_ + 1))((_, _) => throw new IllegalStateException("release"))
        val dropped: Int < Any =
            ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
        val slot = Safepoint.get()
        discard(Safepoint.enter(slot))
        discard(Safepoint.enter(slot))
        try
            val before = Safepoint.save(slot)
            Safepoint.restore(slot, before)
            assert(dropped.eval == -1)
            val after = Safepoint.save(slot)
            Safepoint.restore(slot, after)
            assert(after.equals(before))
        finally
            Safepoint.exit(slot)
            Safepoint.exit(slot)
        end try
    }

    // Nobody but the owner knows which slice it runs, so a late delivery from another thread against a stop
    // the running slice is owed merges into a wildcard: the running slice's own stop is still honored.
    "a late stop from another thread merges into the running slice's own" in {
        val slot     = Safepoint.get()
        val owner    = Thread.currentThread()
        val departed = new AnyRef
        val slice    = new AnyRef
        val prev     = Safepoint.beginSlice(slot, slice)
        assert(Safepoint.stop(owner, slice))
        assert(Safepoint.stopped(slot))
        val late = new Thread(() => discard(Safepoint.stop(owner, departed)))
        late.start()
        late.join()
        assert(Safepoint.stopped(slot))
        assert(Safepoint.consumeStopped(slot))
        assert(!Safepoint.consumeStopped(slot))
        Safepoint.endSlice(slot, prev)
    }

    // A stop aimed at a slice that has ended can land after the next slice began, and it then sits in the slot
    // unhonored. A stop the running slice requests for itself must still land: a fiber boundary asks for one
    // as it parks on a join, and one that never lands re-raises the join every round, nesting a continuation
    // per round until the promise completes and the delivery overflows the stack.
    "a stop for the running slice supersedes a stale one left by a departed slice" in {
        val slot     = Safepoint.get()
        val departed = new AnyRef
        val slice    = new AnyRef
        val prev     = Safepoint.beginSlice(slot, slice)
        assert(Safepoint.stop(Thread.currentThread(), departed))
        assert(!Safepoint.stopped(slot))
        assert(Safepoint.stop(Thread.currentThread(), slice))
        assert(Safepoint.stopped(slot))
        assert(Safepoint.consumeStopped(slot))
        assert(!Safepoint.consumeStopped(slot))
        Safepoint.endSlice(slot, prev)
    }

    // A late delivery to a departed slice is not honored by the running one, and it must not answer for a fresh
    // request from another thread either: an interrupt's stop, or the coordinator's preemption, for the running
    // slice has to land, or the slice runs on until it parks or ends on its own.
    "a stop from another thread lands while a stale one is pending" in {
        val slot     = Safepoint.get()
        val owner    = Thread.currentThread()
        val departed = new AnyRef
        val slice    = new AnyRef
        val prev     = Safepoint.beginSlice(slot, slice)
        val late     = new Thread(() => discard(Safepoint.stop(owner, departed)))
        late.start()
        late.join()
        assert(!Safepoint.stopped(slot))
        val fresh = new Thread(() => discard(Safepoint.stop(owner, slice)))
        fresh.start()
        fresh.join()
        assert(Safepoint.stopped(slot), "the fresh stop was answered by the stale one and lost")
        assert(Safepoint.consumeStopped(slot))
        assert(!Safepoint.consumeStopped(slot))
        Safepoint.endSlice(slot, prev)
    }

    "a wildcard stop supersedes a stale one left by a departed slice" in {
        val slot     = Safepoint.get()
        val departed = new AnyRef
        val prev     = Safepoint.beginSlice(slot, new AnyRef)
        assert(Safepoint.stop(Thread.currentThread(), departed))
        assert(!Safepoint.stopped(slot))
        assert(Safepoint.stop(Thread.currentThread()))
        assert(Safepoint.stopped(slot))
        assert(Safepoint.consumeStopped(slot))
        Safepoint.endSlice(slot, prev)
    }

end SafepointTest
