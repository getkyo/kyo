package kyo.kernel

import kyo.Frame
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec

class KyoTest extends AnyFreeSpec:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())

    type AskHandled = Kyo.Handled[Const[Unit], Const[Int], Ask, Int, Int, Any]

    def loopAsk(value: Int): Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Any] =
        new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Any](Tag[Ask]):
            def apply[X](input: Unit) = Handler.Loop.continue(value)

    def node(h: Handler[Const[Unit], Const[Int], Ask]): AskHandled =
        new Kyo.Handled(ask, h, Arrow[Int])

    def sameRef(a: Any, b: Any): Boolean =
        (a, b) match
            case (a: AnyRef, b: AnyRef) => a eq b
            case _                      => false

    "Handled" - {

        "saves the region parts" in {
            val h = loopAsk(1)
            val n = node(h)
            assert(n.handler eq h)
            assert(n.cont eq Arrow[Int])
        }

        "map lands outside the region" in {
            val h      = loopAsk(1)
            val n      = node(h)
            val mapped = (n: Int < Any).map(_ + 1)
            (mapped: Any) match
                case m: AskHandled @unchecked =>
                    assert(m.handler eq h)
                    assert(sameRef(m.value, n.value))
                    assert(m.cont(41).eval == 42)
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
                    assert(m.cont(4).eval == 50)
                case other =>
                    fail(s"expected a Handled, got $other")
            end match
        }

        "mapping an outer region leaves a nested inner region untouched" in {
            val hInner = loopAsk(1)
            val hOuter = loopAsk(2)
            val inner  = node(hInner)
            val outer  = new Kyo.Handled(inner, hOuter, Arrow[Int])
            val mapped = (outer: Int < Any).map(_ + 1)
            (mapped: Any) match
                case m: AskHandled @unchecked =>
                    assert(m.handler eq hOuter)
                    assert(sameRef(m.value, inner))
                    assert(inner.cont eq Arrow[Int])
                case other =>
                    fail(s"expected a Handled, got $other")
            end match
        }

        "eval cannot handle a cont region yet" in {
            val contAsk =
                new Handler.Cont[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                    def apply[X](input: Unit, cont: Int => Int < (Ask & Any)): Int < (Ask & Any) = cont(1)
            val n = new Kyo.Handled(ask, contAsk, Arrow[Int])
            intercept[IllegalStateException]((n: Int < Any).eval)
        }
    }

end KyoTest
