package kyo.proto.kernel

import kyo.discard
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec

class EffectThreadingTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = v.eval

    private object Abandoned extends RuntimeException("abandoned", null, false, false)

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
            val v: Int < Any = Effect.bracket(Effect.defer(1))((_, _) => released += 1)(r => spin(r))
            val p            = Eval.partial(v)
            sawUnreleased = released == 0 && p.evalNow.isEmpty
            Eval.release(p, Abandoned)
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
                Effect.bracket(Effect.defer(1))((_, _) => discard(released.incrementAndGet()))(r =>
                    Effect.defer {
                        discard(Safepoint.stop(Thread.currentThread()))
                        r
                    }.map(_ + 41)
                )
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            val resumer   = new Thread(() => discard(eval(p)))
            val abandoner = new Thread(() => Eval.release(p, Abandoned))
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
