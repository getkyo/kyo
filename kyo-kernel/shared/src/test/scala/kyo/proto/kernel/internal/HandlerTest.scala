package kyo.proto.kernel.internal

import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Tag
import kyo.discard
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.Effect
import org.scalatest.freespec.AnyFreeSpec

class HandlerTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = Nested.unnest[A](Eval(v))

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    final class Boom extends RuntimeException("boom")

    def contHandler(complete: Int => Int): Handler.ContHandler[Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.ContHandler[Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                                                   = Tag[Ask]
            def run[X](input: Unit, cont: Arrow[Int, Int, Ask & Any]) = cont(1)
            def done(state: Unit, v: Int)                             = complete(v)

    def throwingContHandler: Handler.ContHandler[Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.ContHandler[Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                                                   = Tag[Ask]
            def run[X](input: Unit, cont: Arrow[Int, Int, Ask & Any]) = throw new Boom
            def done(state: Unit, v: Int)                             = v

    def loopHandler(answer: Int): Handler.LoopHandler[Unit, Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.LoopHandler[Unit, Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                              = Tag[Ask]
            def run[X](state: Unit, input: Unit) = Loop.continue((), answer: Int < Any)
            def done(state: Unit, v: Int)        = v

    def pendingLoopHandler(answer: Int): Handler.LoopHandler[Unit, Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.LoopHandler[Unit, Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                              = Tag[Ask]
            def run[X](state: Unit, input: Unit) = Loop.continue((), Effect.defer(answer): Int < Any)
            def done(state: Unit, v: Int)        = v

    def endingLoopHandler(result: Int): Handler.LoopHandler[Unit, Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.LoopHandler[Unit, Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                              = Tag[Ask]
            def run[X](state: Unit, input: Unit) = Loop.done(result: Int < Any)
            def done(state: Unit, v: Int)        = v

    def statefulHandler: Handler.LoopHandler[Int, Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.LoopHandler[Int, Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                             = Tag[Ask]
            def run[X](state: Int, input: Unit) = Loop.continue(state + 1, state: Int < Any)
            def done(state: Int, v: Int)        = v + state

    def throwingLoopHandler: Handler.LoopHandler[Int, Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.LoopHandler[Int, Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                             = Tag[Ask]
            def run[X](state: Int, input: Unit) = throw new Boom
            def done(state: Int, v: Int)        = v

    private def node: Pending[?, ?] = ask.asInstanceOf[Pending[?, ?]]

    private def carrier(ex: Throwable): Maybe[EffectTrace] =
        Maybe.fromOption(ex.getSuppressed.collectFirst { case c: EffectTrace => c })

    "done" - {
        "applies to a settled region result" in {
            assert(eval(contHandler(_ + 1).done((), 41)) == 42)
        }

        "sees the state it is given" in {
            assert(eval(statefulHandler.done(30, 12)) == 42)
        }
    }

    "recover declines by default" in {
        assert(contHandler(identity).recover((), new Boom) == Absent)
        assert(statefulHandler.recover(0, new Boom) == Absent)
    }

    "a region built on a handler" - {
        "answers through the eval and completes with done" in {
            val r = Kyo.handle[Unit, Ask, Int, Int, Any](ask.map(_ + 1), contHandler(_ * 2), ())
            assert(eval(r) == 4)
        }

        "threads the loop handler's state through every answer" in {
            val body: Int < Ask = ask.map(a => ask.map(b => a + b))
            val r               = Kyo.handle[Int, Ask, Int, Int, Any](body, statefulHandler, 10)
            assert(eval(r) == 33)
        }

        "ends with the value a loop clause answers done with" in {
            val r = Kyo.handle[Unit, Ask, Int, Int, Any](ask.map(_ + 1), endingLoopHandler(-1), ())
            assert(eval(r) == -1)
        }

        "a settled body skips the handler except for done" in {
            val r = Kyo.handle[Unit, Ask, Int, Int, Any](41, contHandler(_ + 1), ())
            assert(eval(r) == 42)
        }
    }

    "answers" - {
        val id = Arrow.id[Any].asInstanceOf[Arrow[Any, Any, Any]]

        def continued(outcome: Loop.Outcome2[Int, Any, Int < Any] < Any): Loop.Continue2[Int, Any] =
            outcome match
                case c: Loop.Continue2[Int, Any] @unchecked => c
                case other                                  => fail(s"expected a continue, got $other")

        "threads a settled answer through the continuation" in {
            val k   = Arrow[Any]((x: Any) => x.asInstanceOf[Int] + 1)
            val out = statefulHandler.answers(5, (), k, armed = false, Safepoint.get(), Frame.internal)
            val c   = continued(out)
            assert(c._1 == 6)
            assert(c._2.asInstanceOf[Int] == 6)
        }

        "maps the continuation over a pending answer" in {
            val k   = Arrow[Any]((x: Any) => x.asInstanceOf[Int] + 1)
            val out = pendingLoopHandler(7).answers((), (), k, armed = false, Safepoint.get(), Frame.internal)
            out match
                case c: Loop.Continue2[Unit, Any] @unchecked =>
                    assert(c._2.isInstanceOf[Pending[?, ?]])
                    assert(eval(c._2.asInstanceOf[Int < Any]) == 8)
                case other => fail(s"expected a continue, got $other")
            end match
        }

        "a throwing clause keeps the state it reached and rethrows at the deferred step" in {
            val out = throwingLoopHandler.answers(5, (), id, armed = false, Safepoint.get(), Frame.internal)
            val c   = continued(out)
            assert(c._1 == 5)
            assert(c._2.isInstanceOf[Pending[?, ?]])
            discard(intercept[Boom](eval(c._2.asInstanceOf[Int < Any])))
        }

        "a failing continuation application reports the state after the answer" in {
            val k   = Arrow[Any]((_: Any) => (throw new Boom): Int)
            val out = statefulHandler.answers(5, (), k, armed = false, Safepoint.get(), Frame.internal)
            val c   = continued(out)
            assert(c._1 == 6)
            discard(intercept[Boom](eval(c._2.asInstanceOf[Int < Any])))
        }
    }

    "answering and running attach the trace of a clause failure" - {
        "a cont handler's failure carries its continuation" in {
            val stack = Stack.borrow()
            try
                val cont = Arrow[Int](_ + 1)
                val ex   = intercept[Boom](throwingContHandler.answering((), cont, node, stack))
                val c    = carrier(ex)
                assert(c.nonEmpty)
                assert(c.get.elements.exists(_.getFileName == "HandlerTest.scala"))
            finally Stack.release(stack)
            end try
        }

        "a loop handler's failure carries the suspension" in {
            val stack = Stack.borrow()
            try
                val ex = intercept[Boom](throwingLoopHandler.running(0, (), node, stack))
                val c  = carrier(ex)
                assert(c.nonEmpty)
                assert(c.get.elements.exists(_.getFileName == "HandlerTest.scala"))
            finally Stack.release(stack)
            end try
        }
    }

    "clauseDispatch" - {
        "delivers a done outcome as the region's value" in {
            val step = loopHandler(1).clauseDispatch[Any](Arrow.id[Int])
            assert(eval(step(Loop.done[Unit, Int < Ask, Int < Any](7), Arrow.id[Int])) == 7)
        }

        "re-enters the region on a continue outcome" in {
            val reentry = Arrow[Int]((x: Int) => ask.map(_ + x))
            val step    = loopHandler(5).clauseDispatch[Any](reentry)
            assert(eval(step(Loop.continue[Unit, Int < Ask, Int < Any]((), 1: Int < Any), Arrow.id[Int])) == 6)
        }

        "defers a pending outcome and dispatches it once settled" in {
            val step    = loopHandler(1).clauseDispatch[Any](Arrow.id[Int])
            val pending = Effect.defer(Loop.done[Unit, Int < Ask, Int < Any](7))
            assert(eval(step(pending, Arrow.id[Int])) == 7)
        }
    }

end HandlerTest
