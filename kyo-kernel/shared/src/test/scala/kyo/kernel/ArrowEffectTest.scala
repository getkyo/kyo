package kyo.kernel

import kyo.*
import kyo.kernel.*
import kyo.kernel.internal.*

class ArrowEffectTest extends Test:

    sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]
    sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[Int]]
    sealed trait TestEffect3 extends ArrowEffect[Const[Boolean], Const[Double]]

    def testEffect1(i: Int): String < TestEffect1 =
        ArrowEffect.suspend[Any](Tag[TestEffect1], i)

    def testEffect2(s: String): Int < TestEffect2 =
        ArrowEffect.suspend[Any](Tag[TestEffect2], s)

    def testEffect3(b: Boolean): Double < TestEffect3 =
        ArrowEffect.suspend[Any](Tag[TestEffect3], b)

    "wireTap" - {
        "input/output" in {
            val effect = testEffect1(42)

            val tx = ArrowEffect.wireTap(Tag[TestEffect1], effect)(
                [C] => i => i + 1,
                [C] => (i, o) => o + " from " + i
            )

            val handled = ArrowEffect.handle(Tag[TestEffect1], tx):
                [C] => (input, cont) => cont(input.toString)

            assert(handled.eval == "43 from 42")

        }

        "input" in {
            val effect = testEffect1(42)

            val tx = ArrowEffect.wireTapInput(Tag[TestEffect1], effect):
                [C] => i => i + 1

            val handled = ArrowEffect.handle(Tag[TestEffect1], tx):
                [C] => (input, cont) => cont(input.toString)

            assert(handled.eval == "43")
        }

        "output" in {
            val effect = testEffect1(42)

            val tx = ArrowEffect.wireTapOutput(Tag[TestEffect1], effect)(
                [C] => (i, o) => o + " from " + i
            )

            val handled = ArrowEffect.handle(Tag[TestEffect1], tx):
                [C] => (input, cont) => cont(input.toString)

            assert(handled.eval == "42 from 42")
        }
    }

    "suspend" in {
        val effect = testEffect1(42)
        assert(effect.isInstanceOf[String < TestEffect1])
    }

    "handle" - {
        "single effect" in {
            val effect = testEffect1(42)
            val result = ArrowEffect.handle(Tag[TestEffect1], effect)(
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

            val result = ArrowEffect.handle(Tag[TestEffect1], Tag[TestEffect2], effect)(
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

            val result = ArrowEffect.handle(Tag[TestEffect1], Tag[TestEffect2], Tag[TestEffect3], effect)(
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.toInt),
                [C] => (input, cont) => cont(if input then 1.0 else 0.0)
            )

            assert(result.eval == 1.0)
        }

        "with state" in {
            val effect =
                for
                    s1 <- testEffect1(42)
                    s2 <- testEffect1(43)
                yield (s1, s2)

            val result = ArrowEffect.handleLoop(Tag[TestEffect1], 0, effect)(
                [C] => (input, state, cont) => Loop.continue(state + 1, cont((input + state).toString))
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

            val result = ArrowEffect.handle(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString)
            )

            assert(result.eval == 42)
            assert(maxDepth - minDepth <= 10)
        }
    }

    "handleFirst" - {
        "handles first occurrence of effect" in {
            val effect =
                for
                    s1 <- testEffect1(10)
                    s2 <- testEffect1(20)
                    s3 <- testEffect1(30)
                yield (s1, s2, s3)

            val result = ArrowEffect.handleFirst(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont("handled"),
                identity
            )

            val finalResult = ArrowEffect.handle(Tag[TestEffect1], result) {
                [C] => (input, cont) => cont(input.toString)
            }

            assert(finalResult.eval == ("handled", "20", "30"))
        }

        "preserves unhandled effects" in {
            val effect =
                for
                    s1 <- testEffect1(10)
                    i1 <- testEffect2("test")
                    s2 <- testEffect1(20)
                yield (s1, i1, s2)

            val result = ArrowEffect.handleFirst(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont("handled"),
                identity
            )

            val finalResult = ArrowEffect.handle(Tag[TestEffect1], Tag[TestEffect2], result)(
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.length)
            )

            assert(finalResult.eval == ("handled", 4, "20"))
        }

        "handles pure values correctly" in {
            val effect: String < Any = "pure"
            val result = ArrowEffect.handleFirst(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont("handled"),
                s => s + "-done"
            )

            val finalResult = ArrowEffect.handle(Tag[TestEffect1], result) {
                [C] => (input, cont) => cont(input.toString)
            }

            assert(finalResult.eval == "pure-done")
        }

        "stack safety with nested effects" in {
            def nested(n: Int): Int < TestEffect1 =
                if n == 0 then 42
                else testEffect1(n).map(_ => nested(n - 1))

            val effect = nested(10000)
            val result = ArrowEffect.handleFirst(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont("42"),
                identity
            )

            val finalResult = ArrowEffect.handle(Tag[TestEffect1], result) {
                [C] => (input, cont) => cont(input.toString)
            }

            assert(finalResult.eval == 42)
        }
    }

    "handle.catching" - {
        "failure" in {
            val effect = ArrowEffect.suspend[Int](Tag[TestEffect1], 42)
            val result = ArrowEffect.handleCatching(Tag[TestEffect1], effect)(
                [C] => (input, cont) => throw new RuntimeException("Test exception"),
                recover = {
                    case _: RuntimeException => "recovered"
                }
            )
            assert(result.eval == "recovered")
        }

        "success" in {
            val effect = ArrowEffect.suspend[Int](Tag[TestEffect1], 42)
            val result = ArrowEffect.handleCatching(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString),
                recover = {
                    case _: RuntimeException => "recovered"
                }
            )
            assert(result.eval == "42")
        }
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

        val result = ArrowEffect.handle(Tag[TestEffect1], Tag[TestEffect2], nested(1000))(
            [C] => (input, cont) => cont(input.toString),
            [C] => (input, cont) => cont(input.toInt)
        )

        assert(result.eval == 500500)
    }

    "non-Const inputs/outputs" - {
        sealed trait CustomEffect extends ArrowEffect[List, Option]

        def customEffect(input: List[Int]): Option[Int] < CustomEffect =
            ArrowEffect.suspend[Int](Tag[CustomEffect], input)

        "suspend and handle" in {
            val effect = customEffect(List(1, 2, 3))
            val result = ArrowEffect.handle(Tag[CustomEffect], effect)(
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

            val result = ArrowEffect.handle(Tag[CustomEffect], effect)(
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

            val result = ArrowEffect.handleLoop(Tag[CustomEffect], 0, effect)(
                [C] => (input, state, cont) => Loop.continue(state + 1, cont(Some(input(state))))
            )
            assert(result.eval == (Some(1), Some(5)))
        }
    }

    "handlePartial" - {
        abstract class TestInterceptor extends Safepoint.Interceptor:
            def addFinalizer(f: () => Unit): Unit    = {}
            def removeFinalizer(f: () => Unit): Unit = {}

        "evaluates pure values" in {
            val x: Int < Any = 5
            val result = ArrowEffect.handlePartial(
                Tag[TestEffect1],
                Tag[TestEffect2],
                x,
                Context.empty
            )(
                stop =
                    false,
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.toInt)
            )
            assert(result.evalNow == Maybe(5))
        }

        "suspends at effects" in {
            val x: Int < TestEffect1 = testEffect1(5).map(_ => 6)
            val result = ArrowEffect.handlePartial(
                Tag[TestEffect1],
                Tag[TestEffect2],
                x,
                Context.empty
            )(
                stop =
                    false,
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.toInt)
            )
            assert(result.evalNow == Maybe(6))
        }

        "respects the stop condition" in {
            var called       = false
            val x: Int < Any = Effect.defer(5)
            val result = ArrowEffect.handlePartial(
                Tag[TestEffect1],
                Tag[TestEffect2],
                x,
                Context.empty
            )(
                stop =
                    true,
                [C] =>
                    (input, cont) =>
                        called = true;
                        cont(input.toString)
                ,
                [C] =>
                    (input, cont) =>
                        called = true; cont(input.toInt)
            )
            assert(!called)
            assert(result.evalNow.isEmpty)
        }

        "evaluates nested suspensions" in {
            val x: Int < Any = Effect.defer(Effect.defer(5))
            val result = ArrowEffect.handlePartial(
                Tag[TestEffect1],
                Tag[TestEffect2],
                x,
                Context.empty
            )(
                stop =
                    false,
                [C] => (input, cont) => cont(input.toString),
                [C] => (input, cont) => cont(input.toInt)
            )
            assert(result.evalNow == Maybe(5))
        }
    }

    "effects with variance" - {

        "delimited continuation" - {

            sealed trait Delim[R, +S] extends ArrowEffect[[A] =>> Delim.Op[A, R, S], Id]

            object Delim:

                enum Op[A, R, -S]:
                    case Shift[A, R, S](f: (A => R < (Delim[R, S] & S)) => R < (Delim[R, S] & S)) extends Op[A, R, S]

                def shift[A, R: Tag, S](f: (A => R < (Delim[R, S] & S)) => R < (Delim[R, S] & S))(using
                    tag: Tag[Delim[R, S]]
                ): A < (Delim[R, S] & S) =
                    ArrowEffect.suspend[A](tag, Op.Shift(f))

                def run[R: Tag, S](v: R < (Delim[R, S] & S))(using tag: Tag[Delim[R, S]]): R < S =
                    ArrowEffect.handle(tag, v)(
                        [A] =>
                            (input, cont) =>
                                input match
                                    case Op.Shift(f) =>
                                        // the compiler currently can't prove that the shift effect set
                                        // is the same as the one being handled as restricted by the method signature
                                        f(cont(_).asInstanceOf)
                    )

            end Delim

            "multi shot" in run {
                Delim.shift[Int, Int, Any] { k =>
                    k(42).map(a => k(42 + 1).map(b => a + b))
                }.map(_ * 10)
                    .handle(Delim.run)
                    .map { v =>
                        assert(v == (42 * 10) + ((42 + 1) * 10))
                    }
            }

            "multi shot with other effect" in run {
                Delim.shift[Int, Int, TestEffect2] { k =>
                    k(42).map(a => testEffect2("a").map(k).map(b => a + b))
                }.map(_ * 10)
                    .handle(
                        Delim.run,
                        ArrowEffect.handle(Tag[TestEffect2], _) {
                            [C] => (input, cont) => cont(input.size)
                        }
                    ).map { v =>
                        assert(v == (42 * 10) + ("a".size * 10))
                    }
            }

            "multiple shift with different effect sets" in run {
                Delim.shift[Int, Int, TestEffect2] { k =>
                    k(42).map { r =>
                        testEffect2("a").map(v => r + v)
                    }
                }.map(_ * 10).map { v1 =>
                    Delim.shift[Int, Int, TestEffect1] { k =>
                        k(42).map { r =>
                            testEffect1(v1).map(s => r + s.length)
                        }
                    }
                }
                    .handle(
                        Delim.run,
                        ArrowEffect.handle(Tag[TestEffect1], _) {
                            [C] => (input, cont) => cont(input.toString)
                        },
                        ArrowEffect.handle(Tag[TestEffect2], _) {
                            [C] => (input, cont) => cont(input.size)
                        }
                    ).map { v =>
                        assert(v == 46)
                    }
            }

            "short circuiting" in run {
                def test(numbers: List[Int], expected: Int) =
                    Kyo.foldLeft(numbers)(0) { (acc, n) =>
                        if n < 0 || n == 42 then
                            Delim.shift[Int, Int, Any] { _ => -1 }
                        else
                            acc + n
                    }
                        .handle(Delim.run)
                        .map { r =>
                            assert(r == expected)
                        }

                for
                    _ <- test(List(1, 2), 3)
                    _ <- test(List(1, 2, -1), -1)
                    _ <- test(List(1, 2, 42, 3), -1)
                yield succeed
                end for
            }
        }

        "flow effect with dynamic tags" - {
            sealed trait Flow[+In, -Out] extends ArrowEffect[Flow.Op[In, Out, *], Id]

            object Flow:
                enum Op[-In, +Out, R]:
                    case Poll[V]()     extends Op[V, Nothing, Maybe[V]]
                    case Emit[V](v: V) extends Op[Any, V, Unit]

                def emit[V: Tag](value: V): Unit < Flow[Any, V] =
                    ArrowEffect.suspend(Tag[Flow[Any, V]], Op.Emit(value))

                def poll[V: Tag]: Maybe[V] < Flow[V, Nothing] =
                    ArrowEffect.suspend(Tag[Flow[V, Nothing]], Op.Poll())

                def run[A, S, In: Tag, Out: Tag](in: Chunk[In])(v: A < (Flow[In, Out] & S)): (Chunk[In], Chunk[Out], A) < S =
                    ArrowEffect.handleLoop(Tag[Flow[In, Out]], (in, Chunk.empty[Out]), v)(
                        [C] =>
                            (input, state, cont) =>
                                val (in, out) = state
                                (input: @unchecked) match
                                    case Op.Emit(v) =>
                                        Loop.continue((in, out.append(v)), cont(()))
                                    case Op.Poll() =>
                                        Loop.continue((in.tail, out), cont(in.headMaybe))
                                end match
                        ,
                        (state, r) => (state._1, state._2, r)
                    )

            end Flow

            "single poll" in run {
                def test(source: Chunk[Int], in: Chunk[Int], out: Chunk[Int], result: Maybe[Int]) =
                    Flow.poll[Int]
                        .handle(Flow.run(source))
                        .map { (i, o, r) =>
                            assert(i == in)
                            assert(o == out)
                            assert(r == result)
                        }
                for
                    _ <- test(Chunk.empty, Chunk.empty, Chunk.empty, Absent)
                    _ <- test(Chunk(1), Chunk.empty, Chunk.empty, Present(1))
                    _ <- test(Chunk(1, 2), Chunk(2), Chunk.empty, Present(1))
                yield succeed
                end for
            }

            "poll and emit" in run {
                def test(source: Chunk[Int], out: Chunk[Int], result: Int) =
                    Loop(0) { acc =>
                        Flow.poll[Int].map {
                            case Absent     => Loop.done(acc)
                            case Present(v) => Flow.emit(v + 1).andThen(Loop.continue(acc + v))
                        }
                    }.handle(Flow.run(source)).map { (i, o, r) =>
                        assert(i.isEmpty)
                        assert(o == out)
                        assert(r == result)
                    }
                for
                    _ <- test(Chunk.empty, Chunk.empty, 0)
                    _ <- test(Chunk(1), Chunk(2), 1)
                    _ <- test(Chunk(1, 2), Chunk(2, 3), 3)
                yield succeed
                end for

            }

            "multiple flows in the same computation" in run {
                def test(
                    iSource: Chunk[Int],
                    sSource: Chunk[String],
                    iOut: Chunk[Int],
                    sOut: Chunk[String]
                ) =
                    val a =
                        Loop(0) { acc =>
                            Kyo.zip(Flow.poll[Int], Flow.poll[String]).map(_.zip(_)).map {
                                case Absent => Loop.done(acc)
                                case Present((i, s)) =>
                                    Flow.emit(i + 1).andThen(Flow.emit(s + "a")).andThen(Loop.continue(acc + i + s.size))
                            }
                        }
                    val b: (Chunk[Int], Chunk[Int], Int) < Flow[String, String] =
                        Flow.run(iSource)(a)

                    val c = Flow.run(sSource)(b)
                    c.map {
                        case (si, so, (ii, io, r)) =>
                            assert(so == sOut)
                            assert(io == iOut)
                    }
                end test
                for
                    _ <- test(Chunk(1), Chunk.empty, Chunk.empty, Chunk.empty)
                    _ <- test(Chunk.empty, Chunk("a"), Chunk.empty, Chunk.empty)
                    _ <- test(Chunk(1), Chunk("a"), Chunk(2), Chunk("aa"))
                    _ <- test(Chunk(1, 2), Chunk("a", "b"), Chunk(2, 3), Chunk("aa", "ba"))
                yield succeed
                end for
            }

        }
    }

end ArrowEffectTest
