package kyo.kernel

import kyo.*
import kyo.kernel.*
import scala.annotation.nowarn

class PendingTest extends Test:

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

        "accepts nested computations" in {
            Kyo.lift(TestEffect(1)).evalNow match
                case Absent => fail()
                case Present(v) =>
                    TestEffect.run(v).evalNow match
                        case Absent     => fail()
                        case Present(v) => assert(v == 2)
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
    }

    "show" - {
        "should display pure vals wrapped with inner types displayed using show" in {
            val i: Result[String, Int] < Any         = Result.succeed(23)
            val r: Render[Result[String, Int] < Any] = Render.apply
            assert(r.asText(i).show == "Kyo(Success(23))")
            assert(t"$i".show == "Kyo(Success(23))")
        }
    }

end PendingTest
