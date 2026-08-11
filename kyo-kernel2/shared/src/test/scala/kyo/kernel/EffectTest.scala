package kyo.kernel

import kyo.Frame
import kyo.Tag
import kyo.kernel.internal.*
import org.scalatest.freespec.AnyFreeSpec

class EffectTest extends AnyFreeSpec:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]

    def testEffect1(i: Int): String < TestEffect1 =
        ArrowEffect.suspend[Any](Tag[TestEffect1], i)

    "catching" - {
        "match" in {
            val effect = Effect.catching {
                throw new RuntimeException("Test exception")
            } {
                case _: RuntimeException => 42
            }

            assert(effect.eval == 42)
        }

        "no match" in {
            intercept[Exception] {
                Effect.catching {
                    throw new Exception("Test exception")
                } {
                    case _: RuntimeException => 42
                }.eval
            }
        }

        "failure in map" in {
            val effect = Effect.catching {
                testEffect1(42).map(_ => (throw new RuntimeException("Test exception")): String)
            } {
                case _: RuntimeException => "caught"
            }

            val result = ArrowEffect.handle(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString)
            )

            assert(result.eval == "caught")
        }

        "multiple exception types" in {
            def testCatching(ex: Throwable) = Effect.catching {
                throw ex
            } {
                case _: IllegalArgumentException => "Illegal Argument"
                case _: RuntimeException         => "Runtime"
                case _                           => "Other"
            }

            assert(testCatching(new RuntimeException()).eval == "Runtime")
            assert(testCatching(new IllegalArgumentException()).eval == "Illegal Argument")
            assert(testCatching(new Exception()).eval == "Other")
        }

        "failure in a map after a region" in {
            val region = ArrowEffect.handleLoop(Tag[TestEffect1], testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                [C] => input => Loop.continue(input.toString)
            )
            val effect = Effect.catching {
                region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
            } {
                case _: RuntimeException => "caught"
            }
            assert(effect.eval == "caught")
        }

        "failure in a map after a stateful region" in {
            val region = ArrowEffect.handleLoop(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                [C] => (input, state) => Loop.continue(state + 1, (input * state).toString)
            )
            val effect = Effect.catching {
                region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
            } {
                case _: RuntimeException => "caught"
            }
            assert(effect.eval == "caught")
        }

        "failure after a stateful region reached through a continuation" in {
            val effect = Effect.catching {
                testEffect1(3).map { prefix =>
                    val region = ArrowEffect.handleLoop(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                        [C] => (input, state) => Loop.continue(state + 1, (input * state).toString)
                    )
                    region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else prefix + s)
                }
            } {
                case _: RuntimeException => "caught"
            }
            val result = ArrowEffect.handle(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString)
            )
            assert(result.eval == "caught")
        }

        "a stateful region threads state under catching" in {
            val region = ArrowEffect.handleLoop(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                [C] => (input, state) => Loop.continue(state + 1, (input * state).toString)
            )
            val effect = Effect.catching(region) {
                case _: RuntimeException => "caught"
            }
            assert(effect.eval == "716")
        }
    }

    "defer" - {

        "simple" in {
            var executed = false
            val effect = Effect.defer {
                executed = true
                42
            }
            assert(!executed)
            assert(effect.eval == 42)
            assert(executed)
        }

        "nested defer calls" in {
            var order = List.empty[Int]
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
    }

    "defer with catching" in {
        val effect = Effect.defer {
            Effect.catching {
                throw new RuntimeException("Test exception")
            } {
                case _: RuntimeException => 42
            }
        }
        assert(effect.eval == 42)
    }

    "combining multiple effects" in {
        val effect =
            for
                a <- Effect.defer(1)
                b <- Effect.catching(2 / 0) { case _: ArithmeticException => 2 }
                c <- Effect.defer(3)
            yield a + b + c

        assert(effect.eval == 6)
    }

end EffectTest
