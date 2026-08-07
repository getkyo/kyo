package kyo.kernel2

import kyo.Chunk
import kyo.Id
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Tag
import kyo.discard
import kyo.test.Test
import language.implicitConversions

sealed trait Echo extends ArrowEffect[Const[Int], Const[Int]]
sealed trait Get  extends ArrowEffect[Const[Unit], Const[Int]]
sealed trait Fail extends ArrowEffect[Const[String], Const[Nothing]]

class ArrowEffectTest extends Test[Any]:

    sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]
    sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[Int]]
    sealed trait TestEffect3 extends ArrowEffect[Const[Boolean], Const[Double]]

    def testEffect1(i: Int): String < TestEffect1 =
        ArrowEffect.suspend[Any](Tag[TestEffect1], i)

    def testEffect2(s: String): Int < TestEffect2 =
        ArrowEffect.suspend[Any](Tag[TestEffect2], s)

    def testEffect3(b: Boolean): Double < TestEffect3 =
        ArrowEffect.suspend[Any](Tag[TestEffect3], b)

    "suspend" in {
        val effect: String < TestEffect1 = testEffect1(42)
        discard(effect)
        succeed("ArrowEffect.suspend produces a String < TestEffect1; the type ascription above is the verification")
    }

    "handle" - {
        "single effect" in {
            val effect = testEffect1(42)
            val result = ArrowEffect.handle(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString)
            )
            assert(result.eval == "42")
        }

        // the multi-tag handle overloads are not provided in kernel2 (ruled: handlePartial covers
        // the runtime boundary); the current kernel's multi-tag scenarios run as nested handles
        "two effects" in {
            val effect =
                for
                    s <- testEffect1(42)
                    i <- testEffect2(s)
                yield i

            val result = ArrowEffect.handle(
                Tag[TestEffect2],
                ArrowEffect.handle(Tag[TestEffect1], effect)(
                    [C] => (input, cont) => cont(input.toString)
                )
            )(
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

            val result = ArrowEffect.handle(
                Tag[TestEffect3],
                ArrowEffect.handle(
                    Tag[TestEffect2],
                    ArrowEffect.handle(Tag[TestEffect1], effect)(
                        [C] => (input, cont) => cont(input.toString)
                    )
                )(
                    [C] => (input, cont) => cont(input.toInt)
                )
            )(
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
            // kernel2's dispatch cycle is a few frames deeper than the current kernel's loop;
            // the bound proves the depth stays constant across the 10000 iterations
            assert(maxDepth - minDepth <= 20)
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

            val finalResult = ArrowEffect.handle(
                Tag[TestEffect2],
                ArrowEffect.handle(Tag[TestEffect1], result)(
                    [C] => (input, cont) => cont(input.toString)
                )
            )(
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

        val result = ArrowEffect.handle(
            Tag[TestEffect2],
            ArrowEffect.handle(Tag[TestEffect1], nested(1000))(
                [C] => (input, cont) => cont(input.toString)
            )
        )(
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

    // the current kernel's handlePartial takes two tags plus a context; kernel2's single-tag
    // Maybe protocol is the ruled shape (IOTask adapts), so these scenarios run against it
    "handlePartial" - {
        "evaluates pure values" in {
            val x: Int < TestEffect1 = 5
            val result = ArrowEffect.handlePartial(Tag[TestEffect1], x)(
                [C] => (input, cont) => Maybe(cont(input.toString))
            )
            assert(result.evalNow == Maybe(5))
        }

        "resolves effects while driving" in {
            val x: Int < TestEffect1 = testEffect1(5).map(_ => 6)
            val result = ArrowEffect.handlePartial(Tag[TestEffect1], x)(
                [C] => (input, cont) => Maybe(cont(input.toString))
            )
            assert(result.evalNow == Maybe(6))
        }

        "respects the preempt condition" in {
            var called               = false
            val x: Int < TestEffect1 = Effect.defer(5)
            val result = ArrowEffect.handlePartial(Tag[TestEffect1], x, () => true, 1)(
                [C] =>
                    (input, cont) =>
                        called = true
                        Maybe(cont(input.toString))
            )
            assert(!called)
            assert(result.evalNow.isEmpty)
        }

        "evaluates nested suspensions" in {
            val x: Int < TestEffect1 = Effect.defer(Effect.defer(5))
            val result = ArrowEffect.handlePartial(Tag[TestEffect1], x)(
                [C] => (input, cont) => Maybe(cont(input.toString))
            )
            assert(result.evalNow == Maybe(5))
        }
    }

    "nested effects handling" - {

        given [A, B]: CanEqual[A, B] = CanEqual.derived

        sealed trait NestedTestEffect extends ArrowEffect[Const[Int], Const[Int]]

        def suspendNested(i: Int): Int < NestedTestEffect =
            ArrowEffect.suspend[Any](Tag[NestedTestEffect], i)

        val nestedTag: Tag[NestedTestEffect] = Tag[NestedTestEffect]

        def flattenNested[A, B, C](v: A < B < C): A < (B & C) = v.map(a => a)

        "not handle Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handle(nestedTag, v):
                    [C] => (input, cont) => cont(input * 10)

            "unwraps Nested and returns inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)
                val result: Int < NestedTestEffect < Any = handle(nested)

                assert(result == nested, "handleSimple should return the nested computation")

                val flattened   = flattenNested(result)
                val finalResult = handle(flattened)

                assert(finalResult.eval == 50)
            }
        }

        "handleFirst on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < (S & NestedTestEffect) =
                ArrowEffect.handleFirst(nestedTag, v)(
                    [C] => (input, cont) => cont(input * 10),
                    identity
                )

            "done callback receives unwrapped value" in {
                val comp                                 = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result = handle(nested)

                assert(result == nested, "handleFirst should return the nested computation")

                val flattened                           = flattenNested(result)
                val finalResult: Int < NestedTestEffect = handle(flattened)
                assert(finalResult.evalNow == Maybe(50))
            }
        }

        "handleLoop (stateless) on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handleLoop(Tag[NestedTestEffect], v):
                    [C] => (input, cont) => Loop.continue(cont(input * 10))

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result = handle(nested)
                assert(result == nested, "handleLoop should return the nested computation")

                val flattened              = flattenNested(result)
                val finalResult: Int < Any = handle(flattened)

                assert(finalResult.eval == 50)
            }
        }

        "handleLoop (stateful) on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handleLoop(nestedTag, 0, v)(
                    [C] => (input, state, cont) => Loop.continue(state + 1, cont((input + state) * 10))
                )

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result = handle(nested)
                assert(result == nested, "handleLoop should return the nested computation")

                val flattened              = flattenNested(result)
                val finalResult: Int < Any = handle(flattened)

                assert(finalResult.eval == 50)
            }

        }

        "handleLoop (stateful + done) on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handleLoop(nestedTag, 0, v)(
                    [C] => (input, state, cont) => Loop.continue(state + 1, cont(input * 10)),
                    (state, v) => v
                )

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result = handle(nested)
                assert(result == nested, "handleLoop should return the nested computation")

                val flattened              = flattenNested(result)
                val finalResult: Int < Any = handle(flattened)

                assert(finalResult.eval == 50)
            }
        }

        "handleCatching on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handleCatching(nestedTag, v)(
                    [C] => (input, cont) => cont(input * 10),
                    recover = e => throw e
                )

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result = handle(nested)
                assert(result == nested, "handleLoop should return the nested computation")

                val flattened              = flattenNested(result)
                val finalResult: Int < Any = handle(flattened)

                assert(finalResult.eval == 50)
            }
        }

        "handlePartial on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < (S & NestedTestEffect) =
                ArrowEffect.handlePartial(nestedTag, v)(
                    [C] => (input, cont) => Maybe(cont(input * 10))
                )

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result = handle(nested)
                assert(result == nested, "handlePartial should return the nested computation")

                val flattened   = flattenNested(result)
                val finalResult = handle(flattened)
                assert(finalResult.evalNow == Maybe(50))
            }
        }
    }

    "effects with variance" - {

        def foldLeftList[A, B, S](l: List[A])(acc: B)(f: (B, A) => B < S): B < S =
            l match
                case Nil    => acc
                case h :: t => f(acc, h).map(foldLeftList(t)(_)(f))

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

            "multi shot" in {
                val v = Delim.shift[Int, Int, Any] { k =>
                    k(42).map(a => k(42 + 1).map(b => a + b))
                }.map(_ * 10)
                    .handle(Delim.run)
                assert(v.eval == (42 * 10) + ((42 + 1) * 10))
            }

            "multi shot with other effect" in {
                val v = Delim.shift[Int, Int, TestEffect2] { k =>
                    k(42).map(a => testEffect2("a").map(k).map(b => a + b))
                }.map(_ * 10)
                    .handle(
                        Delim.run,
                        ArrowEffect.handle(Tag[TestEffect2], _) {
                            [C] => (input, cont) => cont(input.size)
                        }
                    )
                assert(v.eval == (42 * 10) + ("a".size * 10))
            }

            "multiple shift with different effect sets" in {
                val v = Delim.shift[Int, Int, TestEffect2] { k =>
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
                    )
                assert(v.eval == 46)
            }

            "short circuiting" in {
                def test(numbers: List[Int], expected: Int) =
                    val v = foldLeftList(numbers)(0) { (acc, n) =>
                        if n < 0 || n == 42 then
                            Delim.shift[Int, Int, Any] { _ => -1 }
                        else
                            (acc + n): Int < (Delim[Int, Any] & Any)
                    }
                        .handle(Delim.run)
                    assert(v.eval == expected)
                end test

                test(List(1, 2), 3)
                test(List(1, 2, -1), -1)
                test(List(1, 2, 42, 3), -1)
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

            "single poll" in {
                def test(source: Chunk[Int], in: Chunk[Int], out: Chunk[Int], result: Maybe[Int]) =
                    val (i, o, r) = Flow.poll[Int]
                        .handle(Flow.run(source))
                        .eval
                    assert(i == in)
                    assert(o == out)
                    assert(r == result)
                end test
                test(Chunk.empty, Chunk.empty, Chunk.empty, Absent)
                test(Chunk(1), Chunk.empty, Chunk.empty, Present(1))
                test(Chunk(1, 2), Chunk(2), Chunk.empty, Present(1))
            }

            "poll and emit" in {
                def test(source: Chunk[Int], out: Chunk[Int], result: Int) =
                    val (i, o, r) = Loop(0) { acc =>
                        Flow.poll[Int].map {
                            case Absent     => Loop.done(acc)
                            case Present(v) => Flow.emit(v + 1).andThen(Loop.continue(acc + v))
                        }
                    }.handle(Flow.run(source)).eval
                    assert(i.isEmpty)
                    assert(o == out)
                    assert(r == result)
                end test
                test(Chunk.empty, Chunk.empty, 0)
                test(Chunk(1), Chunk(2), 1)
                test(Chunk(1, 2), Chunk(2, 3), 3)
            }

            "multiple flows in the same computation" in {
                def zipComp[A, B, S](a: A < S, b: B < S): (A, B) < S =
                    a.map(x => b.map(y => (x, y)))
                def test(
                    iSource: Chunk[Int],
                    sSource: Chunk[String],
                    iOut: Chunk[Int],
                    sOut: Chunk[String]
                ) =
                    val a =
                        Loop(0) { acc =>
                            zipComp(Flow.poll[Int], Flow.poll[String]).map(_.zip(_)).map {
                                case Absent => Loop.done(acc)
                                case Present((i, s)) =>
                                    Flow.emit(i + 1).andThen(Flow.emit(s + "a")).andThen(Loop.continue(acc + i + s.size))
                            }
                        }
                    val b: (Chunk[Int], Chunk[Int], Int) < Flow[String, String] =
                        Flow.run(iSource)(a)

                    val c = Flow.run(sSource)(b)
                    c.eval match
                        case (si, so, (ii, io, r)) =>
                            assert(so == sOut)
                            assert(io == iOut)
                    end match
                end test
                test(Chunk(1), Chunk.empty, Chunk.empty, Chunk.empty)
                test(Chunk.empty, Chunk("a"), Chunk.empty, Chunk.empty)
                test(Chunk(1), Chunk("a"), Chunk(2), Chunk("aa"))
                test(Chunk(1, 2), Chunk("a", "b"), Chunk(2, 3), Chunk("aa", "ba"))
            }

        }
    }

    // kernel2-specific coverage beyond the ported suite

    def echo(v: Int): Int < Echo =
        ArrowEffect.suspend[Any](Tag[Echo], v)

    def get: Int < Get =
        ArrowEffect.suspend[Any](Tag[Get], ())

    def fail(msg: String): Nothing < Fail =
        ArrowEffect.suspend[Any](using summon[kyo.Frame])[Const[String], Const[Nothing], Fail](Tag[Fail], msg)

    "handleResume answers each operation in place" in {
        val program = echo(1).map(a => echo(a + 10).map(b => a * 100 + b))
        val handled = ArrowEffect.handleResume(Tag[Echo], program)(
            [C] => (in) => in + 1
        )
        assert(handled.eval == 213)
    }

    "handleResume clause can suspend on another effect" in {
        val program = echo(5).map(_ * 2)
        val handled: Int < Get = ArrowEffect.handleResume(Tag[Echo], program.asInstanceOf[Int < (Echo & Get)])(
            [C] => (in) => get.map(_ + in)
        )
        val result = ArrowEffect.handleResume(Tag[Get], handled)(
            [C] => (_) => 100
        )
        assert(result.eval == 210)
    }

    "handleResume is deep across operations in clause results" in {
        var calls   = 0
        val program = echo(1).map(a => echo(a).map(b => a + b))
        val handled = ArrowEffect.handleResume(Tag[Echo], program)(
            [C] =>
                (in) =>
                    calls += 1
                    if calls == 1 then echo(in + 10).asInstanceOf[Int < Echo] else in
        )
        assert(handled.eval == 22)
        assert(calls == 3)
    }

    "handleStop ends the region and skips the prefix" in {
        var afterOp = false
        val program = fail("boom").map { (n: Int) =>
            afterOp = true
            n + 1
        }
        val handled = ArrowEffect.handleStop(Tag[Fail], program.asInstanceOf[Int < Fail])(
            [C] => (msg) => msg.length
        )
        assert(handled.eval == 4)
        assert(!afterOp)
    }

    "handleStop is deep when the clause result suspends again" in {
        var stops               = 0
        val program: Int < Fail = fail("first")
        val handled = ArrowEffect.handleStop(Tag[Fail], program)(
            [C] =>
                (msg) =>
                    stops += 1
                    if msg == "first" then fail("second").asInstanceOf[Int < Fail]
                    else msg.length
        )
        assert(handled.eval == 6)
        assert(stops == 2)
    }

    "handleStop leaves outer maps in place" in {
        val program: Int < Fail = fail("boom")
        val handled             = ArrowEffect.handleStop(Tag[Fail], program)([C] => (msg) => msg.length)
        assert(handled.map(_ * 10).eval == 40)
    }

    "handle aborts by not invoking the continuation" in {
        var afterOp = false
        val program = echo(1).map { n =>
            afterOp = true
            n + 1
        }
        val handled = ArrowEffect.handle(Tag[Echo], program)(
            [C] => (in, cont) => in + 100
        )
        assert(handled.eval == 101)
        assert(!afterOp)
    }

    "handle resumes multi shot" in {
        val program = echo(10).map(_ + 1)
        val handled = ArrowEffect.handle(Tag[Echo], program)(
            [C] =>
                (in, cont) =>
                    cont(in).map(a => cont(in * 2).map(b => a * 1000 + b))
        )
        assert(handled.eval == 11021)
    }

    "handle is deep through resumed continuations" in {
        var ops     = 0
        val program = echo(1).map(a => echo(a + 1).map(b => echo(b + 1).map(c => a * 100 + b * 10 + c)))
        val handled = ArrowEffect.handle(Tag[Echo], program)(
            [C] =>
                (in, cont) =>
                    ops += 1
                    cont(in)
        )
        assert(handled.eval == 123)
        assert(ops == 3)
    }

    "nested handlers of different effects dispatch to the innermost match" in {
        val program: Int < (Echo & Get) =
            echo(1).map(a => get.map(b => a * 10 + b))
        val inner = ArrowEffect.handle(Tag[Echo], program)(
            [C] => (in, cont) => cont(in + 1)
        )
        val outer = ArrowEffect.handle(Tag[Get], inner)(
            [C] => (in, cont) => cont(7)
        )
        assert(outer.eval == 27)
    }

    "nested handlers of the same effect: innermost wins" in {
        val program = echo(1).map(_ + 1)
        val inner = ArrowEffect.handle(Tag[Echo], program)(
            [C] => (in, cont) => cont(in + 10)
        )
        val outer = ArrowEffect.handle(Tag[Echo], inner.asInstanceOf[Int < Echo])(
            [C] => (in, cont) => cont(in + 100)
        )
        assert(outer.eval == 12)
    }

    "an unhandled effect parks and a later handler completes it" in {
        val program: Int < (Echo & Get) =
            echo(1).map(a => get.map(b => a + b))
        val partial: Int < Get = ArrowEffect.handleResume(Tag[Echo], program)(
            [C] => (in) => in * 10
        )
        val parked = ArrowEffect.handlePartial(Tag[Get], partial)(
            [C] => (in, cont) => kyo.Maybe.Absent
        )
        val result = ArrowEffect.handleResume(Tag[Get], parked)(
            [C] => (_) => 5
        )
        assert(result.eval == 15)
    }

    "handleFirst handles only the first operation" in {
        val program = echo(1).map(a => echo(a + 1).map(b => a + b))
        val once: Int < Echo = ArrowEffect.handleFirst(Tag[Echo], program)(
            [C] => (in, cont) => cont(in * 10).asInstanceOf[Int < Echo],
            a => a
        )
        val rest = ArrowEffect.handleResume(Tag[Echo], once)(
            [C] => (in) => in + 100
        )
        assert(rest.eval == 121)
    }

    "handleFirst runs done when no operation occurs" in {
        var doneRan             = false
        val program: Int < Echo = 5.asInstanceOf[Int < Echo]
        val handled = ArrowEffect.handleFirst(Tag[Echo], program)(
            [C] => (in, cont) => -1,
            a =>
                doneRan = true
                a + 1
        )
        assert(handled.eval == 6)
        assert(doneRan)
    }

    "handleFirst done runs when the region completes without an operation after install" in {
        val program: Int < Echo = echo(3)
        val handled = ArrowEffect.handleFirst(Tag[Echo], program)(
            [C] => (in, cont) => cont(in).asInstanceOf[Int < Echo].map(_ + 1000),
            a => a
        )
        assert(handled.asInstanceOf[Int < Any].eval == 1003)
    }

    "a stop shaped effect cannot be resumed by construction" in {
        val program: Int < Fail = fail("nope")
        val handled = ArrowEffect.handle(Tag[Fail], program)(
            [C] => (msg, cont) => msg.length
        )
        assert(handled.eval == 4)
    }

    "handlers travel with parked computations" in {
        val program: Int < (Echo & Get) =
            get.map(a => echo(a + 1).map(b => a + b))
        val handled: Int < Get = ArrowEffect.handleResume(Tag[Echo], program)(
            [C] => (in) => in * 10
        )
        val parked = ArrowEffect.handlePartial(Tag[Get], handled)(
            [C] => (in, cont) => kyo.Maybe.Absent
        )
        val resumed = ArrowEffect.handleResume(Tag[Get], parked)(
            [C] => (_) => 2
        )
        assert(resumed.eval == 32)
    }

    "a fully handled region evaluates at the handle site" in {
        var runs = 0
        val handled = ArrowEffect.handleResume(Tag[Echo], echo(1).map(_ + 1))(
            [C] =>
                (in) =>
                    runs += 1
                in
        )
        assert(runs == 1)
        assert(handled.eval == 2)
        assert(handled.eval == 2)
        assert(runs == 1)
    }

    "defer inside a handled region executes at the handle site" in {
        var log = List.empty[String]
        val program = Effect.defer {
            log :+= "defer"
            echo(1)
        }.map(_ + 1)
        val handled = ArrowEffect.handleResume(Tag[Echo], program.asInstanceOf[Int < Echo])(
            [C] => (in) => in + 10
        )
        assert(log == List("defer"))
        assert(handled.eval == 12)
        assert(log == List("defer"))
    }

    "handleLoop threads state and applies done with the final state" in {
        val program = echo(1).map(a => echo(2).map(b => echo(3).map(c => a + b + c)))
        val handled: (Int, Int) < Any = ArrowEffect.handleLoop(Tag[Echo], 0, program)(
            [C] => (in, state, cont) => Loop.continue(state + in, cont(in)),
            (state, a) => (state, a)
        )
        assert(handled.eval == (6, 6))
    }

    "handleLoop ends the region early with Done" in {
        var afterOp = false
        val program = echo(1).map { a =>
            echo(100).map { b =>
                afterOp = true
                a + b
            }
        }
        val handled = ArrowEffect.handleLoop(Tag[Echo], 0, program)(
            [C] =>
                (in, state, cont) =>
                    if in >= 100 then Loop.done(-1)
                    else Loop.continue(state + in, cont(in)),
            (state, a) => a
        )
        assert(handled.eval == -1)
        assert(!afterOp)
    }

    "handleLoop forwards effects raised while computing the outcome" in {
        val program: Int < (Echo & Get) = echo(1).map(_ + 1)
        val handled: Int < Get = ArrowEffect.handleLoop(Tag[Echo], 0, program.asInstanceOf[Int < (Echo & Get)])(
            [C] =>
                (in, state, cont) =>
                    get.map(g => Loop.continue(state + g, cont(in + g))),
            (state, a) => state * 1000 + a
        )
        val result = ArrowEffect.handleResume(Tag[Get], handled)(
            [C] => (_) => 7
        )
        assert(result.eval == 7009)
    }

    "handleLoop state survives a park on a foreign effect" in {
        val program: Int < (Echo & Get) =
            echo(1).map(a => get.map(b => echo(2).map(c => a + b + c)))
        val handled: Int < Get = ArrowEffect.handleLoop(Tag[Echo], 0, program)(
            [C] => (in, state, cont) => Loop.continue(state + in, cont(in)),
            (state, a) => state * 1000 + a
        )
        val parked = ArrowEffect.handlePartial(Tag[Get], handled)(
            [C] => (in, cont) => kyo.Maybe.Absent
        )
        val result = ArrowEffect.handleResume(Tag[Get], parked)(
            [C] => (_) => 10
        )
        assert(result.eval == 3013)
    }

    "handlePartial handles operations deeply at the drive boundary" in {
        import kyo.Maybe
        val program = echo(1).map(a => echo(a + 1).map(b => a + b))
        val result = ArrowEffect.handlePartial(Tag[Echo], program)(
            [C] => (in, cont) => Maybe(cont(in * 10))
        )
        assert(result.asInstanceOf[Int < Any].eval == 120)
    }

    "handlePartial parks on Absent and the captured continuation resumes" in {
        import kyo.Maybe
        val program       = echo(1).map(a => echo(a + 1).map(b => a + b))
        var captured: Any = null
        var slices        = 0
        def slice(v: Int < Echo): Int < Echo =
            ArrowEffect.handlePartial(Tag[Echo], v)(
                [C] =>
                    (in, cont) =>
                        slices += 1
                        captured = cont
                        Maybe.Absent
            )
        val parked1 = slice(program)
        val k1      = captured.asInstanceOf[Arrow[Int, Int, Echo]]
        val parked2 = slice(k1(10))
        val k2      = captured.asInstanceOf[Arrow[Int, Int, Echo]]
        val done    = k2(100)
        assert(done.asInstanceOf[Int < Any].eval == 110)
        assert(slices == 2)
    }

    "installed delimiters win over the handlePartial clause" in {
        import kyo.Maybe
        var lastResort = 0
        val program    = echo(1).map(a => echo(a + 1).map(b => a + b))
        val handled    = ArrowEffect.handleResume(Tag[Echo], program)([C] => (in) => in * 10)
        val result = ArrowEffect.handlePartial(Tag[Echo], handled.asInstanceOf[Int < Echo])(
            [C] =>
                (in, cont) =>
                    lastResort += 1
                    Maybe(cont(in))
        )
        assert(result.asInstanceOf[Int < Any].eval == 120)
        assert(lastResort == 0)
    }

    "handlePartial resumption re-installs traveling delimiters" in {
        import kyo.Maybe
        val program: Int < (Echo & Get) =
            get.map(a => echo(a + 1).map(b => get.map(c => a + b + c)))
        val bound: Int < Echo = ArrowEffect.handleResume(Tag[Get], program.asInstanceOf[Int < (Get & Echo)])(
            [C] => (_) => 5
        ).asInstanceOf[Int < Echo]
        var captured: Any = null
        val parked = ArrowEffect.handlePartial(Tag[Echo], bound)(
            [C] =>
                (in, cont) =>
                    captured = cont
                    Maybe.Absent
        )
        val k = captured.asInstanceOf[Arrow[Int, Int, Echo]]
        assert(k(100).asInstanceOf[Int < Any].eval == 110)
    }

    "handlePartial polls preemption across dispatches" in {
        import kyo.Maybe
        def program(i: Int): Int < Echo =
            if i == 0 then 0
            else echo(i).map(_ => program(i - 1))
        var polls = 0
        val r = ArrowEffect.handlePartial(
            Tag[Echo],
            program(10000),
            () =>
                polls += 1; polls == 2
            ,
            512
        )(
            [C] => (in, cont) => Maybe(cont(in))
        )
        assert(polls == 2)
        val rest = ArrowEffect.handlePartial(Tag[Echo], r)(
            [C] => (in, cont) => Maybe(cont(in))
        )
        assert(rest.asInstanceOf[Int < Any].eval == 0)
    }

end ArrowEffectTest
