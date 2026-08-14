package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class KyoInternalTest extends AnyFreeSpec:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]

    def sameRef(a: Any, b: Any): Boolean =
        (a, b) match
            case (a: AnyRef, b: AnyRef) => a eq b
            case _                      => false

    "Defer" - {
        "map defers: the mapped node is the value and the arrow is the continuation" in {
            val a = ask
            val n = (a: Int < Ask).map(_ + 1)
            (n: Any) match
                case d: Kyo.Defer[?, ?, ?] =>
                    assert(sameRef(d.value, a))
                case other =>
                    fail(s"expected a Defer, got $other")
            end match
        }

        "an identity arrow returns the node unchanged" in {
            val a = ask
            val n =
                (a: Any) match
                    case kyo: Kyo[Int, Ask] @unchecked => kyo.map(Arrow[Int])
                    case other                         => fail(s"expected a node, got $other")
            assert(sameRef(n, a))
        }

        "stores the deferred value and the continuation arrow" in {
            val a     = ask
            val arrow = Arrow[Int]
            val d     = Kyo.defer[Int, Int, Ask](a, arrow)
            (d: Any) match
                case d: Kyo.Defer[?, ?, ?] =>
                    assert(sameRef(d.value, a))
                    assert(sameRef(d.cont, arrow))
                case other =>
                    fail(s"expected a Defer, got $other")
            end match
        }
    }

    "Suspend" - {
        "carries its tag and input and no continuation" in {
            (ask: Any) match
                case s: Kyo.Suspend[Const[Unit], Const[Int], Ask, ?, ?, ?] @unchecked =>
                    assert(s.tag <:< Tag[Ask])
                    assert(s.input == ())
                case other =>
                    fail(s"expected a Suspend, got $other")
            end match
        }
    }

    "HandleCont" - {
        // the clause resumes, so its result keeps raising Ask: the S2 row is
        // where a one-shot region types the effects its clause result carries
        def contNode(v: Int < Ask): Kyo.HandleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Ask] =
            new Kyo.HandleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Ask]:
                def tag   = Tag[Ask]
                def value = v
                def run[X](input: Unit, cont: Int => Int < Ask) =
                    cont(41)
                def complete(v: Int) = v * 10

        "saves the region parts" in {
            val v = ask.map(_ + 1)
            val n = contNode(v)
            assert(n.tag =:= Tag[Ask])
            assert(sameRef(n.value, v))
        }

        "run receives the continuation at its declared types" in {
            val out = contNode(ask).run[Any]((), o => o + 1)
            assert(out.evalNow.contains(42))
        }

        "run may end the region without resuming" in {
            val n =
                new Kyo.HandleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Any]:
                    def tag   = Tag[Ask]
                    def value = ask
                    def run[X](input: Unit, cont: Int => Int < Ask) =
                        -1
                    def complete(v: Int) = v
            assert(n.run[Any]((), o => o + 1).eval == -1)
        }

        "eval answers the region through run, the resumed row discharged by an outer region" in {
            val n = contNode(ask.map(_ + 1))
            val r = ArrowEffect.handleLoop(Tag[Ask], (n: Int < Ask))([X] => _ => Loop.continue(999))
            assert(r.eval == 42)
        }

        "eval takes complete when the value settles without the operation" in {
            val n = contNode((41: Int < Ask).map(_ + 1))
            val r = ArrowEffect.handleLoop(Tag[Ask], (n: Int < Ask))([X] => _ => Loop.continue(999))
            assert(r.eval == 420)
        }
    }

    "HandleLoop" - {
        def loopNode(
            v: Int < Ask,
            outcome: Loop.Outcome[Int < Ask, Int] < Any
        ): Kyo.HandleLoop[Const[Unit], Const[Int], Ask, Int, Int, Any] =
            new Kyo.HandleLoop[Const[Unit], Const[Int], Ask, Int, Int, Any]:
                def tag                 = Tag[Ask]
                def value               = v
                def run[X](input: Unit) = outcome
                def complete(v: Int)    = v * 10

        "run continues at its declared types" in {
            val out = loopNode(ask, Loop.continue(42)).run[Any](())
            (out: Any) match
                case c: Loop.Continue[Int < Ask] @unchecked => assert(c._1.evalNow.contains(42))
                case other                                  => fail(s"expected a continue, got $other")
        }

        "run dones with the bare value" in {
            val out = loopNode(ask, Loop.done(-1)).run[Any](())
            (out: Any) match
                case c: Loop.Continue[?] => fail(s"expected a done, got $c")
                case done: Int           => assert(done == -1)
                case other               => fail(s"expected the bare done value, got $other")
            end match
        }

        "eval answers the region in place and completes the settled value" in {
            val n = loopNode(ask.map(_ + 1), Loop.continue(41))
            assert((n: Int < Any).eval == 420)
        }

        "eval ends the region at a done outcome" in {
            val n = loopNode(ask.map(_ + 1), Loop.done(-1))
            assert((n: Int < Any).eval == -1)
        }

        "eval takes complete when the value settles without the operation" in {
            val n = loopNode((41: Int < Ask).map(_ + 1), Loop.continue(0))
            assert((n: Int < Any).eval == 420)
        }
    }

    "nodes render diagnostically" in {
        assert(Effect.defer(42).toString.startsWith("Kyo(Defer("))
        val mapped = ask.map(_ + 1).toString
        assert(mapped.startsWith("Kyo(") && mapped.contains("Ask"))
        val region = ArrowEffect.handleLoop(Tag[Ask], ask)([X] => _ => Loop.continue(1)).toString
        assert(region.startsWith("Kyo(HandleLoop(") && region.contains("Ask"))
    }

    "Nested" - {
        "lift passes a plain value through" in {
            val lifted: Int < Any = Nested.lift(42)
            assert(lifted.evalNow.contains(42))
        }

        "lift wraps a node so it stays data" in {
            val node             = ask
            val lifted: Any      = Nested.lift[Any, Any](node)
            val unnested: AnyRef = Nested.unnest[AnyRef](lifted)
            assert(!sameRef(lifted, node))
            assert(sameRef(unnested, node))
        }

        "unnest passes a plain value through" in {
            assert(Nested.unnest[Int](42) == 42)
        }
    }

end KyoInternalTest
