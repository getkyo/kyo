package kyo.kernel

import kyo.*
import kyo.kernel.*
import kyo.kernel.internal.*
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.compiletime.testing.typeCheckErrors

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

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait AskSub extends Ask

    def askSub: Int < Ask = ArrowEffect.suspend[Any](Tag[AskSub].asInstanceOf[Tag[Ask]], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    private val Period = Safepoint.period()

    sealed trait Wrap extends ArrowEffect[Const[Unit], Const[Unit]]

    def recovering[A, S](v: A < (Wrap & S))(f: Throwable => A): A < S =
        ArrowEffect.handleCont(Tag[Wrap], v)([C] => (_, cont) => cont(()), a => a, ex => Maybe(f(ex)))

    def burn(n: Int): Int < Any =
        if n == 0 then 0 else (0: Int < Any).map(_ => burn(n - 1))

    "suspend" in {
        val effect: String < TestEffect1 = testEffect1(42)
        discard(effect)
        // ArrowEffect.suspend produces a String < TestEffect1; the type ascription above is the verification
        succeed
    }

    "handle" - {
        "single effect" in {
            val effect = testEffect1(42)
            val result = ArrowEffect.handleCont(Tag[TestEffect1], effect)(
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

            val result = ArrowEffect.handleCont(
                Tag[TestEffect2],
                ArrowEffect.handleCont(Tag[TestEffect1], effect)([C] => (input, cont) => cont(input.toString))
            )([C] => (input, cont) => cont(input.toInt))

            assert(result.eval == 42)
        }

        "three effects" in {
            val effect =
                for
                    s <- testEffect1(42)
                    i <- testEffect2(s)
                    d <- testEffect3(i % 2 == 0)
                yield d

            val result = ArrowEffect.handleCont(
                Tag[TestEffect3],
                ArrowEffect.handleCont(
                    Tag[TestEffect2],
                    ArrowEffect.handleCont(Tag[TestEffect1], effect)([C] => (input, cont) => cont(input.toString))
                )([C] => (input, cont) => cont(input.toInt))
            )([C] => (input, cont) => cont(if input then 1.0 else 0.0))

            assert(result.eval == 1.0)
        }

        "with state" in {
            val effect =
                for
                    s1 <- testEffect1(42)
                    s2 <- testEffect1(43)
                yield (s1, s2)

            val result = ArrowEffect.handleLoopState(Tag[TestEffect1], 0, effect)(
                [C] => (state, input) => Loop.continue(state + 1, (input + state).toString)
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

            val result = ArrowEffect.handleCont(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString)
            )

            assert(result.eval == 42)
            // Diverges from main: the evaluator's frames between the test body and the loop body add a
            // constant 12 here, main's inline handling added at most 10.
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

            val result: (String, String, String) < TestEffect1 = ArrowEffect.handleFirst(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont("handled"),
                identity
            )

            val finalResult = ArrowEffect.handleCont(Tag[TestEffect1], result) {
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

            val result: (String, Int, String) < (TestEffect1 & TestEffect2) =
                ArrowEffect.handleFirst(Tag[TestEffect1], effect)(
                    [C] => (input, cont) => cont("handled"),
                    identity
                )

            val finalResult = ArrowEffect.handleCont(
                Tag[TestEffect2],
                ArrowEffect.handleCont(Tag[TestEffect1], result)([C] => (input, cont) => cont(input.toString))
            )([C] => (input, cont) => cont(input.length))

            assert(finalResult.eval == ("handled", 4, "20"))
        }

        "handles pure values correctly" in {
            val effect: String < Any = "pure"
            val result: String < TestEffect1 = ArrowEffect.handleFirst(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont("handled"),
                s => s + "-done"
            )

            val finalResult = ArrowEffect.handleCont(Tag[TestEffect1], result) {
                [C] => (input, cont) => cont(input.toString)
            }

            assert(finalResult.eval == "pure-done")
        }

        "stack safety with nested effects" in {
            def nested(n: Int): Int < TestEffect1 =
                if n == 0 then 42
                else testEffect1(n).map(_ => nested(n - 1))

            val effect = nested(10000)
            val result: Int < TestEffect1 = ArrowEffect.handleFirst(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont("42"),
                identity
            )

            val finalResult = ArrowEffect.handleCont(Tag[TestEffect1], result) {
                [C] => (input, cont) => cont(input.toString)
            }

            assert(finalResult.eval == 42)
        }

        "answers the first operation and hands the raw remainder" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleFirst(Tag[Ask], v)(
                [C] => (_, cont) => ArrowEffect.handleCont(Tag[Ask], cont(4))([C2] => (_, k) => k(2), a => a),
                a => a
            )
            assert(r.eval == 42)
        }

        "a body that completes without the effect takes done" in {
            val r: Int < Any = ArrowEffect.handleFirst(Tag[Ask], 5: Int < Ask)([C] => (_, _) => -1, a => a * 2)
            assert(r.eval == 10)
        }

        "the clause may end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a
            }
            val r: Int < Any = ArrowEffect.handleFirst(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(r.eval == -1)
            assert(!reached)
        }

        "re-handling the remainder round by round sees every operation" in {
            def collect(v: Int < Ask, acc: List[Int]): List[Int] < Any =
                ArrowEffect.handleFirst(Tag[Ask], v)(
                    [C] => (_, cont) => collect(cont(acc.size + 1), acc :+ (acc.size + 1)),
                    a => acc :+ a
                )
            val v = ask.map(a => ask.map(b => a * 10 + b))
            assert(collect(v, Nil).eval == List(1, 2, 12))
        }
    }

    "handle.catching" - {
        "failure" in {
            val effect = ArrowEffect.suspend[Int](Tag[TestEffect1], 42)
            val result = ArrowEffect.handleCont(Tag[TestEffect1], effect)(
                [C] => (input, cont) => throw new RuntimeException("Test exception"),
                a => a,
                recover = {
                    case _: RuntimeException => Maybe("recovered")
                }
            )
            assert(result.eval == "recovered")
        }

        "success" in {
            val effect = ArrowEffect.suspend[Int](Tag[TestEffect1], 42)
            val result = ArrowEffect.handleCont(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString),
                a => a,
                recover = {
                    case _: RuntimeException => Maybe("recovered")
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

        val result = ArrowEffect.handleCont(
            Tag[TestEffect2],
            ArrowEffect.handleCont(Tag[TestEffect1], nested(1000))([C] => (input, cont) => cont(input.toString))
        )([C] => (input, cont) => cont(input.toInt))

        assert(result.eval == 500500)
    }

    "non-Const inputs/outputs" - {
        sealed trait CustomEffect extends ArrowEffect[List, Option]

        def customEffect(input: List[Int]): Option[Int] < CustomEffect =
            ArrowEffect.suspend[Int](Tag[CustomEffect], input)

        "suspend and handle" in {
            val effect = customEffect(List(1, 2, 3))
            val result = ArrowEffect.handleCont(Tag[CustomEffect], effect)(
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

            val result = ArrowEffect.handleCont(Tag[CustomEffect], effect)(
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

            val result = ArrowEffect.handleLoopState(Tag[CustomEffect], 0, effect)(
                [C] => (state, input) => Loop.continue(state + 1, (Some(input(state)): Option[C]))
            )
            assert(result.eval == (Some(1), Some(5)))
        }
    }

    // Diverges from main: handlePartial is gone, the partial evaluation entry point here is
    // Eval.partial over a computation whose effects are already handled, and the stop condition is
    // the safepoint's rather than a flag handed to the handler.
    "handlePartial" - {
        "evaluates pure values" in {
            val x: Int < Any = 5
            val result       = Eval.partial(x)
            assert(result.evalNow == Maybe(5))
        }

        "suspends at effects" in {
            val x: Int < TestEffect1 = testEffect1(5).map(_ => 6)
            val result = Eval.partial(
                ArrowEffect.handleCont(Tag[TestEffect1], x)(
                    [C] => (input, cont) => cont(input.toString)
                )
            )
            assert(result.evalNow == Maybe(6))
        }

        "respects the stop condition" in {
            var called       = false
            val x: Int < Any = Effect.defer(5)
            requestStop()
            val result = Eval.partial(
                ArrowEffect.handleCont(Tag[TestEffect1], x)(
                    [C] =>
                        (input, cont) =>
                            called = true
                            cont(input.toString)
                )
            )
            assert(!called)
            assert(result.evalNow.isEmpty)
        }

        "evaluates nested suspensions" in {
            val x: Int < Any = Effect.defer(Effect.defer(5))
            val result       = Eval.partial(x)
            assert(result.evalNow == Maybe(5))
        }
    }

    "nested effects handling" - {

        given [A, B]: CanEqual[A, B] = CanEqual.derived

        sealed trait NestedTestEffect extends ArrowEffect[Const[Int], Const[Int]]

        def suspendNested(i: Int): Int < NestedTestEffect =
            ArrowEffect.suspend[Any](Tag[NestedTestEffect], i)

        val nestedTag: Tag[NestedTestEffect] = Tag[NestedTestEffect]

        def flatten[A, B, C](v: A < B < C): A < (B & C) = v.map(a => a)

        // Diverges from main: Nested is a plain box here rather than a suspension node, so a
        // handler's settled path unnests it and the done arm re-boxes. The wrapper each case reads
        // back is a fresh instance, so these assert on the computation it carries.

        "not handle Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handleCont(nestedTag, v)([C] => (input, cont) => cont(input * 10), a => Kyo.lift(a))

            "unwraps Nested and returns inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)
                val result: Int < NestedTestEffect < Any = handle(nested)

                val crossed = Nested.unnest[Any](result).asInstanceOf[AnyRef]
                assert(crossed eq comp.asInstanceOf[AnyRef], "handleSimple should return the nested computation")

                val flattened   = flatten(result)
                val finalResult = handle(flattened)

                assert(finalResult.eval == 50)
            }
        }

        "handleFirst on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < (S & NestedTestEffect) =
                ArrowEffect.handleFirst(nestedTag, v)(
                    [C] => (input, cont) => cont(input * 10),
                    a => Kyo.lift(a)
                )

            "done callback receives unwrapped value" in {
                val comp                                 = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result = handle(nested)

                val crossed = Nested.unnest[Any](result).asInstanceOf[AnyRef]
                assert(crossed eq comp.asInstanceOf[AnyRef], "handleFirst should return the nested computation")

                val flattened                           = flatten(result)
                val finalResult: Int < NestedTestEffect = handle(flattened)
                // Diverges from main: the region is a node here, so its answer is observable at eval
                // rather than at evalNow, and the row it leaves standing is closed to read it.
                assert(ArrowEffect.handleCont(nestedTag, finalResult)([C] => (input, cont) => cont(input)).eval == 50)
            }
        }

        "handleLoop (stateless) on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handleLoop(Tag[NestedTestEffect], v)(
                    [C] => input => Loop.continue((input * 10)),
                    a => Kyo.lift(a)
                )

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result  = handle(nested)
                val crossed = Nested.unnest[Any](result).asInstanceOf[AnyRef]
                assert(crossed eq comp.asInstanceOf[AnyRef], "handleLoop should return the nested computation")

                val flattened              = flatten(result)
                val finalResult: Int < Any = handle(flattened)

                assert(finalResult.eval == 50)
            }
        }

        "handleLoop (stateful) on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handleLoopState(nestedTag, 0, v)(
                    [C] => (state, input) => Loop.continue(state + 1, ((input + state) * 10)),
                    (_, a) => Kyo.lift(a)
                )

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result  = handle(nested)
                val crossed = Nested.unnest[Any](result).asInstanceOf[AnyRef]
                assert(crossed eq comp.asInstanceOf[AnyRef], "handleLoop should return the nested computation")

                val flattened              = flatten(result)
                val finalResult: Int < Any = handle(flattened)

                assert(finalResult.eval == 50)
            }

        }

        "handleLoop (stateful + done) on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handleLoopState(nestedTag, 0, v)(
                    [C] => (state, input) => Loop.continue(state + 1, (input * 10)),
                    (state, v) => Kyo.lift(v)
                )

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result  = handle(nested)
                val crossed = Nested.unnest[Any](result).asInstanceOf[AnyRef]
                assert(crossed eq comp.asInstanceOf[AnyRef], "handleLoop should return the nested computation")

                val flattened              = flatten(result)
                val finalResult: Int < Any = handle(flattened)

                assert(finalResult.eval == 50)
            }
        }

        "handleCatching on Nested" - {

            def handle[A, S](v: A < (S & NestedTestEffect)): A < S =
                ArrowEffect.handleCont(nestedTag, v)(
                    [C] => (input, cont) => cont(input * 10),
                    a => Kyo.lift(a),
                    recover = e => throw e
                )

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result  = handle(nested)
                val crossed = Nested.unnest[Any](result).asInstanceOf[AnyRef]
                assert(crossed eq comp.asInstanceOf[AnyRef], "handleLoop should return the nested computation")

                val flattened              = flatten(result)
                val finalResult: Int < Any = handle(flattened)

                assert(finalResult.eval == 50)
            }
        }

        "handlePartial on Nested" - {

            // Diverges from main: Eval.partial takes a computation with no effects left in the row,
            // so the region is closed before the partial evaluation instead of being handed two
            // handlers and a stop flag.
            def handle[A](v: A < NestedTestEffect): A < Any =
                Eval.partial(
                    ArrowEffect.handleCont(nestedTag, v)([C] => (input, cont) => cont(input * 10), a => Kyo.lift(a))
                )

            "unwraps Nested and handles inner suspension" in {
                val comp: Int < NestedTestEffect         = suspendNested(5)
                val nested: Int < NestedTestEffect < Any = Kyo.lift(comp)

                val result  = handle(nested)
                val crossed = Nested.unnest[Any](result).asInstanceOf[AnyRef]
                assert(crossed eq comp.asInstanceOf[AnyRef], "handlePartial should return the nested computation")

                val flattened   = flatten(result)
                val finalResult = handle(flattened)
                assert(finalResult.evalNow == Maybe(50))
            }
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
                    ArrowEffect.handleCont(tag, v)(
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
                val v =
                    Delim.shift[Int, Int, Any] { k =>
                        k(42).map(a => k(42 + 1).map(b => a + b))
                    }.map(_ * 10)
                        .handle(Delim.run)
                assert(v.eval == (42 * 10) + ((42 + 1) * 10))
            }

            "multi shot with other effect" in {
                val v =
                    Delim.shift[Int, Int, TestEffect2] { k =>
                        k(42).map(a => testEffect2("a").map(k).map(b => a + b))
                    }.map(_ * 10)
                        .handle(
                            Delim.run,
                            v => ArrowEffect.handleCont(Tag[TestEffect2], v)([C] => (input, cont) => cont(input.size))
                        )
                assert(v.eval == (42 * 10) + ("a".size * 10))
            }

            "multiple shift with different effect sets" in {
                val v =
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
                            v => ArrowEffect.handleCont(Tag[TestEffect1], v)([C] => (input, cont) => cont(input.toString)),
                            v => ArrowEffect.handleCont(Tag[TestEffect2], v)([C] => (input, cont) => cont(input.size))
                        )
                assert(v.eval == 46)
            }

            "short circuiting" in {
                def test(numbers: List[Int], expected: Int) =
                    val r =
                        Kyo.foldLeft(numbers)(0) { (acc, n) =>
                            if n < 0 || n == 42 then
                                Delim.shift[Int, Int, Any] { _ => -1 }
                            else
                                acc + n
                        }.handle(Delim.run)
                    assert(r.eval == expected)
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
                    ArrowEffect.handleLoopState(Tag[Flow[In, Out]], (in, Chunk.empty[Out]), v)(
                        [C] =>
                            (state, input) =>
                                val (in, out) = state
                                (input: @unchecked) match
                                    case Op.Emit(v) =>
                                        Loop.continue((in, out.append(v)), (()).asInstanceOf[C < Any])
                                    case Op.Poll() =>
                                        Loop.continue((in.tail, out), (in.headMaybe).asInstanceOf[C < Any])
                                end match
                        ,
                        (state, r) => (state._1, state._2, r)
                    )

            end Flow

            "single poll" in {
                def test(source: Chunk[Int], in: Chunk[Int], out: Chunk[Int], result: Maybe[Int]) =
                    val (i, o, r) = Flow.run(source)(Flow.poll[Int]).eval
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
                    val v =
                        Loop(0) { acc =>
                            Flow.poll[Int].map {
                                case Absent     => Loop.done(acc)
                                case Present(v) => Flow.emit(v + 1).andThen(Loop.continue(acc + v))
                            }
                        }
                    val (i, o, r) = Flow.run(source)(v).eval
                    assert(i.isEmpty)
                    assert(o == out)
                    assert(r == result)
                end test
                test(Chunk.empty, Chunk.empty, 0)
                test(Chunk(1), Chunk(2), 1)
                test(Chunk(1, 2), Chunk(2, 3), 3)
            }

            "multiple flows in the same computation" in {
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
                    val b: (Chunk[Int], Chunk[Int], Int) < Flow[String, String] = Flow.run(iSource)(a)
                    val (_, so, (_, io, _))                                     = Flow.run(sSource)(b).eval
                    assert(so == sOut)
                    assert(io == iOut)
                end test
                test(Chunk(1), Chunk.empty, Chunk.empty, Chunk.empty)
                test(Chunk.empty, Chunk("a"), Chunk.empty, Chunk.empty)
                test(Chunk(1), Chunk("a"), Chunk(2), Chunk("aa"))
                test(Chunk(1, 2), Chunk("a", "b"), Chunk(2, 3), Chunk("aa", "ba"))
            }
        }
    }

    "handleLoop" - {
        "answers every operation in place" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Loop.continue(1), a => a)
            assert(r.eval == 3)
        }

        "Loop.done stops the region" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a)
            assert(r.eval == -1)
            assert(!reached)
        }

        "a done payload that is itself a Continue2 still stops the region" in {
            type Out = Loop.Outcome2[Unit, Int < Any, Int < Any]
            val hostile: Out = Loop.continue((), 7: Int < Any).eval
            var reached      = false
            val body: Out < Ask = ask.map { _ =>
                reached = true
                hostile
            }
            val r: Out < Any = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.done(hostile), a => a)
            val out          = r.eval
            assert(!reached)
            assert(out.asInstanceOf[AnyRef] eq hostile.asInstanceOf[AnyRef])
        }

        "a done payload of type Any holding a Continue2 still stops the region" in {
            val hostile: Any = Loop.continue((), 0: Int < Any).eval: Loop.Outcome2[Unit, Int < Any, Any]
            var reached      = false
            val body: Any < Ask = ask.map { _ =>
                reached = true
                "resumed"
            }
            val r: Any < Any = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.done(hostile), a => a)
            val out          = r.eval
            assert(!reached)
            assert(out.asInstanceOf[AnyRef] eq hostile.asInstanceOf[AnyRef])
        }

        "a done payload that is a pending computation is delivered as data" in {
            val payload: Int < Say = say("p").map(_ => 7)
            var reached            = false
            val body: (Int < Say) < Ask = ask.map { _ =>
                reached = true
                Kyo.lift(payload)
            }
            val r: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.done(Kyo.lift(payload)), a => Kyo.lift(a))
            val boxed = r.eval
            assert(!reached)
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], boxed)([C] => (_, cont) => cont(()), a => a)
            assert(out.eval == 7)
        }

        "an effectful clause ending with a pending payload delivers it as data" in {
            sealed trait Tick extends ArrowEffect[Const[Unit], Const[Unit]]
            val payload: Int < Say = say("p").map(_ => 7)
            var reached            = false
            val body: (Int < Say) < Ask = ask.map { _ =>
                reached = true
                Kyo.lift(payload)
            }
            val handled: (Int < Say) < Tick =
                ArrowEffect.handleLoop(Tag[Ask], body)(
                    [C] => _ => ArrowEffect.suspend[Any](Tag[Tick], ()).map(_ => Loop.done(Kyo.lift(payload))),
                    a => Kyo.lift(a)
                )
            val r: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[Tick], handled)([C] => _ => Loop.continue(()), a => Kyo.lift(a))
            val boxed = r.eval
            assert(!reached)
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], boxed)([C] => (_, cont) => cont(()), a => a)
            assert(out.eval == 7)
        }

        "an unboxed computation payload runs as the region's result" in {
            var evaluated          = 0
            val payload: Int < Any = Effect.defer { evaluated += 1; 2 }
            val body: Any < Ask    = ask.map(_ => "x")
            val r: Any < Any       = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.done(payload), a => a)
            val out                = r.eval
            assert(evaluated == 1)
            assert(out.asInstanceOf[Int] == 2)
        }

        "a crossing clause ending with a computation result runs it" in {
            var evaluated          = 0
            val payload: Int < Any = Effect.defer { evaluated += 1; 2 }
            val body: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], say("s").map(_ => ask))([C] => (_, cont) => cont(()), a => a)
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.done(payload), a => a)
            assert(r.eval == 2)
            assert(evaluated == 1)
        }

        "a crossing clause ending with a boxed payload keeps it as data" in {
            var evaluated          = 0
            val payload: Int < Any = Effect.defer { evaluated += 1; 2 }
            val body: (Int < Any) < Ask =
                ArrowEffect.handleCont(Tag[Say], say("s").map(_ => ask.map(_ => Kyo.lift(payload))))(
                    [C] => (_, cont) => cont(()),
                    a => Kyo.lift(a)
                )
            val r: (Int < Any) < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.done(Kyo.lift(payload)), a => Kyo.lift(a))
            val data = r.eval
            assert(evaluated == 0)
            assert(data.eval == 2)
            assert(evaluated == 1)
        }

        "done sees the settled result" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(41), a => a * 10)
            assert(r.eval == 420)
        }

        "a settled input applies done strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], 41: Int < Ask)(
                [C] => _ => Loop.continue(0),
                a =>
                    ran = true
                    a + 1
            )
            assert(ran)
            assert(r.eval == 42)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(100000))([C] => _ => Loop.continue(1), a => a)
            assert(r.eval == 0)
        }

        "the innermost region of a tag answers" in {
            val inner: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask)([C] => _ => Loop.continue(1), a => a)
            val outer: Int < Any = ArrowEffect.handleLoop(Tag[Ask], inner: Int < Ask)([C] => _ => Loop.continue(2), a => a)
            assert(outer.eval == 1)
        }

        "a clause answers effectfully" in {
            var seen = List.empty[String]
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => say("consult").map(_ => Loop.continue(41)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue(())
                ,
                a => a
            )
            assert(r.eval == 42)
            assert(seen == List("consult"))
        }

        "a clause ends the region effectfully" in {
            var reached   = false
            var completed = false
            var seen      = List.empty[String]
            val body = ask.map { a =>
                reached = true
                a + 1
            }
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => say("stop").map(_ => Loop.done(-1)),
                a =>
                    completed = true
                    a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue(())
                ,
                a => a
            )
            assert(r.eval == -1)
            assert(!reached)
            assert(!completed)
            assert(seen == List("stop"))
        }

        "every clause suspension re-arms the region" in {
            var seen = List.empty[String]
            val v    = ask.map(a => ask.map(b => a * 10 + b))
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], v)(
                [C] => _ => say("x").map(_ => Loop.continue(1)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue(())
                ,
                a => a
            )
            assert(r.eval == 11)
            assert(seen == List("x", "x"))
        }

        "a clause suspension resolves outside the region" in {
            var interiorSeen = List.empty[String]
            var outerSeen    = List.empty[String]
            val body: Int < Ask =
                ArrowEffect.handleLoop(Tag[Say], ask.map(_ + 1))(
                    [C] =>
                        s =>
                            interiorSeen = s :: interiorSeen
                            Loop.continue(())
                    ,
                    a => a
                )
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => say("clause").map(_ => Loop.continue(41)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        outerSeen = s :: outerSeen
                        Loop.continue(())
                ,
                a => a
            )
            assert(r.eval == 42)
            assert(interiorSeen.isEmpty)
            assert(outerSeen == List("clause"))
        }

        "a clause answer survives its own deep evaluation" in {
            def deep(i: Int): Int < Any =
                if i == 0 then 41 else (0: Int < Any).map(_ => deep(i - 1))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => deep(10000).map(n => Loop.continue(n)),
                a => a
            )
            assert(r.eval == 42)
        }

        "a clause suspends on its own effect per operation" in {
            var answered        = 0
            val body: Int < Ask = ask.map(a => ask.map(b => a * 10 + b))
            val doubled: Int < Ask = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => ask.map(v => Loop.continue(v * 2)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], doubled)(
                [C] =>
                    _ =>
                        answered += 1
                        Loop.continue(3)
                ,
                a => a
            )
            assert(r.eval == 66)
            assert(answered == 2)
        }

        "a suspended clause dispatch is multi-shot" in {
            val body: Int < Ask = ask.map(_ + 1)
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => say("s").map(_ => Loop.continue(10)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], handled)(
                [C] => (_, cont) => cont(()).map(x => cont(()).map(y => x * 100 + y)),
                a => a
            )
            assert(r.eval == 1111)
        }

        "a foreign operation crosses the region in place" in {
            var order                   = List.empty[String]
            val body: Int < (Ask & Say) = say("a").map(_ => ask).map(i => i + 1)
            val inner: Int < Say        = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue(41), a => a)
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)(
                [C] =>
                    s =>
                        order = s :: order
                        Loop.continue(())
                ,
                a => a
            )
            assert(r.eval == 42)
            assert(order == List("a"))
        }

        "an effectful answer resolves through an outer handler" in {
            val v: Int < Ask = ask.map(_ + 1)
            val looped: Int < Say =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(say("fetch").map(_ => 41)), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], looped)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == 42)
        }

        "a clause may suspend before producing its outcome" in {
            var logged = 0
            val looped: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        say("pre").map { _ =>
                            logged += 1
                            Loop.continue(41)
                    },
                a => a
            )
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], looped)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == 42)
            assert(logged == 1)
        }

        "done climbs past an inner handler without running its remainder" in {
            var innerExit = false
            var reached   = false
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map { a =>
                reached = true
                a + 1
            }
            val sayHandled: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], program)([C] => (_, cont) => cont(()), a => a).map { v =>
                    innerExit = true
                    v
                }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayHandled)([C] => _ => Loop.done(-1), a => a)
            assert(r.eval == -1)
            assert(!reached)
            assert(!innerExit)
        }

        "handles nested per recursion step in bounded stack" in {
            def go(n: Int): Int < Any =
                if n == 0 then 0
                else ArrowEffect.handleLoop(Tag[Ask], ask.map(_ => go(n - 1)))([C] => _ => Loop.continue(1), a => a)
            assert(go(100000).eval == 0)
        }

        "context effects arise from answering handlers" in {
            def provide[A, S](value: Int)(v: A < (Ask & S)): A < S =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)
            assert(provide(42)(ask.map(_ + 1)).eval == 43)
            assert(provide(1)(provide(2)(ask)).eval == 2)
        }

        "stays in force across a foreign crossing captured by an outer handle" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
            val looped: Int < Say    = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(41), a => a)
            val r: Int < Any         = ArrowEffect.handleCont(Tag[Say], looped)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == 42)
        }

        "the innermost done wins under nested same-tag handlers" in {
            var outerReached     = false
            val inner: Int < Ask = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.done(-1), a => a)
            val outer: Int < Any = ArrowEffect.handleLoop(Tag[Ask], inner)(
                [C] =>
                    _ =>
                        outerReached = true
                        Loop.done(-2)
                ,
                a => a
            )
            assert(outer.eval == -1)
            assert(!outerReached)
        }
    }

    "handleCont" - {
        "answers with the continuation in hand" in {
            val body         = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(10), a => a)
            assert(r.eval == 20)
        }

        "the captured continuation is multi-shot" in {
            val body = ask.map(_ * 2)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)),
                a => a
            )
            assert(r.eval == 6)
        }

        "can end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(r.eval == -1)
            assert(!reached)
        }

        "a settled input applies done strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 41: Int < Ask)(
                [C] => (_, cont) => cont(0),
                a =>
                    ran = true
                    a + 1
            )
            assert(ran)
            assert(r.eval == 42)
        }

        "done applies to the settled result" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a * 10)
            assert(r.eval == 420)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)
            assert(r.eval == 0)
        }

        "answers a single operation" in {
            val v            = ask.map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            assert(r.eval == 42)
        }

        "lazy: the handled computation is a value and answers at eval" in {
            var ran = false
            val v = ask.map { a =>
                ran = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            assert(!ran)
            assert(r.eval == 42)
            assert(ran)
        }

        "a long map tower on a pending suspension handles in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            val v            = tower(ask, 1000000)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => a)
            assert(r.eval == 1000000)
        }

        "stays in force across a foreign crossing with a trailing transform" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(
                Tag[Say],
                ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            )([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == 42)
        }

        "stays in force across a budget bounce with a trailing transform" in {
            def burn(n: Int): Int < Any =
                if n == 0 then
                    val v: Int < Ask = (0: Int < Any).map(_ => ask).map(_ + 1)
                    ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
                else ((): Unit < Any).map(_ => burn(n - 1))
            assert(burn(Period).eval == 42)
        }

        "handler state threads through resumptions" in {
            var count = 0
            val v     = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)(
                [C] =>
                    (_, cont) =>
                        count += 1
                        cont(count * 10)
                ,
                a => a
            )
            assert(r.eval == 30)
            assert(count == 2)
        }

        "a foreign operation passes through and keeps the handler attached" in {
            val v: Int < (Ask & Say)  = ask.map(a => say(a.toString).map(_ => ask.map(b => a + b)))
            val handledAsk: Int < Say = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(21), a => a)
            val r: Int < Any          = ArrowEffect.handleCont(Tag[Say], handledAsk)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == 42)
        }

        "nested handlers answer their own operations" in {
            val v: Int < (Ask & Say) =
                ask.map { a =>
                    say(a.toString).map(_ => a * 2)
                }
            val r: Int < Any = ArrowEffect.handleCont(
                Tag[Say],
                ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(21), a => a)
            )([C] => (s, cont) => cont(()), a => a)
            assert(r.eval == 42)
        }

        "a supertype handler leaves a subtype effect in the row" in {
            val v: Int < AskSub = ArrowEffect.suspend[Any](Tag[AskSub], ()).map(_ + 1)
            val r               = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            assertTypeError("val fullyHandled: Int < Any = r")
            val stillOwed: Int < AskSub = r
            assert(ArrowEffect.handleCont(Tag[AskSub], stillOwed)([C] => (_, cont) => cont(41), a => a).eval == 42)
        }

        "a handler at a subtype effect answers a computation typed at the supertype" in {
            val v: Int < Ask = askSub.map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(Tag[AskSub], v)([C] => (_, cont) => cont(41), a => a)
            assert(r.eval == 42)
        }

        "the operation clause receives the operation reified at its own tag" in {
            var seen         = List.empty[String]
            val v: Int < Ask = ask.map(_ + 1)

            val r: Int < Any = ArrowEffect.handleMasking(Tag[AskSub], v)(
                [X] =>
                    (operation, _) =>
                        seen = operation.toString :: seen
                        -1
                ,
                a => a
            )
            assert(r.eval == -1)
            assert(seen.size == 1)
            assert(seen.head.startsWith(s"Kyo(${Tag[Ask].show}, "))
        }

        "a map chained after the region applies to the result" in {
            val v            = ask.map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a).map(_ * 10)
            assert(r.eval == 420)
        }

        "a map chained after the region runs outside the scope" in {
            val v                = ask.map(_ + 1)
            val r: Int < Ask     = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a).map(a => ask.map(_ + a))
            val outer: Int < Any = ArrowEffect.handleCont(Tag[Ask], r)([C] => (_, cont) => cont(1000), a => a)
            assert(outer.eval == 1042)
        }

        "a map chained after a settled pass-through applies strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 42: Int < Ask)([C] => (_, cont) => cont(0), a => a).map { a =>
                ran = true
                a + 1
            }
            assert(ran)
            assert(r.eval == 43)
        }
    }

    "handleLoopState" - {
        "threads state through operations" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (_, a) => a
            )
            assert(r.eval == 12)
        }

        "done observes the final state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: (Int, Int) < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (s, a) => (s, a)
            )
            assert(r.eval == (12, 21))
        }

        "Loop.done bypasses done" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: String < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (s, _) => if s == 1 then Loop.done("stopped") else Loop.continue(s + 1, 1),
                (s, a) => s"done $a"
            )
            assert(r.eval == "stopped")
        }

        "a stateful clause answers effectfully" in {
            var seen = List.empty[String]
            val v    = ask.map(a => ask.map(b => a * 10 + b))
            val handled: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => say(s"state $s").map(_ => Loop.continue(s + 1, s)),
                (_, a) => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue(())
                ,
                a => a
            )
            assert(r.eval == 12)
            assert(seen == List("state 2", "state 1"))
        }

        "state survives a foreign crossing" in {
            val body: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            val inner: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, body)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (_, a) => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)([C] => _ => Loop.continue(()), a => a)
            assert(r.eval == 12)
        }

        "a settled input applies done strictly with the initial state" in {
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 7, 35: Int < Ask)(
                [C] => (s, _) => Loop.continue(s, 0),
                (s, a) => s + a
            )
            assert(r.eval == 42)
        }

        "state composes with done" in {
            def go(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => go(n - 1))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 3, go(5))(
                [C] => (remaining, _) => if remaining > 0 then Loop.continue(remaining - 1, 1) else Loop.done(-1),
                (_, a) => a
            )
            assert(r.eval == -1)
        }

        "state survives an inner handler's exit" in {
            val program: Int < (Ask & Say) = ask.map(a => say("x").map(_ => a))
            val sayHandled: Int < Ask      = ArrowEffect.handleCont(Tag[Say], program)([C] => (_, cont) => cont(()), a => a)
            val v: Int < Ask               = sayHandled.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (state, _) => Loop.continue(state + 1, state),
                (_, a) => a
            )
            assert(r.eval == 12)
        }

        "done sees the final answer" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (_, a) => a * 2
            )
            assert(r.eval == 42)
        }

        "done may be effectful" in {
            val r: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 5, ask)(
                [C] => (s, _) => Loop.continue(s, s),
                (s, a) => say("bye").map(_ => s + a)
            )
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], r)([C] => (_, cont) => cont(()), a => a)
            assert(out.eval == 10)
        }

    }

    "handleWith" - {
        "applies the continuation to the region result" in {
            val r: Int < Any = ArrowEffect.handleContWith(Tag[Ask], ask.map(_ + 1))(
                [C] => (_, cont) => cont(20),
                a => a * 2
            )(b => b + 100)
            assert(r.eval == 142)
        }

        "applies the continuation to a settled input" in {
            val r: Int < Any = ArrowEffect.handleContWith(Tag[Ask], 5: Int < Ask)(
                [C] => (_, cont) => cont(0),
                a => a + 1
            )(b => b * 10)
            assert(r.eval == 60)
        }

        "the continuation can suspend on an outer effect" in {
            var seen = ""
            val inner: Int < Say =
                ArrowEffect.handleContWith(Tag[Ask], ask)([C] => (_, cont) => cont(1), a => a)(b => say("s").map(_ => b + 1))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)(
                [C] =>
                    s =>
                        seen = s
                        Loop.continue(())
                ,
                a => a
            )
            assert(r.eval == 2)
            assert(seen == "s")
        }

        "deep sequential operations stay stack safe through the continuation" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleContWith(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)(b => b + 7)
            assert(r.eval == 7)
        }
    }

    "handleLoopWith" - {
        "applies the continuation to the region result" in {
            val r: Int < Any = ArrowEffect.handleLoopWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], ask.map(_ + 1))(
                [C] => _ => Loop.continue(41),
                a => a * 10
            )(b => b + 1)
            assert(r.eval == 421)
        }

        "applies the continuation to a settled input" in {
            val r: Int < Any = ArrowEffect.handleLoopWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], 41: Int < Ask)(
                [C] => _ => Loop.continue(0),
                a => a + 1
            )(b => b * 10)
            assert(r.eval == 420)
        }

        "Loop.done flows through the continuation" in {
            val r: Int < Any = ArrowEffect.handleLoopWith(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => Loop.done(-1),
                a => a
            )(b => b * 2)
            assert(r.eval == -2)
        }
    }

    "handleLoopStateWith" - {
        "applies the continuation with the final state observed" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopStateWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any, Int](Tag[Ask], 1, v)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (s, a) => s * 100 + a
            )(b => b + 1)
            assert(r.eval == 313)
        }

        "applies the continuation to a settled input" in {
            val r: Int < Any =
                ArrowEffect.handleLoopStateWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any, Int](Tag[Ask], 7, 35: Int < Ask)(
                    [C] => (s, _) => Loop.continue(s, 0),
                    (s, a) => s + a
                )(b => b * 2)
            assert(r.eval == 84)
        }
    }

    "suspendWith" - {
        "suspends and continues in one node" in {
            val v: Int < Ask = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1)
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(41), a => a)
            assert(r.eval == 42)
        }

        "deep recursion is stack safe" in {
            def loop(i: Int): Int < Ask =
                if i > 100000 then i
                else ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => loop(i + a))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Loop.continue(1), a => a)
            assert(r.eval == 100001)
        }

        "maps chain onto the node" in {
            val v            = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1).map(_ * 2)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(20), a => a)
            assert(r.eval == 42)
        }

        "an effectful continuation suspends again" in {
            val v            = ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(21), a => a)
            assert(r.eval == 42)
        }

        "a long map tower on the node evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            val v            = tower(ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1), 1000000)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => a)
            assert(r.eval == 1000001)
        }
    }

    "a handle capture crossing an inner region" in {
        val inner: Int < Say = ArrowEffect.handleLoop(
            Tag[Ask],
            ask.map(a => say("x").map(_ => ask.map(b => a + b)))
        )([C] => _ => Loop.continue(1), a => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(r.eval == 2)
    }

    "a crossed region resumes without re-running its body" in {
        var runs = 0
        val inner: Int < Say = ArrowEffect.handleLoop(
            Tag[Ask],
            ask.map { a =>
                runs += 1
                if runs > 1 then throw new IllegalStateException("body re-ran")
                say("x").map(_ => a + 1)
            }
        )([C] => _ => Loop.continue(1), a => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(r.eval == 2)
        assert(runs == 1)
    }

    "a crossed stateful region resumes with its in-flight state" in {
        val inner: Int < Say = ArrowEffect.handleLoopState(
            Tag[Ask],
            10,
            ask.map(a => say("x").map(_ => ask.map(b => a * 100 + b)))
        )([C] => (s, _) => Loop.continue(s + 1, s), (_, a) => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(r.eval == 1011)
    }

    "each shot of a multi-shot capture resumes from capture-time state" in {
        val inner: Int < Say = ArrowEffect.handleLoopState(
            Tag[Ask],
            0,
            ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
        )([C] => (s, _) => Loop.continue(s + 1, s), (_, a) => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
            [C] => (_, cont) => cont(()).map(r1 => cont(()).map(r2 => r1 * 100 + r2)),
            a => a
        )
        assert(r.eval == 101)
    }

    "a map after the region applies to the result" in {
        val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(41), a => a)
        assert(r.map(_ * 10).eval == 420)
    }

    "an unresumed handler skips trailing maps at any depth" in {
        for depth <- List(4, 64) do
            val runs         = new Array[Int](depth)
            var v: Int < Ask = ask
            for i <- 0 until depth do
                val j = i
                v = v.map { x =>
                    runs(j) += 1
                    x + 1
                }
            end for
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => 999, a => a * 2)
            assert(r.eval == 1998)
            assert(runs.forall(_ == 0))
    }

    "state survives dumping above a live region" in {
        def tower(v: Int < Ask, n: Int): Int < Ask =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        val body = ask.map(a => tower(ask.map(b => a + b), 64))
        val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, body)(
            [C] => (st, _) => Loop.continue(st + 1, st),
            (st, a) => a * 1000 + st
        )
        assert(r.eval == 85012)
    }

    "park" - {

        "a clause parks by returning and resumes by rewrapping the continuation" in {
            var ready: Maybe[Int]              = Maybe.Absent
            var stash: Maybe[Int => Int < Ask] = Maybe.Absent
            var polls                          = 0
            def boundary(v: Int < Ask): Int < Any =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            polls += 1
                            ready match
                                case Maybe.Present(r) =>
                                    ready = Maybe.Absent
                                    cont(r)
                                case Maybe.Absent =>
                                    stash = Maybe(Region.leak(cont)(_))
                                    -1
                            end match
                    ,
                    a => a
                )
            val body = ask.map(a => ask.map(b => ask.map(c => a * 100 + b * 10 + c)))

            ready = Maybe(1)
            assert(boundary(body).eval == -1)
            assert(polls == 2)

            assert(boundary(stash.get.apply(2)).eval == -1)
            assert(polls == 3)

            assert(boundary(stash.get.apply(3)).eval == 123)
            assert(polls == 3)
        }

        "a park preserves standing sibling regions" in {
            var seen                           = List.empty[String]
            var stash: Maybe[Int => Int < Ask] = Maybe.Absent
            def boundary(v: Int < Ask): Int < Any =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            stash = Maybe(Region.leak(cont)(_))
                            -1
                    ,
                    a => a
                )
            val body: Int < (Ask & Say) = say("before").map(_ => ask.map(a => say("after").map(_ => a + 1)))
            val inner: Int < Ask = ArrowEffect.handleLoop(Tag[Say], body)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue(())
                ,
                a => a
            )
            assert(boundary(inner).eval == -1)
            assert(seen == List("before"))
            assert(boundary(stash.get.apply(41)).eval == 42)
            assert(seen == List("after", "before"))
        }

        "a union tag subsumes both effects the way regions are found" in {
            assert(Tag[Ask].erased <:< Tag[Ask | Say].erased)
            assert(Tag[Say].erased <:< Tag[Ask | Say].erased)
            assert(!(Tag[Ask | Say].erased <:< Tag[Ask].erased))
            assert(!(Tag[Say].erased <:< Tag[Ask].erased))
        }
    }

    "recover" - {

        object Boom extends RuntimeException("boom", null, false, false)

        "a region's recovery clause answers a throw raised in its extent" in {
            val body: Int < Ask = ask.map(_ => (throw Boom): Int)
            val r: Int < Any    = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), a => a, _ => Maybe(-1))
            assert(r.eval == -1)
        }

        "a stateful region's recovery clause receives the live state, not the install-time state" in {
            val body: Int < Ask = ask.map(_ => ask.map(_ => (throw Boom): Int))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, body)(
                [C] => (s, _) => Loop.continue(s + 1, 1),
                (_, a) => a,
                (s, _) => Maybe(-100 - s)
            )

            assert(r.eval == -102)
        }

        "Absent declines and the failure unwinds to the enclosing region" in {
            val body: Int < (Ask & Say) = ask.map(_ => (throw Boom): Int)
            val inner: Int < Say        = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] => (_, cont) => cont(()),
                a => a,
                _ => Maybe(-7)
            )
            assert(r.eval == -7)
        }

        "a settled input's done throw reaches the recovery clause" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 42: Int < Ask)(
                [C] => (_, cont) => cont(1),
                _ => (throw Boom): Int,
                _ => Maybe(-1)
            )
            assert(r.eval == -1)
        }

        "a throw while the input is forced reaches the recovery clause" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], (throw Boom): Int < Ask)(
                [C] => (_, cont) => cont(1),
                a => a,
                _ => Maybe(-1)
            )
            assert(r.eval == -1)
        }

        "a throw while a loop region's input is forced reaches its recovery clause" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], (throw Boom): Int < Ask)(
                [C] => _ => Loop.continue(1),
                a => a,
                _ => Maybe(-1)
            )
            assert(r.eval == -1)
        }

        "a throw while a stateful loop region's input is forced reaches its recovery clause with the initial state" in {
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 5, (throw Boom): Int < Ask)(
                [C] => (s, _) => Loop.continue(s, 1),
                (_, a) => a,
                (s, _) => Maybe(-100 - s)
            )
            assert(r.eval == -105)
        }
    }

    "contracts" - {
        "a clause raising a foreign effect is answered by the outer handler across the region" in {
            val v: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a + b)))
            val sayHandled: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], v)([C] => (_, cont) => ask.map(extra => cont(()).map(_ + extra)), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], sayHandled)([C] => (_, cont) => cont(10), a => a)
            assert(r.eval == 30)
        }

        "a continuation is a value: invoking it twice runs the rest twice" in {
            var runs = 0
            val v = ask.map { a =>
                runs += 1
                a * 10
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)(
                [C] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
                a => a
            )
            assert(r.eval == 30)
            assert(runs == 2)
        }

        "a handler may run another handle inside its answer" in {
            val inner: Int < Say = say("s").map(_ => 5)
            val r: Int < Say = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    (_, cont) =>
                        val answered: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([D] => (_, c) => c(()), a => a)
                        answered.map(cont(_))
                ,
                a => a
            )
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], r)([C] => (_, cont) => cont(()), a => a)
            assert(out.eval == 6)
        }

        "a computation held as a value passes through a handler untouched" in {
            val payload: Int < Any   = (1: Int < Any).map(_ + 1)
            val v: (Int < Any) < Ask = ask.map(_ => Kyo.lift(payload))
            val r: (Int < Any) < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => Kyo.lift(a))
            assert(r.eval.eval == 2)
        }

        "a throw in the handler surfaces at eval" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [C] => (_, _) => (throw new RuntimeException("boom")): Int < Ask,
                a => a
            )
            intercept[RuntimeException] {
                val _ = r.eval
            }
        }

        "a throw in a map surfaces at construction on the settled path" in {
            intercept[RuntimeException] {
                val _ = (1: Int < Any).map(_ => (throw new RuntimeException("boom")): Int)
            }
        }
    }

    "nested box" - {
        "a pending computation held as a value double-boxes and unboxes one level per eval" in {
            val inner: Int < Say                 = say("x").map(_ => 1)
            val once: (Int < Say) < Any          = Kyo.lift(inner)
            val twice: ((Int < Say) < Any) < Any = Kyo.lift(once)
            val back: (Int < Say) < Any          = twice.eval
            val r: Int < Any                     = ArrowEffect.handleCont(Tag[Say], back.eval)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == 1)
        }

        "mapping over a double-boxed computation sees the once-boxed value" in {
            val inner: Int < Say                 = say("x").map(_ => 1)
            val twice: ((Int < Say) < Any) < Any = Kyo.lift(Kyo.lift(inner))
            val unbox = (once: (Int < Say) < Any) =>
                ArrowEffect.handleCont(Tag[Say], once.eval)([C] => (_, cont) => cont(()), a => a).eval
            val r: Int < Any = twice.map(once => unbox(once))
            assert(r.eval == 1)
        }

        "a pending computation held as a value crosses a handler boxed" in {
            val payload: Int < Say         = say("p").map(_ => 7)
            val v: (Int < Say) < Ask       = ask.map(_ => Kyo.lift(payload))
            val handled: (Int < Say) < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => Kyo.lift(a))
            val r: Int < Any               = ArrowEffect.handleCont(Tag[Say], handled.eval)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == 7)
        }

        "a stateful handler passes a pending computation value through intact" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => Kyo.lift(payload))
            val r: (Int < Say) < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (state, _) => Loop.continue(state + 1, 0),
                (_, a) => Kyo.lift(a)
            )
            val boxed          = r.eval
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], boxed)([C] => (_, cont) => cont(()), a => a)
            assert(out.eval == 7)
        }
    }

    "a handler stepping a rescue at the exact budget boundary floats it outward" in {
        def nest(n: Int): Int < Any =
            if n == 0 then
                ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a)
            else (0: Int < Any).map(_ => nest(n - 1))
        assert(nest(Period).eval == 42)
    }

    "coverage" - {
        "the innermost handle wins under nested same-tag handlers" in {
            var outerCount       = 0
            val inner: Int < Ask = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(10), a => a)
            val outer: Int < Any = ArrowEffect.handleCont(Tag[Ask], inner)(
                [C] =>
                    (_, cont) =>
                        outerCount += 1
                        cont(100)
                ,
                a => a
            )
            assert(outer.eval == 11)
            assert(outerCount == 0)
        }

        "settled inputs pass through strictly" in {
            val v: Int < Ask     = 42
            val cont: Int < Any  = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => a)
            val loop: Int < Any  = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(0), a => a)
            val state: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 7, v)([C] => (s, _) => Loop.continue(s, 0), (_, a) => a)
            assert(cont.evalNow == Maybe(42))
            assert(loop.evalNow == Maybe(42))
            assert(state.evalNow == Maybe(42))
            assert(ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0)).evalNow == Maybe(42))
            assert(ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(0)).evalNow == Maybe(42))
            assert(ArrowEffect.handleLoopState(Tag[Ask], 7, v)(
                [C] => (s, _) => Loop.continue(s, 0)
            ).evalNow == Maybe(42))
        }

        "a map chained after a parked handler runs after the handler completes" in {
            var order                = List.empty[String]
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val handled: Int < Say = ArrowEffect.handleCont(Tag[Ask], v)(
                [C] =>
                    (_, cont) =>
                        order :+= "answer"
                        cont(41)
                ,
                a => a
            )
            val chained = handled.map { r =>
                order :+= "after"
                r
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], chained)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == 42)
            assert(order == List("answer", "after"))
        }

        "evaluation recovers after a thrown handler" in {
            intercept[RuntimeException] {
                val failing: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                    [C] => (_, _) => (throw new RuntimeException("boom")): Int < Ask,
                    a => a
                )
                val _ = failing.eval
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a)
            assert(r.eval == 42)
        }
    }

    private def requestStop(): Unit =
        discard(Safepoint.get())
        discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
    end requestStop

    sealed trait Pick[+V] extends ArrowEffect[Const[Unit], Const[V]]
    def pick[V](using Tag[Pick[V]]): V < Pick[V] = ArrowEffect.suspend[Any](Tag[Pick[V]], ())

    sealed trait AskBoxed extends ArrowEffect[Const[Unit], [X] =>> Int < Say]
    def askBoxed: (Int < Say) < AskBoxed = ArrowEffect.suspend[Any](Tag[AskBoxed], ())

    "handleLoopState, ported" - {
        "deep state transitions under a suspending clause are stack safe" in {
            def loop(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => loop(i - a))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, loop(10000))(
                [C] => (s, _) => Effect.defer(Loop.continue(s + 1, 1)),
                (s, a) => s + a
            )
            assert(r.eval == 10000)
        }

        "the overload without done completes with the result and discards the state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handleLoopState(Tag[Ask], 10, v)([C] => (s, _) => Loop.continue(s + 1, s))
            assert(r.eval == 21)
        }

        "a parked stateful region resumes with its state and done" in {
            val v = ask.map(a => ask.map(b => a + b))
            val handled: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] =>
                    (s, _) =>
                        if s == 10 then requestStop()
                        Loop.continue(s + 1, s)
                ,
                (s, a) => s * 1000 + a * 2
            )
            val parked = Eval.partial(handled)
            assert(parked.evalNow.isEmpty)
            assert(parked.eval == 12 * 1000 + (10 + 11) * 2)
        }
    }

    enum First derives CanEqual:
        case Done(value: Int)
        case Standing(cont: Arrow[Int, First, Ask])

    "answering only the first operation" - {

        def firstOf(v: Int < Ask): First < Any =
            ArrowEffect.handleCont(Tag[Ask], v.map(a => First.Done(a): First))(
                [C] => (_, cont) => First.Standing(Region.leak(cont)),
                a => a
            )

        "answers the first and leaves the rest unhandled" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            firstOf(v).eval match
                case First.Standing(cont) =>
                    val rest: First < Ask = cont(4)
                    assert(ArrowEffect.handleCont(Tag[Ask], rest)([X] => (_, k) => k(2), a => a).eval == First.Done(42))
                case other => fail(s"expected a standing operation, got $other")
            end match
        }

        "the clause may end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r = ArrowEffect.handleCont(Tag[Ask], v.map(a => First.Done(a): First))([C] => (_, _) => First.Done(-1), a => a)
            assert(r.eval == First.Done(-1))
            assert(!reached)
        }

        "the continuation is resumable more than once" in {
            var runs = 0
            val v = ask.map { a =>
                runs += 1
                a * 10
            }
            firstOf(v).eval match
                case First.Standing(cont) =>
                    val one: First < Ask = cont(1)
                    val two: First < Ask = cont(2)
                    assert(ArrowEffect.handleCont(Tag[Ask], one)([X] => (_, k) => k(0), a => a).eval == First.Done(10))
                    assert(ArrowEffect.handleCont(Tag[Ask], two)([X] => (_, k) => k(0), a => a).eval == First.Done(20))
                    assert(runs == 2)
                case other => fail(s"expected a standing operation, got $other")
            end match
        }

        "an operation the clause raises re-enters the same region" in {
            var clauseRuns = 0
            val r: First < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(a => First.Done(a): First))(
                [C] =>
                    (_, cont) =>
                        clauseRuns += 1
                        if clauseRuns == 1 then ask.map(extra => First.Done(extra * 10): First)
                        else cont(7)
                ,
                a => a
            )
            assert(r.eval == First.Done(70))
            assert(clauseRuns == 2)
        }

        "an operation raised after the region reaches the outer handler" in {
            var outer = 0
            val captured: First < Ask =
                firstOf(ask.map(_ + 1)).map:
                    case First.Standing(_) => ask.map(extra => First.Done(extra * 10): First)
                    case done              => done
            val out = ArrowEffect.handleCont(Tag[Ask], captured)(
                [X] =>
                    (_, k) =>
                        outer += 1
                        k(4)
                ,
                a => a
            )
            assert(out.eval == First.Done(40))
            assert(outer == 1)
        }

        "a settled body takes the done clause" in {
            assert(firstOf(41).eval == First.Done(41))
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            firstOf(loop(100000)).eval match
                case First.Standing(cont) =>
                    val rest: First < Ask = cont(1)
                    assert(ArrowEffect.handleCont(Tag[Ask], rest)([X] => (_, k) => k(1), a => a).eval == First.Done(0))
                case other => fail(s"expected a standing operation, got $other")
            end match
        }
    }

    "handleFirst, ported" - {
        def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](effectTag: Tag[E], v: A < (E & S))(
            handle: [X] => (I[X], O[X] => A < (E & S)) => B < (S & S2),
            done: A => B < (S & S2)
        ): B < (S & S2) =
            ArrowEffect.handleFirst[I, O, E, A, B, S, S2](effectTag, v)(
                handle = [X] => (input, cont) => handle[X](input, o => cont(o)),
                done = done
            )

        "the handler stays installed until the operation arrives after a foreign crossing" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val first                = handleFirst(Tag[Ask], v)([X] => (_, cont) => cont(41), identity)
            val sayHandled           = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            assert(ArrowEffect.handleCont(Tag[Ask], sayHandled)([X] => (_, cont) => cont(0), a => a).eval == 42)
        }

        "the done clause runs when the computation settles without the operation" in {
            val v: Int < (Ask & Say) = say("x").map(_ => 41)
            val first                = handleFirst(Tag[Ask], v)([X] => (_, _) => -1, _ + 1)
            val r                    = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            assert(r.eval == 42)
        }

        "the continuation re-enters the regions the operation was raised under" in {
            var exits                    = 0
            val inner: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val region = ArrowEffect.handleCont(Tag[Say], inner)([X] => (_, cont) => cont(()), a => a).map { a =>
                exits += 1
                a
            }
            val first = handleFirst(Tag[Ask], region)([X] => (_, cont) => cont(41), identity)
            assert(ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(0), a => a).eval == 42)
            assert(exits == 1)
        }

        "the remainder is handed out as a value and re-handled after a foreign crossing" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val first =
                ArrowEffect.handleFirst[Const[Unit], Const[Int], Ask, Int, Either[Int, Arrow[Int, Int, Ask & Say]], Say, Any](
                    Tag[Ask],
                    v
                )(
                    handle = [X] => (_, cont) => Right(cont),
                    done = a => Left(a)
                )
            val sayHandled = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            sayHandled.eval match
                case Right(rest) =>
                    val resumed  = ArrowEffect.handleCont(Tag[Ask], rest(41))([X] => (_, cont) => cont(0), a => a)
                    val finished = ArrowEffect.handleCont(Tag[Say], resumed)([X] => (_, cont) => cont(()), a => a)
                    assert(finished.eval == 42)
                case Left(a) => fail(s"expected the remainder, got $a")
            end match
        }

        "one effect at two type arguments crosses to the outer handler" in {
            val v: (Int, String) < (Pick[Int] & Pick[String]) =
                pick[String].map(s => pick[Int].map(i => (i, s)))
            val first = handleFirst(Tag[Pick[Int]], v)([X] => (_, cont) => cont(1), identity)
            val outer = ArrowEffect.handleCont(Tag[Pick[String]], first)([X] => (_, cont) => cont("a"), a => a)
            assert(outer.asInstanceOf[(Int, String) < Any].eval == (1, "a"))
        }

        "one effect at two type arguments hands out the remainder as a value" in {
            val v: (Int, String) < (Pick[Int] & Pick[String]) =
                pick[String].map(s => pick[Int].map(i => (i, s)))
            val first =
                ArrowEffect.handleFirst[
                    Const[Unit],
                    Const[Int],
                    Pick[Int],
                    (Int, String),
                    Either[(Int, String), Arrow[Int, (Int, String), Pick[Int] & Pick[String]]],
                    Pick[String],
                    Any
                ](Tag[Pick[Int]], v)(
                    handle = [X] => (_, cont) => Right(cont),
                    done = a => Left(a)
                )
            val outer = ArrowEffect.handleCont(Tag[Pick[String]], first)([X] => (_, cont) => cont("a"), a => a)
            outer.eval match
                case Right(rest) =>
                    val resumed  = ArrowEffect.handleCont(Tag[Pick[Int]], rest(1))([X] => (_, cont) => cont(0), a => a)
                    val finished = ArrowEffect.handleCont(Tag[Pick[String]], resumed)([X] => (_, cont) => cont("b"), a => a)
                    assert(finished.asInstanceOf[(Int, String) < Any].eval == (1, "a"))
                case Left(a) => fail(s"expected the remainder, got $a")
            end match
        }

        "the continuation is handed out as a value and resumed later" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r = handleFirst(Tag[Ask], v)(
                [X] => (_, cont) => (1, () => cont(4)),
                a => (0, () => (a: Int < Ask))
            )
            val (answered, rest) = r.eval
            assert(answered == 1)
            assert(ArrowEffect.handleCont(Tag[Ask], rest())([X] => (_, cont) => cont(2), a => a).eval == 42)
        }

        "the continuation is multi-shot" in {
            var runs = 0
            val v = ask.map { a =>
                runs += 1
                a * 10
            }
            val first = handleFirst(Tag[Ask], v)(
                [X] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
                identity
            )
            assert(ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(0), a => a).eval == 30)
            assert(runs == 2)
        }

        "an operation raised by the clause reaches the outer handler" in {
            var outerAnswered = 0
            val first = handleFirst(Tag[Ask], ask.map(_ + 1))(
                [X] => (_, cont) => ask.map(extra => cont(extra * 10)),
                identity
            )
            val r = ArrowEffect.handleCont(Tag[Ask], first)(
                [X] =>
                    (_, cont) =>
                        outerAnswered += 1
                        cont(4)
                ,
                a => a
            )
            assert(r.eval == 41)
            assert(outerAnswered == 1)
        }

        "a settled input applies the done clause strictly" in {
            val v: Int < Ask = 41
            val r            = handleFirst(Tag[Ask], v)([X] => (_, cont) => cont(0), _ + 1)
            assert(r.evalNow == Maybe(42))
        }

        "the done clause may suspend" in {
            val v: Int < (Ask & Say) = say("x").map(_ => 41)
            val first                = handleFirst(Tag[Ask], v)([X] => (_, _) => -1, a => say("done").map(_ => a + 1))
            val r                    = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            assert(r.eval == 42)
        }

        "maps chained after the region apply to both clauses" in {
            def firstOf(v: Int < (Ask & Say)) =
                handleFirst(Tag[Ask], v)([X] => (_, _) => 1, _ => 2).map(_ * 10).map(_ + 1)
            val answered = ArrowEffect.handleCont(Tag[Say], firstOf(say("x").map(_ => ask)))([X] => (_, cont) => cont(()), a => a)
            val settled  = ArrowEffect.handleCont(Tag[Say], firstOf(say("x").map(_ => 41)))([X] => (_, cont) => cont(()), a => a)
            assert(answered.eval == 11)
            assert(settled.eval == 21)
        }

        "the innermost handleFirst wins under nested same-tag handlers" in {
            var outerAnswered = 0
            val inner         = handleFirst(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(10), identity)
            val outer = handleFirst(Tag[Ask], inner: Int < Ask)(
                [X] =>
                    (_, cont) =>
                        outerAnswered += 1
                        cont(100)
                ,
                identity
            )
            assert(ArrowEffect.handleCont(Tag[Ask], outer)([X] => (_, cont) => cont(0), a => a).eval == 11)
            assert(outerAnswered == 0)
        }

        "the done clause receives a computation held as a value unboxed" in {
            val payload: Int < Say           = say("p").map(_ => 7)
            val v: (Int < Say) < (Ask & Say) = say("x").map(_ => Kyo.lift(payload))
            var seen: AnyRef                 = null
            val first = handleFirst(Tag[Ask], v)(
                [X] => (_, _) => Kyo.lift(payload),
                a =>
                    seen = a.asInstanceOf[AnyRef]
                    Kyo.lift(a)
            )
            val boxed = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => Kyo.lift(a)).eval
            assert(seen eq payload.asInstanceOf[AnyRef])
            assert(ArrowEffect.handleCont(Tag[Say], boxed)([X] => (_, cont) => cont(()), a => a).eval == 7)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val first = handleFirst(Tag[Ask], loop(100000))([X] => (_, cont) => cont(1), identity)
            assert(ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(1), a => a).eval == 0)
        }
    }

    "recover, ported" - {
        "answers operations when nothing fails" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handleCont(Tag[Ask], v)([X] => (_, cont) => cont(21), a => a, _ => Maybe(-1))
            assert(r.eval == 42)
        }

        "recovers a throw in the handler" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handleCont(Tag[Ask], v)(
                [X] => (_, _) => (throw new RuntimeException("boom")): Int < Ask,
                a => a,
                _ => Maybe(-1)
            )
            assert(r.eval == -1)
        }

        "recovers a throw raised after a foreign crossing" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ => (throw new RuntimeException("boom")): Int)
            val caught               = ArrowEffect.handleCont(Tag[Ask], v)([X] => (_, cont) => cont(41), a => a, _ => Maybe(-1))
            val r                    = ArrowEffect.handleCont(Tag[Say], caught)([X] => (_, cont) => cont(()), a => a)
            assert(r.eval == -1)
        }

        "recovers a throw raised after an inner region's exit" in {
            val region       = ArrowEffect.handleCont(Tag[Say], say("x").map(_ => 1))([X] => (_, cont) => cont(()), a => a)
            val v: Int < Ask = ask.map(_ => region.map(_ => (throw new RuntimeException("boom")): Int))
            val r            = ArrowEffect.handleCont(Tag[Ask], v)([X] => (_, cont) => cont(41), a => a, _ => Maybe(-1))
            assert(r.eval == -1)
        }

        "the recovered value is the region's result" in {
            val v = ask.map(_ => (throw new RuntimeException("boom")): Int)
            val r = ArrowEffect.handleCont(Tag[Ask], v)([X] => (_, cont) => cont(0), a => a, _ => Maybe(21)).map(_ * 2)
            assert(r.eval == 42)
        }

        "a throw after the region is not recovered" in {
            val r = ArrowEffect
                .handleCont(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(41), a => a, _ => Maybe(-1))
                .map(_ => (throw new RuntimeException("boom")): Int)
            intercept[RuntimeException] {
                val _ = r.eval
            }
        }

        "a throw inside a region nested in the computation is recovered" in {
            val region = ArrowEffect.handleLoop(Tag[Say], say("x").map(_ => (throw new RuntimeException("boom")): Int))(
                [X] => _ => Loop.continue(())
            )
            val v: Int < Ask = ask.map(_ => region)
            val r            = ArrowEffect.handleCont(Tag[Ask], v)([X] => (_, cont) => cont(0), a => a, _ => Maybe(-1))
            assert(r.eval == -1)
        }

        "a fatal error in the computation is not recovered" in {
            val v = ask.map(_ => (throw new InterruptedException("fatal")): Int)
            val r = ArrowEffect.handleCont(Tag[Ask], v)([X] => (_, cont) => cont(0), a => a, _ => Maybe(-1))
            intercept[InterruptedException] {
                val _ = r.eval
            }
        }

        "a fatal error in the handler is not recovered" in {
            val r = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [X] => (_, _) => (throw new InterruptedException("fatal")): Int < Ask,
                a => a,
                _ => Maybe(-1)
            )
            intercept[InterruptedException] {
                val _ = r.eval
            }
        }

        "an inner handler keeps answering its own operations" in {
            val inner: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val sayHandled: Int < Ask    = ArrowEffect.handleCont(Tag[Say], inner)([X] => (_, cont) => cont(()), a => a)
            val r = ArrowEffect.handleCont(Tag[Ask], sayHandled)([X] => (_, cont) => cont(41), a => a, _ => Maybe(-1))
            assert(r.eval == 42)
        }

        "the done clause takes the region's result" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handleCont(Tag[Ask], v)([X] => (_, cont) => cont(41), a => a * 2, _ => Maybe(-1))
            assert(r.eval == 84)
        }

        "the recovery answers at the region's row, so the done clause is not reached" in {
            var doneRan = false
            val v       = ask.map(_ => (throw new RuntimeException("boom")): Int)
            val r = ArrowEffect.handleCont(Tag[Ask], v)(
                [X] => (_, cont) => cont(41),
                a =>
                    doneRan = true
                    a
                ,
                _ => Maybe(-1)
            )
            assert(r.eval == -1)
            assert(!doneRan)
        }

        "a computation held as a value passes through untouched" in {
            val payload: Int < Any   = (1: Int < Any).map(_ + 1)
            val v: (Int < Any) < Ask = ask.map(_ => Kyo.lift(payload))
            val r: (Int < Any) < Any =
                ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => Kyo.lift(a), _ => Maybe(Kyo.lift(-1)))
            assert(r.eval.eval == 2)
        }
    }

    "two effects interleaved at depth cross regions each step" in {
        def loop(n: Int): Int < (Ask & Say) =
            if n == 0 then 0
            else ask.map(a => say(a.toString).map(_ => loop(n - 1).map(_ + a)))
        val handled = ArrowEffect.handleCont(Tag[Say], loop(1000))([C] => (_, cont) => cont(()), a => a)
        val r       = ArrowEffect.handleCont(Tag[Ask], handled)([C] => (_, cont) => cont(1), a => a)
        assert(r.eval == 1000)
    }

    "the answer fast path" - {
        "a capture across the fast path is multi-shot, including across threads" in {
            var kref: Arrow[Unit, Int, Any] = null
            val body: Int < (Ask & Say) =
                ask.map(a => ask.map(b => say("x").andThen(ask.map(c => a + b + c))))
            val region: Int < Say =
                ArrowEffect.handleLoopState(Tag[Ask], 0, body)(
                    [C] => (n, _) => Loop.continue(n + 1, n),
                    (n, a) => n * 1000 + a
                )
            val r0 = ArrowEffect.handleCont(Tag[Say], region)(
                [C] =>
                    (_, cont) =>
                        kref = cont.asInstanceOf[Arrow[Unit, Int, Any]]
                        cont(())
                ,
                a => a
            ).eval
            assert(r0 == 3003)
            assert(kref(()).eval == 3003)
            assert(kref(()).eval == 3003)
        }

        "a clause keeps only the values it was given" in {
            val seen = ListBuffer[(Int, Unit)]()
            def run(): Int =
                ArrowEffect.handleLoopState(Tag[Ask], 0, ask.map(a => ask.map(b => ask.map(c => a + b + c))))(
                    [C] =>
                        (n, i) =>
                            seen += ((n, i))
                            Loop.continue(n + 1, n)
                    ,
                    (n, a) => n * 1000 + a
                ).eval
            assert(run() == 3003)
            val snapshot = seen.toList
            assert(snapshot == List((0, ()), (1, ()), (2, ())))
            assert(run() == 3003)
            assert(seen.toList.take(3) == snapshot)
            assert(seen.toList.drop(3) == snapshot)
        }

        "a clause can run a full eval of its own mid-loop" in {
            def innerRun(): Int =
                ArrowEffect.handleLoopState(Tag[Ask], 100, ask.map(a => ask.map(b => a + b)))(
                    [C] => (n, _) => Loop.continue(n + 1, n),
                    (n, a) => n + a
                ).eval
            val r: Int = ArrowEffect.handleLoopState(Tag[Ask], 0, ask.map(a => ask.map(b => a + b)))(
                [C] =>
                    (n, _) =>
                        val i = innerRun()
                        Loop.continue(n + 1, (n + i))
                ,
                (n, a) => n * 100000 + a
            ).eval
            assert(r == 200607)
        }

        "a throw after settled answers recovers with every commit already made" in {
            val states = ListBuffer[Int]()
            case class Boom() extends RuntimeException
            def loop(i: Int): Int < Ask =
                if i > 5 then i else ask.map(a => if a == 2 then throw Boom() else loop(i + 1))
            val region: Int < Any =
                ArrowEffect.handleLoopState(Tag[Ask], 0, loop(0))(
                    [C] =>
                        (n, _) =>
                            states += n
                            Loop.continue(n + 1, n)
                    ,
                    (n, a) => a
                )
            val r =
                try region.eval
                catch case _: Boom => -1
            assert(r == -1)
            assert(states.toList == List(0, 1, 2))
        }

        "a park taken mid answer loop resumes in a fresh full eval" in {
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            val region: Int < Any =
                ArrowEffect.handleLoopState(Tag[Ask], 0, countdown(100))(
                    [C] =>
                        (n, _) =>
                            if n == 10 then requestStop()
                            Loop.continue(n + 1, 1)
                    ,
                    (n, a) => n + a
                )
            val first = Eval.partial(region)
            assert(first.evalNow == Maybe.Absent)
            assert(first.eval == 100)
        }
    }

    "the cont answer fast path" - {
        "a mid-loop continuation applied twice replays the tail independently" in {
            def loop(i: Int): Int < Ask =
                if i > 3 then i else ask.map(a => loop(i + a))
            var count = 0
            val r = ArrowEffect.handleCont(Tag[Ask], loop(0))(
                [C] =>
                    (_, cont) =>
                        count += 1
                        if count == 2 then cont(1).map(x => cont(1).map(y => x + y))
                        else cont(1)
                ,
                a => a
            ).eval
            assert(r == 8)
            assert(count == 6)
        }

        "a hoarded fast-path continuation replays after the region finished" in {
            var kref: Arrow[Int, Int, Any] = null
            def loop(i: Int): Int < Ask =
                if i > 3 then i else ask.map(a => loop(i + a))
            val r0 = ArrowEffect.handleCont(Tag[Ask], loop(0))(
                [C] =>
                    (_, cont) =>
                        kref = cont.asInstanceOf[Arrow[Int, Int, Any]]
                        cont(1)
                ,
                a => a
            ).eval
            assert(r0 == 4)
            assert(kref(1).eval == 4)
            assert(kref(1).eval == 4)
        }

        "a clause can run a full eval of its own mid-loop" in {
            def innerRun(): Int =
                ArrowEffect.handleCont(Tag[Ask], ask.map(a => ask.map(b => a + b)))(
                    [C] => (_, cont) => cont(7),
                    a => a
                ).eval
            def loop(i: Int): Int < Ask =
                if i > 3 then i else ask.map(a => loop(i + a))
            val r = ArrowEffect.handleCont(Tag[Ask], loop(0))(
                [C] => (_, cont) => cont(innerRun() / 14),
                a => a
            ).eval
            assert(r == 4)
        }

        "a throw while applying the continuation recovers after the answers made" in {
            val seen = ListBuffer[Int]()
            case class Boom() extends RuntimeException
            def loop(i: Int): Int < Ask =
                if i > 5 then i else ask.map(a => if i == 2 then throw Boom() else loop(i + a))
            val region: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], loop(0))(
                    [C] =>
                        (_, cont) =>
                            seen += seen.size
                            cont(1)
                    ,
                    a => a
                )
            val r =
                try region.eval
                catch case _: Boom => -1
            assert(r == -1)
            assert(seen.toList == List(0, 1, 2))
        }

        "a park taken mid answer loop resumes in a fresh full eval" in {
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            var n = 0
            val region: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], countdown(100))(
                    [C] =>
                        (_, cont) =>
                            n += 1
                            if n == 10 then requestStop()
                            cont(1)
                    ,
                    a => a
                )
            val first = Eval.partial(region)
            assert(first.evalNow == Maybe.Absent)
            assert(first.eval == 0)
            assert(n == 100)
        }
    }

    "safety audit pins" - {
        "a clause that suspends on the outer effect keeps its state lane across the shared cell" in {
            var outerSaw        = List.empty[String]
            val body: Int < Ask = ask.map(a => ask.map(b => ask.map(c => a * 100 + b * 10 + c)))
            val askRegion: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], "s", body)(
                [C] => (s, _) => say(s).map(_ => Loop.continue(s + "+", s.length)),
                (s, a) => if s == "s+++" then a else -1000
            )
            val sayRegion: Int < Any = ArrowEffect.handleLoopState(Tag[Say], 0, askRegion)(
                [C] =>
                    (n, msg) =>
                        outerSaw = outerSaw :+ msg
                        Loop.continue(n + 1, ())
                ,
                (n, a) => n * 1000 + a
            )
            assert(sayRegion.eval == 3123)
            assert(outerSaw == List("s", "s+", "s++"))
        }

        "a clause throw escapes the region on the fast and general cont paths" in {
            case class Boom() extends RuntimeException
            def run(body: Int < Ask): Int =
                try ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a).eval
                catch case _: Boom => -2
            assert(run(ask) == -2)
            assert(run(ask.map(_ + 1)) == -2)
        }

        "a clause throw releases the interior brackets it escapes" in {
            def probe(handle: (Int < Ask) => Int < Any): (Int, Boolean) =
                var sawPanic = false
                val body: Int < Ask =
                    Bracket(Effect.defer(1))(_ => ask.map(_ + 1))((_, r) =>
                        sawPanic = r.exists(_.getMessage == "clause-boom")
                    )
                val out =
                    try handle(body).eval
                    catch case ex: RuntimeException if ex.getMessage == "clause-boom" => -2
                (out, sawPanic)
            end probe
            def boom(): Nothing = throw new RuntimeException("clause-boom")
            val loopSide        = probe(b => ArrowEffect.handleLoop(Tag[Ask], b)([C] => _ => boom(), a => a))
            val contSide        = probe(b => ArrowEffect.handleCont(Tag[Ask], b)([C] => (_, _) => boom(), a => a))
            assert(loopSide == ((-2, true)))
            assert(contSide == ((-2, true)))
        }

        "a throwing deferred payload after an answer does not re-run the consumed continuation" in {
            case class Boom() extends RuntimeException
            var contRuns = 0
            val body: Int < Ask =
                ask.map { a =>
                    contRuns += 1
                    Effect.defer((throw Boom()): Int).map(_ + a)
                }
            val region: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Loop.continue(1),
                a => a
            )
            val r =
                try region.eval
                catch case _: Boom => -1
            assert(r == -1)
            assert(contRuns == 1)
        }

        "a stop arriving during eager construction inside a slice reifies and parks the chain" in {
            var built = 0
            val v: Int < Any =
                Effect.defer(0).map { z =>
                    var acc: Int < Any = z
                    var i              = 0
                    while i < 100 do
                        acc = acc.map { x =>
                            built += 1
                            if built == 50 then requestStop()
                            x + 1
                        }
                        i += 1
                    end while
                    acc
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            assert(built >= 50 && built <= 52, s"built=$built")
            assert(p.eval == 100)
            assert(built == 100)
        }

        "a boxed answer crosses the answers loop unopened" in {
            val payload: Int < Say = say("p").map(_ => 7)
            val region: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[AskBoxed], askBoxed.map(v => Kyo.lift(v)))(
                    [C] => _ => Loop.continue(Kyo.lift(payload): (Int < Say) < Any),
                    a => Kyo.lift(a)
                )
            val got: Int < Say = region.eval
            assert(got.asInstanceOf[AnyRef] eq payload.asInstanceOf[AnyRef])
            val r = ArrowEffect.handleCont(Tag[Say], got)([C] => (_, cont) => cont(()), a => a).eval
            assert(r == 7)
        }
    }

    "recover, ported from catching" - {
        "the recovery dispatches on the exception type" in {
            def recovered(ex: Throwable): String < Any =
                recovering(Effect.defer((throw ex): String)) {
                    case _: IllegalArgumentException => "Illegal Argument"
                    case _: RuntimeException         => "Runtime"
                    case _                           => "Other"
                }
            assert(recovered(new RuntimeException()).eval == "Runtime")
            assert(recovered(new IllegalArgumentException()).eval == "Illegal Argument")
            assert(recovered(new Exception()).eval == "Other")
        }

        "a throw after a handleFirst region's exit reaches the recovery" in {
            val region: String < TestEffect1 =
                ArrowEffect.handleFirst(Tag[TestEffect1], testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                    handle = [C] => (input, cont) => cont(input.toString),
                    done = a => a
                )
            val effect: String < TestEffect1 =
                recovering(region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s))(_ => "caught")
            val result = ArrowEffect.handleCont(Tag[TestEffect1], effect)([C] => (input, cont) => cont(input.toString))
            assert(result.eval == "caught")
        }

        "a throw after a stateful region's exit reaches the recovery" in {
            val region: String < Any =
                ArrowEffect.handleLoopState(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                    [C] => (state, input) => Loop.continue(state + 1, (input * state).toString),
                    (_, a) => a
                )
            val effect = recovering(region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s))(_ => "caught")
            assert(effect.eval == "caught")
        }

        "a throw after a stateful region reached through a resumed continuation reaches the recovery" in {
            val effect: String < TestEffect1 = recovering {
                testEffect1(3).map { prefix =>
                    val region: String < Any =
                        ArrowEffect.handleLoopState(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                            [C] => (state, input) => Loop.continue(state + 1, (input * state).toString),
                            (_, a) => a
                        )
                    region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else prefix + s)
                }
            }(_ => "caught")
            val result = ArrowEffect.handleCont(Tag[TestEffect1], effect)([C] => (input, cont) => cont(input.toString))
            assert(result.eval == "caught")
        }

        "a recovery catches past the budget rescue" in {
            val effect = recovering(burn(Period * 2).map(_ => (throw new RuntimeException("Test exception")): Int))(_ => -1)
            assert(effect.eval == -1)
        }

        "a recovery catches past the budget inside a stateful region" in {
            val body = testEffect1(1).map(a => burn(Period * 2).map(_ => testEffect1(2).map(b => a + b)))
            val region: String < Any =
                ArrowEffect.handleLoopState(Tag[TestEffect1], 7, body)(
                    [C] => (state, input) => Loop.continue(state + 1, (input * state).toString),
                    (_, a) => a
                )
            val effect = recovering(region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s))(_ => "caught")
            assert(effect.eval == "caught")
        }

        "a recovery does not reach into a boxed computation" in {
            val fallback: String < TestEffect1 = "caught"
            val boxed: (String < TestEffect1) < Any =
                recovering(Kyo.lift(testEffect1(1).map(_ => (throw new RuntimeException("Test exception")): String)))(_ => fallback)
            val inner: String < TestEffect1 = boxed.eval
            val handled                     = ArrowEffect.handleCont(Tag[TestEffect1], inner)([C] => (input, cont) => cont(input.toString))
            discard(intercept[RuntimeException](handled.eval))
        }

        "a recovery guards a stateful region across a park" in {
            val body = testEffect1(1).map { a =>
                requestStop()
                testEffect1(2).map(b => a + b)
            }
            val region: String < Any =
                ArrowEffect.handleLoopState(Tag[TestEffect1], 7, body)(
                    [C] => (state, input) => Loop.continue(state + 1, (input * state).toString),
                    (_, a) => a
                )
            val effect = recovering(region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s))(_ => "caught")
            val parked = Eval.partial(effect)
            assert(parked.isInstanceOf[Pending[?, ?]])
            assert(parked.eval == "caught")
        }

        "a deferred payload that throws mid answer loop cannot commit another dispatch's state or continuation" in {
            case class Boom() extends RuntimeException
            val body: Int < (Ask & Say) =
                say("s").map(_ => ask.map(a => Effect.defer((throw Boom()): Int).map(_ + a)))
            val recovered: Int < (Ask & Say) = recovering(body)(_ => -1)
            val askRegion: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], "s0", recovered)(
                [C] => (s, _) => Loop.continue(s + "+", 1),
                (s, a) => if s == "s0+" then a else -100
            )
            val sayRegion: Int < Any = ArrowEffect.handleLoopState(Tag[Say], 100, askRegion)(
                [C] => (n, _) => Loop.continue(n + 1, ()),
                (n, a) => n * 1000 + a
            )
            assert(sayRegion.eval == 100999)
        }

        "a clause throw passes over a recovery standing inside the region on every dispatch path" in {
            case class Boom() extends RuntimeException
            val body: Int < Ask = recovering(ask.map(_ + 1))(_ => -1)
            def viaLoop: Int < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => throw Boom(), a => a)
            def viaCont: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a)
            assert((try viaLoop.eval
            catch case _: Boom => -2) == -2)
            assert((try viaCont.eval
            catch case _: Boom => -2) == -2)
        }

    }

    "reading audit pins" - {
        "an Any-typed Continue2 does not conform to the clause's outcome without Loop.done" in {
            assert(typeCheckErrors(
                """
                val hostile: Any = Loop.continue((), 0).eval
                ArrowEffect.handleLoop(Tag[Ask], ask: Any < Ask)([C] => _ => hostile, (a: Any) => a)
                """
            ).nonEmpty)
        }

        "the fused loop leaves a subtype operation to the general path" in {
            val askAtSub: Int < AskSub = ArrowEffect.suspend[Any](Tag[AskSub], ())
            val body: Int < AskSub     = ask.map(a => askAtSub.map(b => a * 10 + b))
            val r                      = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue(1), a => a)
            val out: Int < Any         = ArrowEffect.handleLoop(Tag[AskSub], r)([C] => _ => Loop.continue(2), a => a)
            assert(out.eval == 12)
        }

        "a boxed answer parked mid loop is still data after the resume" in {
            sealed trait Give extends ArrowEffect[Const[Unit], Const[Int < Say]]
            val give: (Int < Say) < Give = ArrowEffect.suspend[Any](Tag[Give], ())
            val payload: Int < Say       = say("p").map(_ => 7)
            val region: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[Give], give.map(v => Kyo.lift[Int < Say, Any](v)))(
                    [C] =>
                        _ =>
                            requestStop()
                            Loop.continue(Kyo.lift[Int < Say, Any](payload))
                    ,
                    a => Kyo.lift[Int < Say, Any](a)
                )
            val parked         = Eval.partial(region)
            val got: Int < Say = parked.eval
            assert(got.asInstanceOf[AnyRef] eq payload.asInstanceOf[AnyRef])
        }

        "a recovery after a foreign in-place answer sees the advanced state" in {
            val boom                    = new RuntimeException("boom")
            val body: Int < (Ask & Say) = say("x").map(_ => ask.map(_ => (throw boom): Int))
            val inner: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 0, body)(
                [C] => (s, _) => Loop.continue(s + 1, 1),
                (_, a) => a,
                (s, _) => Maybe(-100 - s)
            )
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == -101)
        }
    }

    "eff issue 12 pins" - {
        sealed trait Cfg extends ContextEffect[Int]
        def read: Int < Cfg = ContextEffect.suspend(Tag[Cfg])

        def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
            ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

        "a local handler around the operation does not see the effect its interpreter's clause raises" in {
            var localAnswered = 0
            val local: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], ask: Int < (Ask & Say))(
                    [C] =>
                        (_, _) =>
                            localAnswered += 1
                            -1
                    ,
                    a => a
                )
            val interpreted: Int < Say =
                ArrowEffect.handleCont(Tag[Ask], local)([C] => (_, cont) => say("not caught").map(_ => cont(0)), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], interpreted)([C] => (_, _) => -99, a => a)
            assert(r.eval == -99)
            assert(localAnswered == 0)
        }

        "a computation answered as a value runs under the local handler the clause could not see" in {
            var localAnswered = 0
            val local: Int < AskBoxed =
                ArrowEffect.handleCont(Tag[Say], askBoxed.map(v => v): Int < (AskBoxed & Say))(
                    [C] =>
                        (_, _) =>
                            localAnswered += 1
                            -1
                    ,
                    a => a
                )
            val payload: Int < Say = say("not caught").map(_ => 7)
            val interpreted: Int < Say =
                ArrowEffect.handleCont(Tag[AskBoxed], local)([C] => (_, cont) => cont(payload), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], interpreted)([C] => (_, _) => -99, a => a)
            assert(r.eval == -1)
            assert(localAnswered == 1)
        }

        "a computation answered as a value that yields inward escapes the answering region, and the resume site's handler answers the remainder" in {
            var stash                      = Maybe.empty[Arrow[Unit, Int, AskBoxed & Say]]
            var oldAnswered                = 0
            var newAnswered                = 0
            val op: Int < (AskBoxed & Say) = askBoxed.map(v => v)
            val body: Int < (AskBoxed & Say) =
                op.map(a => op.map(b => a * 10 + b))
            val coroutine: Int < AskBoxed = ArrowEffect.handleCont(Tag[Say], body)(
                [C] =>
                    (_, cont) =>
                        stash = Maybe(Region.leak(cont))
                        -1
                ,
                a => a
            )
            val first: Int < Any = ArrowEffect.handleCont(Tag[AskBoxed], coroutine)(
                [C] =>
                    (_, cont) =>
                        oldAnswered += 1
                        cont(say("yield").map(_ => 1))
                ,
                a => a
            )
            assert(first.eval == -1)
            val resumed: Int < AskBoxed =
                ArrowEffect.handleCont(Tag[Say], stash.get(()))([C] => (_, cont) => cont(()), a => a)
            val second: Int < Any = ArrowEffect.handleCont(Tag[AskBoxed], resumed)(
                [C] =>
                    (_, cont) =>
                        newAnswered += 1
                        cont(5)
                ,
                a => a
            )
            assert(second.eval == 15)
            assert(oldAnswered == 1)
            assert(newAnswered == 1)
        }

        "each shot of a computation answered as a value runs under the re-established local handler" in {
            var localAnswered = 0
            val local: Int < AskBoxed =
                ArrowEffect.handleCont(Tag[Say], askBoxed.map(v => v): Int < (AskBoxed & Say))(
                    [C] =>
                        (_, cont) =>
                            localAnswered += 1
                            cont(())
                    ,
                    a => a
                )
            val payload: Int < Say = say("m").map(_ => 1)
            val interpreted: Int < Say = ArrowEffect.handleCont(Tag[AskBoxed], local)(
                [C] => (_, cont) => cont(payload).map(a => cont(payload).map(b => a + b)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], interpreted)([C] => (_, _) => -99, a => a)
            assert(r.eval == 2)
            assert(localAnswered == 2)
        }

        "a clause's code after the resume is not captured by a coroutine handler inside the region" in {
            var stash                   = Maybe.empty[Arrow[Unit, Int, Ask & Say]]
            val log                     = ListBuffer[String]()
            val body: Int < (Ask & Say) = ask.map(a => say("yield").map(_ => a + 1))
            val coroutine: Int < Ask = ArrowEffect.handleCont(Tag[Say], body)(
                [C] =>
                    (_, cont) =>
                        stash = Maybe(Region.leak(cont))
                        -1
                ,
                a => a
            )
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], coroutine)(
                [C] =>
                    (_, cont) =>
                        cont(1).map { x =>
                            log += s"post $x"
                            x * 10
                    },
                a => a
            )
            assert(r.eval == -10)
            assert(log.toList == List("post -1"))
            val resumed: Int < Any = ArrowEffect.handleCont(
                Tag[Ask],
                ArrowEffect.handleCont(Tag[Say], stash.get(()))([C] => (_, cont) => cont(()), a => a)
            )([C] => (_, cont) => cont(100), a => a)
            assert(resumed.eval == 2)
            assert(log.toList == List("post -1"))
        }

        "a handler whose clause suspends outward travels with the continuation and answers ahead of the handler at the resume site" in {
            var stash           = Maybe.empty[Arrow[Unit, Int, Say]]
            val yields          = ListBuffer[String]()
            var atResumeSite    = 0
            val body: Int < Ask = ask.map(a => ask.map(b => a * 10 + b))
            val captured: Int < Say =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => say("yield").map(_ => cont(1)), a => a)
            val first: Int < Any = ArrowEffect.handleCont(Tag[Say], captured)(
                [C] =>
                    (_, cont) =>
                        stash = Maybe(Region.leak(cont))
                        -1
                ,
                a => a
            )
            assert(first.eval == -1)
            val swapped: Int < Say =
                ArrowEffect.handleCont(Tag[Ask], stash.get(()): Int < (Ask & Say))(
                    [C] =>
                        (_, cont) =>
                            atResumeSite += 1
                            cont(5)
                    ,
                    a => a
                )
            val second: Int < Any = ArrowEffect.handleCont(Tag[Say], swapped)(
                [C] =>
                    (s, cont) =>
                        yields += s
                        cont(())
                ,
                a => a
            )
            assert(second.eval == 11)
            assert(atResumeSite == 0)
            assert(yields.toList == List("yield"))
        }

        "a clause's code after the resume runs where the continuation is resumed, under the bindings there" in {
            var stash = Maybe.empty[Arrow[Unit, Int, Say & Cfg]]
            val captured: Int < (Say & Cfg) =
                ArrowEffect.handleCont(Tag[Ask], ask.map(a => a))(
                    [C] => (_, cont) => say("yield").map(_ => cont(1)).map(x => read.map(c => x + c)),
                    a => a
                )
            val first: Int < Any =
                ContextEffect.handleInheritable(Tag[Cfg], 10)(
                    ArrowEffect.handleCont(Tag[Say], captured)(
                        [C] =>
                            (_, cont) =>
                                stash = Maybe(Region.leak(cont))
                                -1
                        ,
                        a => a
                    )
                )
            assert(first.eval == -1)
            val second: Int < Any =
                ContextEffect.handleInheritable(Tag[Cfg], 100)(
                    ArrowEffect.handleCont(Tag[Say], stash.get(()))([C] => (_, cont) => cont(()), a => a)
                )
            assert(second.eval == 101)
        }

        "a recovery captured with the continuation answers a throw in the remainder before the resume site's recovery" in {
            val boom                     = new RuntimeException("boom")
            var stash                    = Maybe.empty[Arrow[Int, Int, Ask & Wrap]]
            val body: Int < (Ask & Wrap) = recovering(ask.map(a => if a < 0 then (throw boom): Int else a))(_ => -1)
            val first: Int < Any = recovering(
                ArrowEffect.handleCont(Tag[Ask], body)(
                    [C] =>
                        (_, cont) =>
                            stash = Maybe(Region.leak(cont))
                        0
                    ,
                    a => a
                )
            )(_ => -3)
            assert(first.eval == 0)
            val resumed: Int < Any = recovering(answerAsk(0)(stash.get(-5)))(_ => -2)
            assert(resumed.eval == -1)
        }

        "a loop region's recovery does not guard its clause after the clause suspends" in {
            val boom = new RuntimeException("boom")
            val viaLoop: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => say("c").map(_ => (throw boom): Loop.Outcome[Int < (Ask & Say), Int < Say]),
                a => a,
                _ => Maybe(-1)
            )
            val r = ArrowEffect.handleCont(Tag[Say], viaLoop)([C] => (_, cont) => cont(()), a => a)
            assert(intercept[RuntimeException](r.eval) eq boom)
        }

        "a cont region's recovery guards its clause after the clause suspends" in {
            val boom = new RuntimeException("boom")
            val viaCont: Int < Say = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [C] => (_, _) => say("c").map(_ => (throw boom): Int),
                a => a,
                _ => Maybe(-1)
            )
            val r = ArrowEffect.handleCont(Tag[Say], viaCont)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == -1)
        }

        "a stashed continuation's row is required at the resume site" in {
            assert(typeCheckErrors(
                """
                val stash: Arrow[Unit, Int, Say & Cfg] = ???
                val r: Int < Any = ArrowEffect.handleCont(Tag[Say], stash(()))([C] => (_, cont) => cont(()), a => a)
                """
            ).nonEmpty)
        }
    }

    "eff issue 12 pins, writer" - {
        sealed trait Tell extends ArrowEffect[Const[List[Int]], Const[Unit]]
        def tell(w: List[Int]): Unit < Tell = ArrowEffect.suspend[Any](Tag[Tell], w)

        def runWriter[A, S](v: A < (Tell & S)): (A, List[Int]) < S =
            ArrowEffect.handleLoopState(Tag[Tell], List.empty[Int], v)(
                [C] => (acc, w) => Loop.continue(acc ++ w, ()),
                (acc, a) => (a, acc)
            )

        def listen[A, S](v: A < (Tell & S)): (A, List[Int]) < (Tell & S) =
            ArrowEffect.handleLoopState(Tag[Tell], List.empty[Int], v)(
                [C] => (acc, w) => tell(w).map(_ => Loop.continue(acc ++ w, ())),
                (acc, a) => (a, acc)
            )

        def pass[A, S](v: (A, List[Int] => List[Int]) < (Tell & S)): A < (Tell & S) =
            ArrowEffect.handleLoopState(Tag[Tell], List.empty[Int], v)(
                [C] => (acc, w) => Loop.continue(acc ++ w, ()),
                (acc, af) => tell(af._2(acc)).map(_ => af._1)
            )

        def censor[A, S](f: List[Int] => List[Int])(v: A < (Tell & S)): A < (Tell & S) =
            ArrowEffect.handleLoop(Tag[Tell], v)(
                [C] => w => tell(f(w)).map(_ => Loop.continue(())),
                a => a
            )

        sealed trait CC extends ArrowEffect[Const[Maybe[Int]], Const[Int]]
        def capture: Int < CC      = ArrowEffect.suspend[Any](Tag[CC], Maybe.empty[Int])
        def jump(n: Int): Int < CC = ArrowEffect.suspend[Any](Tag[CC], Maybe(n))

        def runCC[A, S](v: A < (CC & S)): A < S =
            var captured = Maybe.empty[Arrow[Int, A, CC & S]]
            ArrowEffect.handleCont(Tag[CC], v)(
                [C] =>
                    (input, cont) =>
                        input match
                            case Maybe.Absent =>
                                captured = Maybe(Region.leak(cont))
                                cont(0)
                            case Maybe.Present(n) => captured.get(n)
                ,
                a => a
            )
        end runCC

        "a forwarding listen keeps a tell that precedes an escape and re-establishes its frame at the captured state" in {
            val body: (Unit, List[Int]) < (Tell & CC) =
                listen(capture.map { x =>
                    if x == 0 then tell(List(1)).map(_ => jump(2)).unit
                    else tell(List(x))
                })
            assert(runWriter(runCC(body)).eval == ((((), List(2)), List(1, 2))))
        }

        "a transactional pass drops its tells when the body escapes before completing" in {
            val body: Unit < (Tell & CC) =
                capture.map { x =>
                    if x == 0 then pass(tell(List(1)).map(_ => jump(2)).map(_ => ((), (l: List[Int]) => l)))
                    else tell(List(x))
                }
            assert(runWriter(runCC(body)).eval == (((), List(2))))
        }

        "a transactional pass duplicates the tells before a multi-shot resume; a forwarding listen does not" in {
            val viaPass: (Unit, List[Int]) < Any =
                runWriter(
                    ArrowEffect.handleCont(
                        Tag[Ask],
                        pass(tell(List(1)).map(_ => ask).map(a => tell(List(a)).map(_ => ((), (l: List[Int]) => l))))
                    )([C] => (_, cont) => cont(2).map(_ => cont(3)), a => a)
                )
            assert(viaPass.eval == (((), List(1, 2, 1, 3))))
            val viaListen: ((Unit, List[Int]), List[Int]) < Any =
                runWriter(
                    ArrowEffect.handleCont(
                        Tag[Ask],
                        listen(tell(List(1)).map(_ => ask).map(a => tell(List(a))))
                    )([C] => (_, cont) => cont(2).map(_ => cont(3)), a => a)
                )
            assert(viaListen.eval == ((((), List(1, 3)), List(1, 2, 3))))
        }

        "a listen around the operation does not see the tell its interpreter's clause raises" in {
            val interpreted: (Int, List[Int]) < Tell =
                ArrowEffect.handleCont(Tag[Ask], listen(ask: Int < (Tell & Ask)))(
                    [C] => (_, cont) => tell(List(7)).map(_ => cont(1)),
                    a => a
                )
            assert(runWriter(interpreted).eval == (((1, List.empty[Int]), List(7))))
        }

        "a censor around the operation does not censor the tell its interpreter's clause raises" in {
            val body: Int < (Tell & Ask) = censor(_.map(_ * 100))(ask.map(a => tell(List(a)).map(_ => a)))
            val interpreted: Int < Tell =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => tell(List(7)).map(_ => cont(1)), a => a)
            assert(runWriter(interpreted).eval == ((1, List(7, 100))))
        }
    }

    "eff issue 12 pins, choice" - {
        sealed trait Choose extends ArrowEffect[Const[Unit], Const[Boolean]]
        def choose: Boolean < Choose = ArrowEffect.suspend[Any](Tag[Choose], ())

        sealed trait Bump extends ArrowEffect[Const[Unit], Const[Int]]
        def bump: Int < Bump = ArrowEffect.suspend[Any](Tag[Bump], ())

        def counted[A, S](v: A < (Bump & S)): A < S =
            ArrowEffect.handleLoopState(Tag[Bump], 0, v)(
                [C] => (n, _) => Loop.continue(n + 1, (n + 1)),
                (_, a) => a
            )

        def both[A, S](v: List[A] < (Choose & S)): List[A] < S =
            ArrowEffect.handleCont(Tag[Choose], v)(
                [C] => (_, cont) => cont(true).map(a => cont(false).map(b => a ++ b)),
                a => a
            )

        "scoped state kept in a nested region is per branch under a multi-shot choice" in {
            val body: List[(Boolean, Int)] < (Choose & Bump) = choose.map(b => bump.map(n => List((b, n))))
            val perBranch: List[(Boolean, Int)] < Any        = both(counted(body))
            assert(perBranch.eval == List((true, 1), (false, 1)))
        }

        "state kept in the answering handler is shared across the branches of a multi-shot choice" in {
            val body: List[(Boolean, Int)] < (Choose & Bump) = choose.map(b => bump.map(n => List((b, n))))
            val shared: List[(Boolean, Int)] < Any           = counted(both(body))
            assert(shared.eval == List((true, 1), (false, 2)))
        }
    }

    "higher-order operations" - {
        enum ReaderOp[X]:
            case Ask                                            extends ReaderOp[Int]
            case Local[X](f: Int => Int, m: X < (Reader & Say)) extends ReaderOp[X]
        sealed trait Reader extends ArrowEffect[ReaderOp, [X] =>> X]

        def askR: Int < Reader = ArrowEffect.suspend[Int](Tag[Reader], ReaderOp.Ask)
        def local[A](f: Int => Int)(m: A < (Reader & Say)): A < (Reader & Say) =
            ArrowEffect.suspend[A](Tag[Reader], ReaderOp.Local(f, m))

        def runReader[A, S](env: Int)(v: A < (Reader & Say & S)): A < (Say & S) =
            ArrowEffect.handleCont(Tag[Reader], v)(
                [C] =>
                    (op, cont) =>
                        op match
                            case _: ReaderOp.Ask.type => cont(env)
                            case ReaderOp.Local(f, m) => runReader(f(env))(m).map(c => cont(c))
                ,
                a => a
            )

        "a higher-order operation carries its body, and the handler elaborates it as a region that a crossing carries along" in {
            val prog: (Int, Int, Int) < (Reader & Say) =
                local(_ * 10)(askR.map(a => say("x").map(_ => askR.map(b => (a, b)))))
                    .map((a, b) => askR.map(c => (a, b, c)))
            val r: (Int, Int, Int) < Any =
                ArrowEffect.handleCont(Tag[Say], runReader(4)(prog))([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == ((40, 40, 4)))
        }

        "an elaborated region is re-established per shot under a multi-shot resume" in {
            val prog: List[(Int, Int)] < (Reader & Say) =
                local(_ * 10)(askR.map(a => say("x").map(_ => askR.map(b => List((a, b))))))
            val r: List[(Int, Int)] < Any =
                ArrowEffect.handleCont(Tag[Say], runReader(4)(prog))(
                    [C] => (_, cont) => cont(()).map(x => cont(()).map(y => x ++ y)),
                    a => a
                )
            assert(r.eval == List((40, 40), (40, 40)))
        }

        enum ErrOp[X]:
            case Fail(msg: String)                          extends ErrOp[Nothing]
            case Catch[X](m: X < Err, h: String => X < Err) extends ErrOp[X]
        sealed trait Err extends ArrowEffect[ErrOp, [X] =>> X]

        def fail(msg: String): Nothing < Err = ArrowEffect.suspend[Nothing](Tag[Err], ErrOp.Fail(msg))
        def catching[A](m: A < Err)(h: String => A < Err): A < Err =
            ArrowEffect.suspend[A](Tag[Err], ErrOp.Catch(m, h))

        def runErr[A, S](v: A < (Err & S)): Either[String, A] < S =
            ArrowEffect.handleCont(Tag[Err], v.map(a => (Right(a): Either[String, A])))(
                [C] =>
                    (op, cont) =>
                        op match
                            case ErrOp.Fail(msg) => (Left(msg): Either[String, A])
                            case ErrOp.Catch(m, h) =>
                                runErr(m).map {
                                    case Right(c) => cont(c)
                                    case Left(e) =>
                                        runErr(h(e)).map {
                                            case Right(c) => cont(c)
                                            case Left(e2) => (Left(e2): Either[String, A])
                                        }
                            }
                ,
                a => a
            )

        "a higher-order catch elaborates its body under a recovering region, and a failure after it escapes" in {
            val caught: Int < Err = catching(fail("boom").map(_ => 1))(_ => 41)
            val later: Int < Err  = caught.map(a => fail("later").map(_ => a + 1))
            assert(runErr(caught.map(_ + 1)).eval == Right(42))
            assert(runErr(later).eval == Left("later"))
        }
    }

    // A continuation a clause receives is confined to the region: its row carries `Region.NoEscape`,
    // which no handler accepts and no Isolate can be derived for. The kernel's notion of crossing to
    // another fiber is an Isolate, so the gate is pinned here on a stand-in for a fork, with no
    // scheduler involved: anything that demands an isolate for the continuation's row.
    "no escape" - {
        def cross[A, S, S2](v: A < S)(using Isolate[S, Any, S2]): A < S = v

        val confined = "cannot leave the region that handed it out"

        "a continuation resumed inside its clause compiles, and the region discharges the marker" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a)
            assert(r.eval == 42)
        }

        "a continuation applied more than once inside its clause compiles" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask)([C] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)), a => a)
            assert(r.eval == 3)
        }

        "a continuation cannot cross an isolate boundary" in {
            typeCheckFailure(
                """
                ArrowEffect.handleCont(Tag[Ask], ask)([C] => (_, cont) => cross(cont(1)), (a: Int) => a)
                """
            )(confined)
        }

        "the refusal names the marker even when other effects in the row have no isolate either" in {
            typeCheckFailure(
                """
                val body: Int < (Ask & Say) = say("s").map(_ => ask)
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cross(cont(1)), (a: Int) => a)
                """
            )(confined)
        }

        "a row without the marker gets the ordinary isolate error, not the refusal" in {
            // outside a clause: inside one, the expected type puts the marker on anything the clause
            // builds, so every row there carries it
            typeCheckFailure("cross(ask)")("This operation requires isolation for effects")
        }

        "a continuation cannot be stored in a holder typed without the marker" in {
            typeCheckFailure(
                """
                var stash: Arrow[Unit, Int, Ask] = null
                ArrowEffect.handleCont(Tag[Ask], ask)([C] => (_, cont) => { stash = cont; cont(1) }, (a: Int) => a)
                """
            )("NoEscape")
        }

        "a hand-written isolate for the marker does not open the gate" in {
            typeCheckFailure(
                """
                ArrowEffect.handleCont(Tag[Ask], ask)(
                    [C] => (_, cont) =>
                        given Isolate[Region.NoEscape, Any, Any] =
                            Isolate.internal.Contextual.asInstanceOf[Isolate[Region.NoEscape, Any, Any]]
                        cross(cont(1)),
                    (a: Int) => a
                )
                """
            )(confined)
        }

        "a direct summon of the marker's isolate does not compile" in {
            // an inline given that aborts is reported as a failed search rather than with its own text,
            // so what a direct summon shows is the marker in the type it could not find an instance for
            typeCheckFailure(
                """
                ArrowEffect.handleCont(Tag[Ask], ask)(
                    [C] => (_, cont) =>
                        summon[Isolate[Region.NoEscape, Any, Any]]
                        cont(1),
                    (a: Int) => a
                )
                """
            )("NoEscape")
        }

        "the operation form confines its continuation the same way" in {
            typeCheckFailure(
                """
                ArrowEffect.handleMasking(Tag[Ask], ask)([X] => (op, next) => cross(op.map(next(_))), (a: Int) => a)
                """
            )(confined)
        }

        "a continuation from an enclosing clause can be resumed inside a nested clause on the same fiber" in {
            val body: Int < (Ask & Say) = say("s").map(_ => ask)
            val r: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], body)(
                    [C] => (_, outer) => ArrowEffect.handleCont(Tag[Ask], outer(()))([C2] => (_, inner) => inner(5), a => a),
                    a => a
                )
            val answered: Int < Any = ArrowEffect.handleCont(Tag[Ask], r)([C] => (_, cont) => cont(0), a => a)
            assert(answered.eval == 5)
        }
    }

end ArrowEffectTest
