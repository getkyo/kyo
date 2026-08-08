package kyo.kernel2

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Render
import kyo.Result
import kyo.Tag
import kyo.discard
import kyo.kernel2.*
import kyo.render
import kyo.test.Test
import language.implicitConversions
import scala.annotation.nowarn

sealed trait Ask  extends ArrowEffect[Const[Unit], Const[Int]]
sealed trait Ask2 extends ArrowEffect[Const[Unit], Const[Int]]

class PendingTest extends Test[Any]:

    "map" in {
        val x: Int < Any    = 5
        val y: String < Any = x.map(_.toString)
        assert(y.eval == "5")
    }

    "flatMap" in {
        val x: Int < Any    = 5
        val y: String < Any = x.flatMap(i => (i * 2).toString)
        assert(y.eval == "10")
    }

    "handle chaining with for-comprehension" in {
        val result =
            for
                x <- 5: Int < Any
                y <- 3: Int < Any
            yield x + y

        assert(result.eval == 8)
    }

    "flatten" in {
        val x: Int < Any < Any = Kyo.lift(10: Int < Any)
        val y: Int < Any       = x.flatten
        assert(y.eval == 10)
    }

    "unit" in {
        val x: Int < Any  = 5
        val y: Unit < Any = x.unit
        assert(y.eval == (()))
    }

    "andThen" in {
        val x: Unit < Any   = ()
        val y: String < Any = x.andThen("result")
        assert(y.eval == "result")
    }

    "eval" in {
        val x: Int < Any = 10
        assert(x.eval == 10)
    }

    "eval should not compile for pending effects" in {
        @nowarn("msg=unused") trait CustomEffect extends ArrowEffect[Const[Unit], Const[Unit]]
        typeCheckFailure("val x: Int < CustomEffect = 5; x.eval")("value eval is not a member of Int < CustomEffect")
    }

    "lift" - {

        "allows lifting pure values" in {
            val x: Int < Any = 5
            assert(x.eval == 5)
        }

        "nested computation" - {
            "generic method effect mismatch" in {
                @nowarn("msg=unused") def test1[A](v: A < Any) = v
                typeCheckFailure("test1(1: Int < TestEffect)")("Required: Any < Any")
            }
            "inference widening" in {
                typeCheckFailure("val _: Int < Any < Any = (1: Int < Any)")("Required: Int < Any < Any")
            }
        }

        "functions" - {
            "one param" in {
                val f: Int => String =
                    _.toString
                val lifted: Int => String < Any = f
                assert(lifted(42).eval == "42")
            }

            "two params" in {
                val f: (Int, Int) => String =
                    (a, b) => (a + b).toString
                val lifted: (Int, Int) => String < Any = f
                assert(lifted(20, 22).eval == "42")
            }

            "three params" in {
                val f: (Int, Int, Int) => String =
                    (a, b, c) => (a + b + c).toString
                val lifted: (Int, Int, Int) => String < Any = f
                assert(lifted(10, 20, 12).eval == "42")
            }

            "four params" in {
                val f: (Int, Int, Int, Int) => String =
                    (a, b, c, d) => (a + b + c + d).toString
                val lifted: (Int, Int, Int, Int) => String < Any = f
                assert(lifted(10, 20, 10, 2).eval == "42")
            }

            "doesn't lift nested computations" in {
                val f1: Int => String < Any                  = (_) => "test"
                val f2: (Int, Int) => String < Any           = (_, _) => "test"
                val f3: (Int, Int, Int) => String < Any      = (_, _, _) => "test"
                val f4: (Int, Int, Int, Int) => String < Any = (_, _, _, _) => "test"
                discard(f1, f2, f3, f4)
                typeCheckFailure("""
                    val _: Int => String < Any < Any = f1
                """)(
                    "Required: Int => String < Any < Any"
                )
                typeCheckFailure("""
                    val _: (Int, Int) => String < Any < Any = f2
                """)(
                    "Required: (Int, Int) => String < Any < Any"
                )
                typeCheckFailure("""
                    val _: (Int, Int, Int) => String < Any < Any = f3
                """)(
                    "Required: (Int, Int, Int) => String < Any < Any"
                )
                typeCheckFailure("""
                    val _: (Int, Int, Int, Int) => String < Any < Any = f4
                """)(
                    "Required: (Int, Int, Int, Int) => String < Any < Any"
                )
            }
        }
    }

    sealed trait TestEffect extends ArrowEffect[Const[Int], Const[Int]]
    object TestEffect:
        def apply(i: Int): Int < TestEffect = ArrowEffect.suspend[Unit](Tag[TestEffect], i)
        def run[A, S](v: => A < (TestEffect & S)) =
            ArrowEffect.handle(Tag[TestEffect], v)(
                [C] => (input, cont) => cont(input + 1)
            )
    end TestEffect

    "evalNow" - {
        "returns Present for pure values" in {
            val x: Int < Any = 5
            assert(x.evalNow == Maybe(5))
        }

        "returns Absent for suspended computations" in {
            val x: Int < TestEffect = TestEffect(5)
            assert(x.evalNow == Maybe.empty)
        }

        // handler installation is pure, so the handled inner computation is still
        // suspended until evaluated; the nested lift itself is a completed outer value
        "accepts nested computations" in {
            Kyo.lift(TestEffect(1)).evalNow match
                case Absent     => fail()
                case Present(v) => assert(TestEffect.run(v).eval == 2)
            end match
        }
    }

    "handle" - {
        "applies a function to a pure value" in {
            val result = (5: Int < Any).handle(_.map(_ + 1))
            assert(result.eval == 6)
        }

        "applies a function to an effectful value" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result                   = effect.handle(v => TestEffect.run(v))
            assert(result.eval == 2)
        }

        "allows chaining of operations" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result = effect
                .handle(v => v.map(_ * 2))
                .handle(v => TestEffect.run(v))
            assert(result.eval == 4)
        }

        "works with functions that return effects" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result = effect.handle { v =>
                TestEffect.run(v).map { x =>
                    TestEffect.run(TestEffect(1))
                }
            }
            assert(result.eval == 2)
        }

        "works with identity function" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result                   = effect.handle(identity)
            assert(TestEffect.run(result).eval == 2)
        }

        "can produce a value instead of a computation" in {
            val result: Int = TestEffect(1).handle(TestEffect.run).handle(_.eval)
            assert(result == 2)
        }

        "works with two functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2)
            )
            assert(result.eval == 12)
        }

        "works with three functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString)
            )
            assert(result.eval == "12")
        }

        "works with four functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length)
            )
            assert(result.eval == 2)
        }

        "works with five functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1)
            )
            assert(result.eval == true)
        }

        "works with six functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No")
            )
            assert(result.eval == "Yes")
        }

        "works with seven functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No"),
                _.map(_.toLowerCase)
            )
            assert(result.eval == "yes")
        }

        "works with eight functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No"),
                _.map(_.toLowerCase),
                _.map(_.length)
            )
            assert(result.eval == 3)
        }

        "works with nine functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No"),
                _.map(_.toLowerCase),
                _.map(_.length),
                _.map(_ * 2)
            )
            assert(result.eval == 6)
        }

        "works with ten functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No"),
                _.map(_.toLowerCase),
                _.map(_.length),
                _.map(_ * 2),
                _.map(_ > 5)
            )
            assert(result.eval == true)
        }
    }

    "nested computations" - {
        sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]
        object TestEffect1:
            def apply(i: Int): String < TestEffect1 =
                ArrowEffect.suspend[Any](Tag[TestEffect1], i)

            def run[A, S](v: A < (TestEffect1 & S)): A < S =
                ArrowEffect.handle(Tag[TestEffect1], v)([C] =>
                    (input, cont) =>
                        cont(s"Effect1:$input"))
        end TestEffect1

        sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[Int]]
        object TestEffect2:
            def apply(s: String): Int < TestEffect2 =
                ArrowEffect.suspend[Any](Tag[TestEffect2], s)

            def run[A, S](v: A < (TestEffect2 & S)): A < S =
                ArrowEffect.handle(Tag[TestEffect2], v)([C] =>
                    (input, cont) =>
                        cont(input.length + 10))
        end TestEffect2

        sealed trait TestEffect3 extends ContextEffect[Boolean]
        object TestEffect3:
            def apply(): Boolean < TestEffect3 =
                ContextEffect.suspend(Tag[TestEffect3])

            def run[A, S](value: Boolean)(v: A < (TestEffect3 & S)): A < S =
                ContextEffect.handle(Tag[TestEffect3], value)(v)
        end TestEffect3

        "basic nesting operations" in {
            val nested: String < TestEffect1 < Any = Kyo.lift(TestEffect1(42))
            assert(TestEffect1.run(nested.flatten).eval == "Effect1:42")

            val result = Kyo.lift(TestEffect1(5)).map(_.map(s => TestEffect1(s.length)))
            assert(TestEffect1.run(result).eval == "Effect1:9")
        }

        "multiple effects" in {
            val comp: Int < TestEffect2 < TestEffect1 =
                Kyo.lift(TestEffect1(10)).map(_.map(s => Kyo.lift(TestEffect2(s))))

            val result = comp.map(_.handle(TestEffect2.run)).handle(TestEffect1.run)
            assert(result.eval == "Effect1:10".length + 10)

            val result2 = comp.flatten.handle(TestEffect2.run).handle(TestEffect1.run)
            assert(result2.eval == "Effect1:10".length + 10)
        }

        "map" in {
            val nested: String < TestEffect1 < Any = Kyo.lift(TestEffect1(50))

            val mapped: Int < TestEffect1 = nested.map(_.map(_.length))
            assert(TestEffect1.run(mapped).eval == "Effect1:50".length)

            val mapped2 = nested.map(v => Kyo.lift(v.map(_.length))).map(_.handle(TestEffect1.run))
            assert(mapped2.eval == "Effect1:50".length)

            val mapped3 = nested.map(v => Kyo.lift(v.map(_.length))).map(v => Kyo.lift(v.handle(TestEffect1.run)))
            assert(mapped3.eval.eval == "Effect1:50".length)
        }

        "unit" in {
            val comp = Kyo.lift(TestEffect1(60)).map(_.unit).handle(TestEffect1.run)
            assert(comp.eval == ())

            val comp2 = Kyo.lift(TestEffect1(60)).map(v => v.unit.handle(TestEffect1.run))
            assert(comp2.eval == ())

            val comp3 = Kyo.lift(TestEffect1(60)).map(v => Kyo.lift(v.unit)).map(_.handle(TestEffect1.run))
            assert(comp2.eval == ())

            val comp4 = Kyo.lift(TestEffect1(60)).map(v => Kyo.lift(v.unit)).map(v => Kyo.lift(v.handle(TestEffect1.run)))
            assert(comp4.eval.eval == ())
        }

        "andThen" in {
            val comp = Kyo.lift(TestEffect1(60)).andThen(TestEffect1(70)).handle(TestEffect1.run)
            assert(comp.eval == "Effect1:70")

            val comp2 =
                Kyo.lift(TestEffect1(60)).andThen(Kyo.lift(TestEffect2("Effect")))
                    .handle(TestEffect1.run).map(_.handle(TestEffect2.run))
            assert(comp2.eval == 16)

            val comp3 =
                Kyo.lift(TestEffect1(60)).andThen(Kyo.lift(TestEffect2("Effect")))
                    .handle(TestEffect1.run).flatten.handle(TestEffect2.run)
            assert(comp3.eval == 16)
        }

        "handle" in {
            val nested: String < TestEffect1 < Any = Kyo.lift(TestEffect1(80))

            val result = nested.handle(comp =>
                TestEffect1.run(comp.flatten).map(_.length)
            )

            assert(result.eval == 10)

            val result2 = nested.handle(
                comp => TestEffect1.run(comp.flatten),
                res => res.eval.length
            )

            assert(result2 == "Effect1:80".length)
        }

        "multiple operations" in {
            def processValue(v: Int): Int < TestEffect2 < TestEffect1 =
                TestEffect1(v).map(s => Kyo.lift(TestEffect2(s + "!")))

            val input = 100
            val result = processValue(input).flatten
                .map(n => n * 2)
                .flatMap(n => TestEffect3().map(_ => n))

            val finalResult = TestEffect1.run(
                TestEffect2.run(
                    TestEffect3.run(true)(result)
                )
            )

            assert(finalResult.eval == ("Effect1:100!".length + 10) * 2)
        }

        "method returning nested computation" in {
            def compute(x: Int): String < TestEffect1 < TestEffect2 =
                TestEffect2(x.toString).map(n => Kyo.lift(TestEffect1(n)))

            val result      = compute(200).flatten
            val finalResult = TestEffect2.run(TestEffect1.run(result))

            assert(finalResult.eval == "Effect1:13")
        }

        "nested effect suspensions" in {
            val nested: Int < TestEffect2 < TestEffect1 =
                TestEffect1(1).map(_ => Kyo.lift(TestEffect2("hello")))

            val result = TestEffect1.run(nested.map(TestEffect2.run))
            assert(result.eval == 15)

            val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
            assert(result2.eval == 15)
        }

        "nested effect suspension lifted function" in {
            def f(str: String): Int < TestEffect2 = TestEffect2(str)

            def g[B](f: String => B): B < TestEffect1 =
                TestEffect1(1).map(f)

            val nested: Int < TestEffect2 < TestEffect1 = g(f)

            val result = TestEffect1.run(nested.map(TestEffect2.run))
            assert(result.eval == 19)

            val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
            assert(result2.eval == 19)
        }

        "nested effect suspension widened lifted function" in {
            def f(str: String): Int < TestEffect2 = TestEffect2(str)

            def g[B](f: String => B): B < TestEffect1 =
                val liftedF: String => B < Any = f
                TestEffect1(1).map(liftedF)

            val nested: Int < TestEffect2 < TestEffect1 = g(f)

            val result = TestEffect1.run(nested.map(TestEffect2.run))
            assert(result.eval == 19)

            val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
            assert(result2.eval == 19)
        }

        "generic lifted functions" in {
            def f(str: String): Int < TestEffect2 = TestEffect2(str)

            def g[B](f: String => B): B < TestEffect1 =
                TestEffect1(1).map(f).flatMap(_ => f("a")).andThen(f("b"))

            val nested: Int < TestEffect2 < TestEffect1 = g(f)

            val result = TestEffect1.run(nested.map(TestEffect2.run))
            assert(result.eval == 11)

            val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
            assert(result2.eval == 11)
        }

    }

    "show" - {
        "should display pure vals wrapped with inner types displayed using show" in {
            val i: Result[String, Int] < Any         = Result.succeed(23)
            val r: Render[Result[String, Int] < Any] = Render.apply
            assert(r.asString(i) == "Kyo(Success(23))")
            assert(render"$i" == "Kyo(Success(23))")
        }
    }

    // kernel2-specific coverage beyond the ported suite

    def ask: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def resolve(v: Int < Ask, answers: Int*): Int =
        var remaining = answers.toList
        ArrowEffect.handle(Tag[Ask], v)(
            [C] =>
                (input, cont) =>
                    remaining match
                        case a :: tail =>
                            remaining = tail
                            cont(a)
                        case Nil =>
                            throw new IllegalStateException("out of answers")
        ).eval
    end resolve

    def park(v: Int < Ask): Arrow[Int, Int, Ask] =
        var parked: Arrow[Int, Int, Ask] = null
        val _ = ArrowEffect.handlePartial(Tag[Ask], v)(
            [C] =>
                (input, cont) =>
                    parked = cont
                    Maybe.Absent
        )
        parked
    end park

    "eager recursion through map is stack safe" in {
        def loop(i: Int): Int < Any =
            if i == 0 then 0
            else (i: Int < Any).map(_ => loop(i - 1))
        assert(loop(1000000).eval == 0)
    }

    "eager chain evaluates during construction" in {
        assert(((1: Int < Any).map(_ + 1).map(_ * 2)).eval == 4)
    }

    "empty arrow is identity" in {
        assert(Arrow[Int](5).eval == 5)
    }

    "handler resolves a suspension synchronously" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 1000 do
            k = k.map(_ + 1)
            i += 1
        assert(resolve(k, 5) == 1005)
    }

    "handler resolves a bare suspension" in {
        assert(resolve(ask, 41) == 41)
        assert(resolve(ask.map(_ + 1), 41) == 42)
    }

    // handler installation is pure: nothing evaluates until a drive reaches the region
    "handling installs without evaluating" in {
        var ran = false
        val handled = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))(
            [C] =>
                (input, cont) =>
                    ran = true
                    cont(1)
        )
        assert(!ran)
        assert(handled.eval == 2)
        assert(ran)
    }

    "handler parks and the continuation resumes" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 1000 do
            k = k.map(_ + 1)
            i += 1
        val resumed = park(k)(7)
        assert(resumed.asInstanceOf[Int < Any].eval == 1007)
    }

    "a parked continuation is multi shot" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 10 do
            k = k.map(_ + 1)
            i += 1
        val cont = park(k)
        assert(cont(0).asInstanceOf[Int < Any].eval == 10)
        assert(cont(100).asInstanceOf[Int < Any].eval == 110)
    }

    "appending after a park does not disturb the parked continuation" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 5 do
            k = k.map(_ + 1)
            i += 1
        val cont     = park(k)
        val extended = cont.map(transform(_ * 10))
        assert(cont(0).asInstanceOf[Int < Any].eval == 5)
        assert(extended(0).asInstanceOf[Int < Any].eval == 50)
        assert(cont(1).asInstanceOf[Int < Any].eval == 6)
    }

    "mid chain suspension captures the remainder" in {
        val program = ask.map(_ + 1).map(a => ask.map(b => a + b)).map(_ * 2)
        assert(resolve(program, 10, 10) == 42)
    }

    "nested suspensions resolve in order" in {
        val program = ask.map(a => ask.map(b => ask.map(c => a * 100 + b * 10 + c)))
        assert(resolve(program, 1, 2, 3) == 123)
    }

    "handling drives deep programs at the handle site" in {
        def program(i: Int): Int < Ask =
            if i == 0 then 0
            else ask.map(_ => program(i - 1))
        val handled = ArrowEffect.handle(Tag[Ask], program(100000))(
            [C] => (input, cont) => cont(0)
        )
        assert(handled.eval == 0)
    }

    "preemption yields and the remainder resumes" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 10000 do
            k = k.map(_ + 1)
            i += 1
        val resumed = park(k)(0).asInstanceOf[Int < Any]
        var polls   = 0
        val suspended = resumed.eval(
            () =>
                polls += 1; polls == 2
            ,
            512
        )
        assert(polls == 2)
        assert(suspended.eval == 10000)
    }

    "period controls poll cadence" in {
        def parkAndResume(): Int < Any =
            var k: Int < Ask = ask
            var i            = 0
            while i < 10000 do
                k = k.map(_ + 1)
                i += 1
            park(k)(0).asInstanceOf[Int < Any]
        end parkAndResume
        var p512 = 0
        assert(parkAndResume().eval(
            () =>
                p512 += 1;
                false
            ,
            512
        ).eval == 10000)
        var p4096 = 0
        assert(parkAndResume().eval(
            () =>
                p4096 += 1;
                false
            ,
            4096
        ).eval == 10000)
        assert(p512 > p4096)
    }

    "deep resumed continuation is stack safe" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 100000 do
            k = k.map(_ + 1)
            i += 1
        assert(resolve(k, 0) == 100000)
    }

    "long append chains resolve" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 5000 do
            k = k.map(_ + 1)
            i += 1
        assert(resolve(k, 0) == 5000)
    }

    def transform(f: Int => Int): Arrow[Int, Int, Any] =
        new Arrow.Transform[Int, Int, Any]:
            def frame = Frame.derive
            def run[C, S2](v: Any, cont: Arrow[Int, C, S2]): C < (Any & S2) =
                cont(f(v.asInstanceOf[Int]))

    "handlers route by tag and nest" in {
        val ask2: Int < Ask2 =
            ArrowEffect.suspend[Any](Tag[Ask2], ())
        val program: Int < (Ask & Ask2) =
            ask.map(a => ask2.map(b => a * 10 + b))
        val inner = ArrowEffect.handle(Tag[Ask], program)(
            [C] => (input, cont) => cont(1)
        )
        val outer = ArrowEffect.handle(Tag[Ask2], inner)(
            [C] => (input, cont) => cont(2)
        )
        assert(outer.eval == 12)
    }

    "exceptions carry effect frames in the stack trace" in {
        val program: Int < Ask = ask.map { _ =>
            (1: Int < Any).map(_ => (throw new RuntimeException("boom")): Int)
        }
        // installation is pure, so the throw happens when the drive evaluates the region
        val ex =
            try
                val _ = ArrowEffect.handle(Tag[Ask], program)(
                    [C] => (input, cont) => cont(1)
                ).eval
                null
            catch case e: RuntimeException => e
        assert(ex.getMessage == "boom")
        val top = ex.getStackTrace.take(1)
        assert(top.forall(_.getClassName == "map @ PendingTest"))
        assert(top.forall(_.getFileName == "PendingTest.scala"))
    }

    "lift wraps nested computations" in {
        val inner: Int < Ask          = ask
        val nested: (Int < Ask) < Any = Kyo.lift(inner)
        val out                       = nested.eval
        assert(resolve(out.map(_ + 1), 41) == 42)
    }

    "a computation as a value survives the chain" in {
        val inner: Int < Any = (1: Int < Any).map(_ + 1)
        val program: (Int < Any) < Ask =
            ask.map(n => Kyo.lift(inner.map(_ + n)))
        val handled = ArrowEffect.handle(Tag[Ask], program)(
            [C] => (input, cont) => cont(10)
        )
        val out = handled.eval
        assert(out.eval == 12)
    }

    "identity arrow has no step" in {
        assert(Arrow[Int].step == Maybe.Absent)
    }

    "step decomposes a continuation for caller-site execution" in {
        val cont = park(ask.map(_ + 1).map(_ * 10))
        cont.step match
            case Maybe.Present(s) =>
                assert(s.asInstanceOf[AnyRef] eq cont.asInstanceOf[AnyRef])
                assert(s.head.run(3, s.next).asInstanceOf[Int < Any].eval == 40)
            case Maybe.Absent =>
                fail("expected a step")
        end match
    }

    "step wraps a lone transform" in {
        val cont = park(ask.map(_ + 5))
        cont.step match
            case Maybe.Present(s) =>
                assert(s.head.run(2, s.next).asInstanceOf[Int < Any].eval == 7)
            case Maybe.Absent =>
                fail("expected a step")
        end match
    }

    "step drive round-trips a mid-chain suspension" in {
        val cont = park(ask.map(x => ask.map(_ + x)).map(_ * 2))
        val resumed =
            cont.step match
                case Maybe.Present(s) => s.head.run(10, s.next)
                case Maybe.Absent     => fail("expected a step")
        assert(resolve(resumed.asInstanceOf[Int < Ask], 5) == 30)
    }

    "handler hosts phase 2 end to end" in {
        var remaining = List(7, 3)
        val program   = ask.map(a => ask.map(_ + a)).map(_ * 2)
        val result = ArrowEffect.handlePartial(Tag[Ask], program)(
            [C] =>
                (input, cont) =>
                    val a = remaining.head
                    remaining = remaining.tail
                    cont.step match
                        case Maybe.Present(s) => Maybe(s.head.run(a, s.next).asInstanceOf[Int < Ask])
                        case Maybe.Absent     => Maybe(Kyo.lift(a).asInstanceOf[Int < Ask])
        )
        assert(result.asInstanceOf[Int < Any].eval == 20)
    }

    "flatMap supports for-comprehensions" in {
        val v =
            for
                a <- ask
                b <- ask
            yield a + b
        assert(resolve(v, 10, 20) == 30)
    }

    "andThen sequences and keeps the second result" in {
        var order = List.empty[Int]
        val v = ask.map { a =>
            order = order :+ a
            a
        }.andThen(ask.map { b =>
            order = order :+ b
            b
        })
        assert(resolve(v, 1, 2) == 2)
        assert(order == List(1, 2))
    }

    "unit discards the result and keeps the effects" in {
        var seen = -1
        val v = ask.map { a =>
            seen = a
            a
        }.unit
        val result = ArrowEffect.handle(Tag[Ask], v)(
            [C] => (input, cont) => cont(7)
        ).eval
        assert(result == ())
        assert(seen == 7)
    }

    "handle applies a transformation" in {
        assert(ask.handle(resolve(_, 5)) == 5)
    }

    "handle chains transformations in sequence" in {
        def inc(b: => Int): Int    = b + 1
        def double(c: => Int): Int = c * 2
        assert(ask.handle(resolve(_, 3), inc, double) == 8)
    }

    "handle chains five transformations" in {
        def inc(b: => Int): Int         = b + 1
        def double(c: => Int): Int      = c * 2
        def dec(d: => Int): Int         = d - 1
        def toString(e: => Int): String = e.toString
        assert(ask.handle(resolve(_, 3), inc, double, dec, toString) == "7")
    }

    "flatten collapses a nested computation" in {
        val inner: Int < Ask          = ask
        val nested: (Int < Ask) < Any = Kyo.lift(inner)
        assert(resolve(nested.flatten, 9) == 9)
    }

end PendingTest
