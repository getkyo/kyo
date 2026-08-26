package kyo.kernel.internal

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.kernel.*

/** The representation contract: a value crosses the pending type unwrapped, and only a value that would itself read as a computation is
  * boxed. Getting this wrong in either direction is what makes the evaluator mistake a payload for a suspension, or hand a caller a box
  * where it expected a value.
  */
class NestedTest extends kyo.Test:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)

    // the pending type erases to its union, so a test can look at what a lift actually produced
    def boxed(v: Any): Boolean = v.isInstanceOf[Nested[?]]

    "lift" - {
        "passes a settled value through unwrapped" in {
            val v: Int < Any = Nested.nest(42)
            assert(!boxed(v))
            assert(v.evalNow == Maybe(42))
        }

        "passes a reference value through unwrapped" in {
            val v: String < Any = Nested.nest("x")
            assert(!boxed(v))
            assert(v.eval == "x")
        }

        "boxes a computation, so it travels as data" in {
            val inner: Int < Ask     = ask.map(_ + 1)
            val v: (Int < Ask) < Any = Nested.nest(inner)
            assert(boxed(v))
            val payload: Int < Ask = v.eval
            assert(answerAsk(41)(payload).eval == 42)
        }

        "boxes an already-boxed value again, one level per lift" in {
            val inner: Int < Ask                 = ask.map(_ + 1)
            val once: (Int < Ask) < Any          = Nested.nest(inner)
            val twice: ((Int < Ask) < Any) < Any = Nested.nest(once)
            assert(boxed(twice))
            assert(boxed(twice.eval))
            assert(answerAsk(41)(twice.eval.eval).eval == 42)
        }

        // map over a settled value runs strictly, so this computation is its result by the time the
        // lift sees it, and there is nothing an evaluator could mistake for a suspension
        "does not box a computation that has already settled" in {
            val inner: Int < Any     = (1: Int < Any).map(_ + 1)
            val v: (Int < Any) < Any = Nested.nest(inner)
            assert(!boxed(v))
            assert(v.eval.eval == 2)
        }
    }

    "the box carries its payload unchanged" in {
        val inner: Int < Ask = ask
        val box              = Nested(inner)
        assert(!boxed(box.value))
        assert(answerAsk(7)(box.value).eval == 7)
    }

    "unwrapping strips exactly one level" in {
        val inner: Int < Ask     = ask.map(_ + 1)
        val v: (Int < Ask) < Any = Nested.nest(inner)
        val stripped: Int < Ask  = Nested.unnest(v)
        assert(!boxed(stripped))
        assert(answerAsk(41)(stripped).eval == 42)
    }

end NestedTest
