package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.targetName

class KyoInternalTest extends AnyFreeSpec:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    type AskHandled = Kyo.Handled[Const[Unit], Const[Int], Ask, Int, Int, Any, Any]

    def loopAsk(value: Int): Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any] =
        new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any]:
            def tag = Tag[Ask]
            @targetName("applyInput")
            def apply[X](input: Unit) = Loop.continue(value)

    def node(h: Handler.Cont[Const[Unit], Const[Int], Ask, Int, Any] | Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any])
        : AskHandled =
        Kyo.Handled(ask, h, Arrow[Int])

    def sameRef(a: Any, b: Any): Boolean =
        (a, b) match
            case (a: AnyRef, b: AnyRef) => a eq b
            case _                      => false

    "Handled" - {

        "saves the region parts" in {
            val h = loopAsk(1)
            val n = node(h)
            assert(n.handler eq h)
            assert(n.exit eq Arrow[Int])
        }

        "map lands outside the region" in {
            val h      = loopAsk(1)
            val n      = node(h)
            val mapped = (n: Int < Any).map(_ + 1)
            (mapped: Any) match
                case m: AskHandled @unchecked =>
                    assert(m.handler eq h)
                    assert(sameRef(m.value, n.value))
                    assert(m.exit(41).eval == 42)
                case other =>
                    fail(s"expected a Handled, got $other")
            end match
        }

        "chained maps accumulate in order outside the region" in {
            val h      = loopAsk(1)
            val n      = node(h)
            val mapped = (n: Int < Any).map(_ + 1).map(_ * 10)
            (mapped: Any) match
                case m: AskHandled @unchecked =>
                    assert(m.exit(4).eval == 50)
                case other =>
                    fail(s"expected a Handled, got $other")
            end match
        }

        "mapping an outer region leaves a nested inner region untouched" in {
            val hInner = loopAsk(1)
            val hOuter = loopAsk(2)
            val inner  = node(hInner)
            val outer  = Kyo.Handled(inner, hOuter, Arrow[Int])
            val mapped = (outer: Int < Any).map(_ + 1)
            (mapped: Any) match
                case m: AskHandled @unchecked =>
                    assert(m.handler eq hOuter)
                    assert(sameRef(m.value, inner))
                    assert(inner.exit eq Arrow[Int])
                case other =>
                    fail(s"expected a Handled, got $other")
            end match
        }

        "eval answers a cont region through its handler" in {
            val contAsk =
                new Handler.Cont[Const[Unit], Const[Int], Ask, Int, Any]:
                    def tag                                           = Tag[Ask]
                    def apply[X](input: Unit, cont: Int => Int < Ask) = cont(1)
            val n = Kyo.Handled(ask, contAsk, Arrow[Int])
            assert((n: Int < Any).eval == 1)
        }
    }

    "HandledState" - {
        "saves the region parts including the state" in {
            val h =
                new Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any, Int]:
                    def tag                               = Tag[Ask]
                    def apply[X](input: Unit, state: Int) = Loop.continue(state + 1, state)
            val n = Kyo.HandledState(ask, h, Arrow[Int], 7)
            assert(n.handler eq h)
            assert(n.state == 7)
            assert(n.exit eq Arrow[Int])
        }

        "map lands outside the region and keeps the state" in {
            val h =
                new Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any, Int]:
                    def tag                               = Tag[Ask]
                    def apply[X](input: Unit, state: Int) = Loop.continue(state + 1, state)
            val n      = Kyo.HandledState(ask, h, Arrow[Int], 7)
            val mapped = (n: Int < Any).map(_ + 1)
            (mapped: Any) match
                case m: Kyo.HandledState[Const[Unit], Const[Int], Ask, Int, Int, Any, Any, Int] @unchecked =>
                    assert(m.handler eq h)
                    assert(m.state == 7)
                    assert(m.exit(41).eval == 42)
                case other =>
                    fail(s"expected a HandledState, got $other")
            end match
        }

        "eval seeds the region from the node's state" in {
            val h =
                new Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any, Int]:
                    def tag                               = Tag[Ask]
                    def apply[X](input: Unit, state: Int) = Loop.continue(state + 1, state)
            // the node carries 41: the region resumes from the node's state
            val n = Kyo.HandledState(ask.map(_ + 1), h, Arrow[Int], 41)
            assert((n: Int < Any).eval == 42)
        }
    }

    "Defer and Handled render diagnostically" in {
        assert(Effect.defer(42).toString.startsWith("Kyo(Defer("))
        val n = node(loopAsk(1))
        assert(n.toString.startsWith("Kyo(Handled("))
        assert(n.toString.contains("Ask"))
    }

end KyoInternalTest
