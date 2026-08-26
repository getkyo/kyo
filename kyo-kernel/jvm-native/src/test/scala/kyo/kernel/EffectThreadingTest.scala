package kyo.kernel

import kyo.Result
import kyo.discard
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Safepoint

/** The bracket tests that need real threads: cross-thread stops and racing resumers. The
  * cross-platform bracket coverage stays in the shared EffectTest.
  */
class EffectThreadingTest extends kyo.test.Test[Any]:

    "a cross-thread stop parks inside a bracket and abandonment releases" in {
        @volatile var started       = false
        @volatile var released      = 0
        @volatile var sawUnreleased = false
        val t = new Thread(() =>
            def spin(i: Int): Int < Any =
                ((i + 1) & 63: Int < Any).map { v =>
                    started = true
                    spin(v)
                }
            val v: Int < Any = Effect.bracket(Effect.defer(1))(_ => released += 1)(r => spin(r))
            val p            = Eval.partial(v)
            sawUnreleased = released == 0 && p.evalNow.isEmpty
            Eval.finalizeResources(p)
        )
        t.start()
        while !started do ()
        assert(Safepoint.stop(t))
        t.join(20000)
        assert(!t.isAlive)
        assert(sawUnreleased)
        assert(released == 1)
    }

    "an abandonment racing a resume releases exactly once" in {
        var iterations = 0
        while iterations < 200 do
            val released = new java.util.concurrent.atomic.AtomicInteger
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))(_ => discard(released.incrementAndGet()))(r =>
                    Effect.defer {
                        discard(Safepoint.stop(Thread.currentThread()))
                        r
                    }.map(_ + 41)
                )
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            val resumer   = new Thread(() => discard(Eval(p)))
            val abandoner = new Thread(() => Eval.finalizeResources(p))
            resumer.start()
            abandoner.start()
            resumer.join(10000)
            abandoner.join(10000)
            assert(!resumer.isAlive && !abandoner.isAlive)
            assert(released.get == 1)
            iterations += 1
        end while
    }
end EffectThreadingTest
