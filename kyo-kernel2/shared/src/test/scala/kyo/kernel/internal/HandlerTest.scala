package kyo.kernel.internal

import kyo.Arrow
import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.discard
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

/** A handler is an arrow: the region's result flows into it, and what it produces is the region's answer. These are the handler's arrow
  * behaviors in isolation; how a region evaluates is covered by ArrowEffectTest and EvalTest.
  */
class HandlerTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)

    def contHandler(done: Int => Int)(using _frame: Frame): Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def frame                                                 = _frame
            def tag                                                   = Tag[Ask]
            def run[X](input: Unit, cont: Arrow[Int, Int, Ask & Any]) = cont(1)
            override def apply(a: Int)                                = done(a)

    def statefulHandler(init: Int): Handler.HandlerLoopState[Const[Unit], Const[Int], Ask, Int, Int, Any, Int] =
        new Handler.HandlerLoopState[Const[Unit], Const[Int], Ask, Int, Int, Any, Int]:
            def frame                           = Frame.internal
            def tag                             = Tag[Ask]
            def initialState                    = init
            def run[X](state: Int, input: Unit) = Loop.continue(state + 1, state: Int < Any)
            def apply(state: Int, a: Int)       = a + state

    "is its own head with an identity tail" in {
        val h = contHandler(identity)
        assert(h.head eq h)
        assert(h.tail eq Arrow.id[Int])
    }

    "applies done to a settled region result" in {
        assert(contHandler(_ + 1)(41).eval == 42)
    }

    "defers a pending region result and answers it once the eval reaches it" in {
        val h = contHandler(_ + 1)
        val r = h(ask, Arrow.id[Int])
        assert(r.evalNow.isEmpty)
        // applying the handler puts it on the eval as the innermost handler for its own tag, so the
        // region below is never consulted: run answers with 1, then done adds 1
        assert(answerAsk(41)(r).eval == 2)
    }

    "runs its continuation after done" in {
        val h    = contHandler(_ + 1)
        val next = Arrow[Int, Int, Any](_ * 10)
        assert(h(41, next).eval == 420)
    }

    "a stateful handler" - {
        "applies done with its initial state when applied as a plain arrow" in {
            assert(statefulHandler(10)(32).eval == 42)
        }

        "applies done with an explicit state" in {
            assert(statefulHandler(10).apply(30, 12).eval == 42)
        }

        "the successor carries a new initial state and delegates everything else" in {
            val h    = statefulHandler(10)
            val next = Handler.HandlerLoopState(h, 40)
            assert(next.initialState == 40)
            assert(next.tag <:< h.tag && h.tag <:< next.tag)
            assert(next.frame eq h.frame)
            assert(next(2).eval == 42)
            assert(next.apply(1, 2).eval == 3)
        }

        "the successor leaves the original's initial state alone" in {
            val h = statefulHandler(10)
            discard(Handler.HandlerLoopState(h, 40))
            assert(h.initialState == 10)
            assert(h(32).eval == 42)
        }
    }

    "answers a region through the eval" in {
        val body: Int < Ask = ask.map(a => ask.map(b => a + b))
        val r: Int < Any    = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(21), a => a)
        assert(r.eval == 42)
    }

    "the generic cont answers commits the continuation before running the clause" in {
        case class Boom() extends RuntimeException
        val h = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def frame                                                 = Frame.internal
            def tag                                                   = Tag[Ask]
            def run[X](input: Unit, cont: Arrow[Int, Int, Ask & Any]) = throw Boom()
            override def apply(a: Int)                                = a
        val out  = new Handler.Out
        val k    = Arrow.id[Any].asInstanceOf[Arrow[Any, Any, Any]]
        val slot = Safepoint.get()
        intercept[Boom](discard(h.answers((), k, armed = false, slot, out)))
        // the cell's cont lane is the exception lane: it must hold the unconsumed continuation
        // when the clause throws, committed before the clause ran
        assert(out.cont eq k)
    }

end HandlerTest
