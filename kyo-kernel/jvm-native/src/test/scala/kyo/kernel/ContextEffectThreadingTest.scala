package kyo.kernel

import kyo.Tag
import kyo.discard
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec

class ContextEffectThreadingTest extends AnyFreeSpec:

    sealed trait Count extends ContextEffect[Int]

    def count: Int < Count = ContextEffect.suspend(Tag[Count])

    "a parked computation resumes with its binding" in {
        val v: Int < Any = ContextEffect.handleInheritable(Tag[Count], 21)(
            count.map { c =>
                discard(Safepoint.stop(Thread.currentThread()))
                c
            }.map(_ * 2)
        )
        val parked = Eval.partial(v)
        assert(parked.evalNow.isEmpty)
        assert(parked.eval == 42)
    }

    "a park holding a binding resumes on another thread with the binding it captured" in {
        val body: Int < Count =
            Effect.defer {
                discard(Safepoint.stop(Thread.currentThread()))
                ()
            }.map(_ => count).map(_ + 1)
        def parked(label: String): Int < Any =
            val p = Eval.partial(ContextEffect.handleInheritable(Tag[Count], 10, (a: Int) => a + 10)(body))
            assert(p.evalNow.isEmpty, label)
            p
        end parked
        assert(parked("first").eval == 11)
        @volatile var enclosed = 0
        val p1                 = parked("second")
        val t = new Thread(() =>
            enclosed = ContextEffect.handleInheritable(Tag[Count], 100)(p1: Int < Count).eval
        )
        t.start()
        t.join(10000)
        assert(!t.isAlive)
        assert(enclosed == 11)
    }

    "concurrent resumes under different enclosures each keep the captured binding" in {
        var run = 0
        while run < 50 do
            val body: Int < Count =
                Effect.defer {
                    discard(Safepoint.stop(Thread.currentThread()))
                    ()
                }.map(_ => count).map(_ + 1)
            val p = Eval.partial(ContextEffect.handleInheritable(Tag[Count], 10, (a: Int) => a + 10)(body))
            assert(p.evalNow.isEmpty)
            @volatile var a = 0
            @volatile var b = 0
            val ta          = new Thread(() => a = ContextEffect.handleInheritable(Tag[Count], 100)(p: Int < Count).eval)
            val tb          = new Thread(() => b = ContextEffect.handleInheritable(Tag[Count], 1000)(p: Int < Count).eval)
            ta.start()
            tb.start()
            ta.join(10000)
            tb.join(10000)
            assert(!ta.isAlive && !tb.isAlive)
            assert(a == 11, s"a=$a")
            assert(b == 11, s"b=$b")
            run += 1
        end while
    }

end ContextEffectThreadingTest
