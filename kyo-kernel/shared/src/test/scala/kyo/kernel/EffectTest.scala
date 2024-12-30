package kyo.kernel

import kyo.*
import kyo.kernel.*

class EffectTest extends Test:

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
            assertThrows[Exception] {
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
