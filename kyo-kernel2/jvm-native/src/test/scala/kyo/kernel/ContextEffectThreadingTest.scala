package kyo.kernel

import kyo.Tag
import kyo.discard
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec

/** The binding tests that park through a cross-thread stop and resume on real threads; the
  * cross-platform binding coverage stays in the shared ContextEffectTest.
  */
class ContextEffectThreadingTest extends AnyFreeSpec:

    sealed trait Count extends ContextEffect[Int]

    def count: Int < Count = ContextEffect.suspend(Tag[Count])

    "a parked computation resumes with its binding" in {
        // the stop lodges before a trailing step, so the slice parks applying it, with the
        // binding still installed; a stop on the last step would let the slice complete instead
        val v: Int < Any = ContextEffect.handle(Tag[Count], 21)(
            count.map { c =>
                discard(Safepoint.stop(Thread.currentThread()))
                c
            }.map(_ * 2)
        )
        val parked = Eval.partial(v)
        assert(parked.evalNow.isEmpty)
        assert(Eval(parked) == 42)
    }

    "a park holding a binding resumes on another thread against that thread's enclosing scope" in {
        // the parked binding re-resolves at the resume site: bare resume sees no enclosure and
        // takes ifUndefined; a resume under an enclosing binding of the same tag sees its value
        // the park lands BEFORE the read: the stop lodges in a deferred payload the eval reads per
        // slice (an eager settled map would lodge it once, at construction), so the binding's value
        // is taken at the resume site, not captured before the park
        val body: Int < Count =
            Effect.defer {
                discard(Safepoint.stop(Thread.currentThread()))
                ()
            }.map(_ => count).map(_ + 1)
        def parked(label: String): Int < Any =
            val p = Eval.partial(ContextEffect.handle(Tag[Count], 10, (a: Int) => a + 10)(body))
            assert(p.evalNow.isEmpty, label)
            p
        end parked
        assert(Eval(parked("first")) == 11)
        @volatile var enclosed = 0
        val p1                 = parked("second")
        val t = new Thread(() =>
            enclosed = Eval(ContextEffect.handle(Tag[Count], 100)(p1: Int < Count))
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
            val p = Eval.partial(ContextEffect.handle(Tag[Count], 10, (a: Int) => a + 10)(body))
            assert(p.evalNow.isEmpty)
            @volatile var a = 0
            @volatile var b = 0
            val ta          = new Thread(() => a = Eval(ContextEffect.handle(Tag[Count], 100)(p: Int < Count)))
            val tb          = new Thread(() => b = Eval(ContextEffect.handle(Tag[Count], 1000)(p: Int < Count)))
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
