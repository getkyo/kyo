package kyo.proto.kernel

import kyo.Frame
import kyo.proto.Arrow
import kyo.proto.kernel.internal.Kyo
import org.scalatest.freespec.AnyFreeSpec

class EffectTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = v.eval

    def inc(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
        new Arrow.Transform[Int, Int, Any]:
            def frame                                              = _frame
            def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) = v.map(i => next(i + 1))

    def double(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
        new Arrow.Transform[Int, Int, Any]:
            def frame                                              = _frame
            def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) = v.map(i => next(i * 2))

    "defer composes with maps without running early" in {
        var ran = false
        val d: Int < Any = Effect.defer {
            ran = true
            1
        }
        val r = d.map(_ + 1)
        assert(!ran)
        assert(eval(r) == 2)
        assert(ran)
    }

    "deferInline delays evaluation until the eval" in {
        var ran = false
        val d: Int < Any = Effect.deferInline {
            ran = true
            7
        }
        assert(!ran)
        assert(eval(d.map(_ * 6)) == 42)
        assert(ran)
    }

    "defer evaluates once per eval of a fresh value" in {
        var runs = 0
        def d: Int < Any = Effect.defer {
            runs += 1
            runs
        }
        assert(eval(d) == 1)
        assert(eval(d) == 2)
    }

    "nested defer calls run innermost last, in order" in {
        var order = List.empty[Int]
        val d: Int < Any = Effect.defer {
            order = 1 :: order
            Effect.defer {
                order = 2 :: order
                Effect.defer {
                    order = 3 :: order
                    42
                }
            }
        }
        assert(eval(d) == 42)
        assert(order == List(3, 2, 1))
    }

    "the deferral node" - {
        "runs the value into its continuation" in {
            assert(eval(Effect.defer(1: Int < Any, inc)) == 2)
        }

        "runs both continuations in order" in {
            assert(eval(Effect.defer(1: Int < Any, inc, double)) == 4)
            assert(eval(Effect.defer(1: Int < Any, double, inc)) == 3)
        }

        "collapses an identity second continuation into the one-continuation node" in {
            val node = Effect.defer(1: Int < Any, inc, Arrow.id[Int])
            node match
                case d: Kyo.Defer[?, ?, ?, ?] => assert(d.contB eq Arrow.id[Int])
                case other                    => fail(s"expected a deferral node, got $other")
            assert(eval(node) == 2)
        }

        "the four-argument form runs its three continuations in order" in {
            assert(eval(Effect.defer(1: Int < Any, inc, double, inc)) == 5)
            assert(eval(Effect.defer(1: Int < Any, double, inc, double)) == 6)
        }
    }

end EffectTest
