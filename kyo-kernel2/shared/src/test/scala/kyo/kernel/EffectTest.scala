package kyo.kernel

import kyo.Const
import kyo.Tag
import kyo.kernel.internal.Eval
import org.scalatest.freespec.AnyFreeSpec

class EffectTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    "defer delays evaluation until the drive" in {
        var ran = false
        val d: Int < Any = Effect.defer {
            ran = true
            42
        }
        assert(!ran)
        assert(Eval(d) == 42)
        assert(ran)
    }

    "defer suspends effects performed by its body" in {
        var ran = false
        val d: Int < Ask = Effect.defer {
            ran = true
            ask.map(_ + 1)
        }
        assert(!ran)
        assert(Eval(answerAsk(41)(d)) == 42)
        assert(ran)
    }

    "defer composes with maps without running early" in {
        var ran = false
        val d: Int < Any = Effect.defer {
            ran = true
            1
        }
        val r = d.map(_ + 1)
        assert(!ran)
        assert(Eval(r) == 2)
        assert(ran)
    }

    "deferInline delays evaluation until the drive" in {
        var ran = false
        val d: Int < Any = Effect.deferInline {
            ran = true
            7
        }
        assert(!ran)
        assert(Eval(d.map(_ * 6)) == 42)
        assert(ran)
    }

    "defer evaluates once per drive of a fresh value" in {
        var runs = 0
        def d: Int < Any = Effect.defer {
            runs += 1
            runs
        }
        assert(Eval(d) == 1)
        assert(Eval(d) == 2)
    }

end EffectTest
