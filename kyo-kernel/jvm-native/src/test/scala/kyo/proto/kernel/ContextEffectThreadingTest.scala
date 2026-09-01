package kyo.proto.kernel

import kyo.Tag
import kyo.discard
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec

class ContextEffectThreadingTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = v.eval

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
        assert(eval(parked) == 42)
    }

    "a park holding a binding resumes on another thread against that thread's enclosing scope" in {
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
        assert(eval(parked("first")) == 11)
        @volatile var enclosed = 0
        val p1                 = parked("second")
        val t = new Thread(() =>
            enclosed = eval(ContextEffect.handleInheritable(Tag[Count], 100)(p1: Int < Count))
        )
        t.start()
        t.join(10000)
        assert(!t.isAlive)
        assert(enclosed == 111)
    }

    "concurrent resumes under different enclosures do not observe each other's resolution" in {
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
            val ta          = new Thread(() => a = eval(ContextEffect.handleInheritable(Tag[Count], 100)(p: Int < Count)))
            val tb          = new Thread(() => b = eval(ContextEffect.handleInheritable(Tag[Count], 1000)(p: Int < Count)))
            ta.start()
            tb.start()
            ta.join(10000)
            tb.join(10000)
            assert(!ta.isAlive && !tb.isAlive)
            assert(a == 111, s"a=$a")
            assert(b == 1011, s"b=$b")
            run += 1
        end while
    }

end ContextEffectThreadingTest
