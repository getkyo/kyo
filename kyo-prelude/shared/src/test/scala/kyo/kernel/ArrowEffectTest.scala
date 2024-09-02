package kyo.kernel

import kyo.Maybe
import kyo.Tag
import kyo.Test
import kyo.kernel.*

class EffectTest extends Test:

    sealed trait TestEffect1 extends Effect[Const[Int], Const[String]]
    sealed trait TestEffect2 extends Effect[Const[String], Const[Int]]
    sealed trait TestEffect3 extends Effect[Const[Boolean], Const[Double]]

    def testEffect1(i: Int): String < TestEffect1 =
        Effect.suspend[Any](Tag[TestEffect1], i)

    def testEffect2(s: String): Int < TestEffect2 =
        Effect.suspend[Any](Tag[TestEffect2], s)

    def testEffect3(b: Boolean): Double < TestEffect3 =
        Effect.suspend[Any](Tag[TestEffect3], b)

    "suspend" in {
        val effect = testEffect1(42)
        assert(effect.isInstanceOf[String < TestEffect1])
    }

    "handle" - {
        "single effect" in {
            val effect = testEffect1(42)
            val result = Effect.handle(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString)
            )
            assert(result.eval == "42")
        }

        "two effects" in {
            val effect =
                for
                    s <- testEffect1(42)
                    i <- testEffect2(s)
                yield i

            val result = Effect.handle(Tag[TestEffect1], Tag[TestEffect2], effect)(
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.toInt)
            )

            assert(result.eval == 42)
        }

        "three effects" in {
            val effect =
                for
                    s <- testEffect1(42)
                    i <- testEffect2(s)
                    d <- testEffect3(i % 2 == 0)
                yield d

            val result = Effect.handle(Tag[TestEffect1], Tag[TestEffect2], Tag[TestEffect3], effect)(
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.toInt),
                [C] => (input, cont) => cont(if input then 1.0 else 0.0)
            )

            assert(result.eval == 1.0)
        }

        "with accept" in {
            val effect = testEffect1(42)
            val result = Effect.handle(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString),
                done = identity,
                accept = [C] => _ == 42
            )
            assert(result.eval == "42")
        }

        "with state" in {
            val effect =
                for
                    s1 <- testEffect1(42)
                    s2 <- testEffect1(43)
                yield (s1, s2)

            val result = Effect.handle.state(Tag[TestEffect1], 0, effect)(
                [C] => (input, state, cont) => (state + 1, cont((input + state).toString))
            )

            assert(result.eval == ("42", "44"))
        }

        "execution is tail-recursive" in {
            var minDepth = Int.MaxValue
            var maxDepth = 0
            def loop(i: Int): Int < TestEffect1 =
                val depth = (new Exception).getStackTrace().size
                if depth < minDepth then minDepth = depth
                if depth > maxDepth then maxDepth = depth
                if i == 0 then 42
                else testEffect1(i).map(_ => loop(i - 1))
            end loop

            val effect = loop(10000)

            val result = Effect.handle(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString)
            )

            assert(result.eval == 42)
            assert(maxDepth - minDepth <= 10)
        }
    }

    "handle.catching" - {
        "failure" in {
            val effect = Effect.suspend[Int](Tag[TestEffect1], 42)
            val result = Effect.handle.catching(Tag[TestEffect1], effect)(
                [C] => (_, _) => throw new RuntimeException("Test exception"),
                recover = {
                    case _: RuntimeException => "recovered"
                }
            )
            assert(result.eval == "recovered")
        }

        "success" in {
            val effect = Effect.suspend[Int](Tag[TestEffect1], 42)
            val result = Effect.handle.catching(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString),
                recover = {
                    case _: RuntimeException => "recovered"
                }
            )
            assert(result.eval == "42")
        }
    }

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

            val result = Effect.handle(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString)
            )

            assert(result.eval == "caught")
        }
    }

    "defer" in {
        var executed = false
        val effect = Effect.defer {
            executed = true
            42
        }
        assert(!executed)
        assert(effect.eval == 42)
        assert(executed)
    }

    "deeply nested effects" in {
        def nested(n: Int): Int < (TestEffect1 & TestEffect2) =
            if n == 0 then 0
            else
                for
                    s <- testEffect1(n)
                    i <- testEffect2(s)
                    r <- nested(n - 1)
                yield i + r

        val result = Effect.handle(Tag[TestEffect1], Tag[TestEffect2], nested(1000))(
            [C] => (input, cont) => cont(input.toString),
            [C] => (input, cont) => cont(input.toInt)
        )

        assert(result.eval == 500500)
    }

    "non-Const inputs/outputs" - {
        sealed trait CustomEffect extends Effect[List, Option]

        def customEffect(input: List[Int]): Option[Int] < CustomEffect =
            Effect.suspend[Int](Tag[CustomEffect], input)

        "suspend and handle" in {
            val effect = customEffect(List(1, 2, 3))
            val result = Effect.handle(Tag[CustomEffect], effect)(
                [C] => (input, cont) => cont(input.headOption)
            )
            assert(result.eval == Some(1))
        }

        "chained effects" in {
            val effect =
                for
                    a <- customEffect(List(1, 2, 3))
                    b <- customEffect(List(4, 5, 6))
                yield (a, b)

            val result = Effect.handle(Tag[CustomEffect], effect)(
                [C] => (input, cont) => cont(input.headOption)
            )
            assert(result.eval == (Some(1), Some(4)))
        }

        "handle with state" in {
            val effect =
                for
                    a <- customEffect(List(1, 2, 3))
                    b <- customEffect(List(4, 5, 6))
                yield (a, b)

            val result = Effect.handle.state(Tag[CustomEffect], 0, effect)(
                [C] => (input, state, cont) => (state + 1, cont(Some(input(state))))
            )
            assert(result.eval == (Some(1), Some(5)))
        }
    }

    "Effect.partial" - {

        "evaluates pure values" in {
            val x: Int < Any = 5
            val result = Effect.handle.partial(
                Tag[TestEffect1],
                Tag[TestEffect2],
                Tag[TestEffect3],
                x,
                Context.empty
            )(
                stop = false,
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.toInt),
                [C] => (input, cont) => cont(if input then 1.0 else 0.0)
            )
            assert(result.evalNow == Maybe(5))
        }

        "suspends at effects" in {
            val x: Int < TestEffect1 = testEffect1(5).map(_ => 6)
            val result = Effect.handle.partial(
                Tag[TestEffect1],
                Tag[TestEffect2],
                Tag[TestEffect3],
                x,
                Context.empty
            )(
                stop = false,
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.toInt),
                [C] => (input, cont) => cont(if input then 1.0 else 0.0)
            )
            assert(result.evalNow == Maybe(6))
        }

        "respects the stop condition" in {
            var called       = false
            val x: Int < Any = Effect.defer(5)
            val result = Effect.handle.partial(
                Tag[TestEffect1],
                Tag[TestEffect2],
                Tag[TestEffect3],
                x,
                Context.empty
            )(
                stop = true,
                [C] =>
                    (input, cont) =>
                        called = true; cont(input.toString)
                ,
                [C] =>
                    (input, cont) =>
                        called = true; cont(input.toInt)
                ,
                [C] =>
                    (input, cont) =>
                        called = true; cont(if input then 1.0 else 0.0)
            )
            assert(!called)
            assert(result.evalNow.isEmpty)
        }

        "evaluates nested suspensions" in {
            val x: Int < Any = Effect.defer(Effect.defer(5))
            val result = Effect.handle.partial(
                Tag[TestEffect1],
                Tag[TestEffect2],
                Tag[TestEffect3],
                x,
                Context.empty
            )(
                stop = false,
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.toInt),
                [C] => (input, cont) => cont(if input then 1.0 else 0.0)
            )
            assert(result.evalNow == Maybe(5))
        }
    }

end EffectTest
