package kyo.kernel

import kyo.*
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class EffectTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    sealed trait Wrap extends ArrowEffect[Const[Unit], Const[Unit]]

    def recovering[A](v: A < Wrap)(f: Throwable => A): A < Any =
        ArrowEffect.handleCont(Tag[Wrap], v)([C] => (_, cont) => cont(()), a => a, ex => Maybe(f(ex)))

    def inc: Arrow[Int, Int, Any]    = Arrow[Int](i => i + 1)
    def double: Arrow[Int, Int, Any] = Arrow[Int](i => i * 2)

    "defer" - {

        "simple" in {
            var executed = false
            val effect   = Effect.defer {
                executed = true
                42
            }
            assert(!executed)
            assert(effect.eval == 42)
            assert(executed)
        }

        "nested defer calls" in {
            var order  = List.empty[Int]
            val effect = Effect.defer {
                order = 1 :: order
                Effect.defer {
                    order = 2 :: order
                    Effect.defer {
                        order = 3 :: order
                        42
                    }
                }
            }
            assert(effect.eval == 42)
            assert(order == List(3, 2, 1))
        }

        "defer composes with maps without running early" in {
            var ran          = false
            val d: Int < Any = Effect.defer {
                ran = true
                1
            }
            val r = d.map(_ + 1)
            assert(!ran)
            assert(r.eval == 2)
            assert(ran)
        }

        "defer suspends effects performed by its body" in {
            var ran          = false
            val d: Int < Ask = Effect.defer {
                ran = true
                ask.map(_ + 1)
            }
            assert(!ran)
            assert(answerAsk(41)(d).eval == 42)
            assert(ran)
        }

        "deferInline delays evaluation until the eval" in {
            var ran          = false
            val d: Int < Any = Effect.deferInline {
                ran = true
                7
            }
            assert(!ran)
            assert(d.map(_ * 6).eval == 42)
            assert(ran)
        }

        "defer evaluates once per eval of a fresh value" in {
            var runs         = 0
            def d: Int < Any = Effect.defer {
                runs += 1
                runs
            }
            assert(d.eval == 1)
            assert(d.eval == 2)
        }
    }

    "defer with a recovery inside" in {
        val effect = Effect.defer(recovering(Effect.defer((throw new RuntimeException("Test exception")): Int))(_ => 42))
        assert(effect.eval == 42)
    }

    "combining multiple effects" in {
        val effect =
            for
                a <- Effect.defer(1)
                b <- recovering(Effect.defer(2 / 0))(_ => 2)
                c <- Effect.defer(3)
            yield a + b + c

        assert(effect.eval == 6)
    }

    "the deferral node" - {
        "runs the value into its continuation" in {
            assert(Effect.defer(1: Int < Any, inc).eval == 2)
        }

        "defers a pending value" in {
            assert(answerAsk(41)(Effect.defer(ask, inc)).eval == 42)
        }

        "runs both continuations in order" in {
            assert(Effect.defer(1: Int < Any, inc, double).eval == 4)
            assert(Effect.defer(1: Int < Any, double, inc).eval == 3)
        }

        "an identity second continuation leaves the result unchanged" in {
            assert(Effect.defer(1: Int < Any, inc, Arrow.id[Int]).eval == 2)
        }

        "the four-argument form runs its three continuations in order" in {
            assert(Effect.defer(1: Int < Any, inc, double, inc).eval == 5)
            assert(Effect.defer(1: Int < Any, double, inc, double).eval == 6)
        }
    }

end EffectTest
