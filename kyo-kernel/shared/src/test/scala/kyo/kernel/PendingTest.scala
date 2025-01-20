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
        def widen[A](v: A): A < Any = v
        val x: Int < Any < Any      = widen(10: Int < Any)
        val y: Int < Any            = x.flatten
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
        assertDoesNotCompile("val x: Int < CustomEffect = 5; x.eval")
    }

    "lift" - {

        "allows lifting pure values" in {
            val x: Int < Any = 5
            assert(x.eval == 5)
        }

        "nested computation" - {
            "generic method effect mismatch" in {
                @nowarn("msg=unused") def test1[A](v: A < Any) = v
                assertDoesNotCompile("test1(effect)")
            }
            "inference widening" in {
                assertDoesNotCompile("val _: Int < Any < Any = (1: Int < Any)")
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
                assertDoesNotCompile("""
                    val _: Int => String < Any < Any = f1
                """)
                assertDoesNotCompile("""
                    val _: (Int, Int) => String < Any < Any = f2
                """)
                assertDoesNotCompile("""
                    val _: (Int, Int, Int) => String < Any < Any = f3
                """)
                assertDoesNotCompile("""
                    val _: (Int, Int, Int, Int) => String < Any < Any = f4
                """)
            }
        }
    }

    sealed trait TestEffect extends ArrowEffect[Const[Int], Const[Int]]
    object TestEffect:
        def apply(i: Int): Int < TestEffect = ArrowEffect.suspend[Unit](Tag[TestEffect], i)
        def run[A: Flat, S](v: => A < (TestEffect & S)) =
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

        "doesn't accept nested computations" in {
            assertDoesNotCompile("def test(x: Int < Any < Any) = x.evalNow")
        }
    }

    "pipe" - {
        "applies a function to a pure value" in {
            val result = (5: Int < Any).pipe(_.map(_ + 1))
            assert(result.eval == 6)
        }

        "applies a function to an effectful value" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result                   = effect.pipe(v => TestEffect.run(v))
            assert(result.eval == 2)
        }

        "allows chaining of operations" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result = effect
                .pipe(v => v.map(_ * 2))
                .pipe(v => TestEffect.run(v))
            assert(result.eval == 4)
        }

        "works with functions that return effects" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result = effect.pipe { v =>
                TestEffect.run(v).map { x =>
                    TestEffect.run(TestEffect(1))
                }
            }
            assert(result.eval == 2)
        }

        "works with identity function" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result                   = effect.pipe(identity)
            assert(TestEffect.run(result).eval == 2)
        }

        "can produce a value instead of a computation" in {
            val result: Int = TestEffect(1).pipe(TestEffect.run).pipe(_.eval)
            assert(result == 2)
        }

        "works with two functions" in {
            val result = (5: Int < Any).pipe(
                _.map(_ + 1),
                _.map(_ * 2)
            )
            assert(result.eval == 12)
        }

        "works with three functions" in {
            val result = (5: Int < Any).pipe(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString)
            )
            assert(result.eval == "12")
        }

        "works with four functions" in {
            val result = (5: Int < Any).pipe(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length)
            )
            assert(result.eval == 2)
        }

        "works with five functions" in {
            val result = (5: Int < Any).pipe(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1)
            )
            assert(result.eval == true)
        }

        "works with six functions" in {
            val result = (5: Int < Any).pipe(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No")
            )
            assert(result.eval == "Yes")
        }
    }

    "only 'flatten' is available for nested computations" in {
        def test[S](effect: Unit < S < S) =
            assertDoesNotCompile("effect.map(_ => 1))")
            assertDoesNotCompile("effect.andThen(1)")
            assertDoesNotCompile("effect.flatMap(_ => 1)")
            assertDoesNotCompile("effect.pipe(_ => 1)")
            assertDoesNotCompile("effect.evalNow")
            assertDoesNotCompile("effect.repeat(10)")
            assertDoesNotCompile("effect.unit")
            effect.flatten
        end test
        def nest[T](v: T): T < Any =
            v
        assert(test(nest((): Unit < Any)).eval == ())
    }

    "show" - {
        "should display pure vals wrapped with inner types displayed using show" in {
            val i: Result[String, Int] < Any         = Result.success(23)
            val r: Render[Result[String, Int] < Any] = Render.apply
            assert(r.asText(i).show == "Kyo(Success(23))")
            assert(t"$i".show == "Kyo(Success(23))")
        }
    }

end PendingTest
