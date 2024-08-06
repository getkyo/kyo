package kyo.kernel

import kyo.Maybe
import kyo.Tag
import kyo.Test
import kyo.kernel.*

class PendingTest extends Test:

    "lift" in {
        val x: Int < Any = 5
        assert(x.eval == 5)
    }

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

    "repeat" in {
        var counter       = 0
        val x: Unit < Any = Effect.defer { counter += 1 }
        x.repeat(3).eval
        assert(counter == 3)
    }

    "eval" in {
        val x: Int < Any = 10
        assert(x.eval == 10)
    }

    "eval should not compile for pending effects" in {
        trait CustomEffect extends Effect[Const[Unit], Const[Unit]]
        assertDoesNotCompile("val x: Int < CustomEffect = 5; x.eval")
    }

    "lift" - {

        sealed trait TestEffect extends Effect[Const[Unit], Const[Unit]]
        val effect: Unit < TestEffect = Effect.suspend[Any](Tag[TestEffect], ())

        "allows lifting pure values" in {
            val x: Int < Any = 5
            assert(x.eval == 5)
        }

        "prevents lifting nested kyo computations" - {
            "method effect mismatch" in {
                def test1(v: Int < Any) = v.map(_ + 1)
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

    sealed trait TestEffect extends Effect[Const[Int], Const[Int]]

    def testEffect(i: Int): Int < TestEffect =
        Effect.suspend[Int](Tag[TestEffect], i)

    "evalNow" - {
        "returns Defined for pure values" in {
            val x: Int < Any = 5
            assert(x.evalNow == Maybe(5))
        }

        "returns Empty for suspended computations" in {
            val x: Int < TestEffect = testEffect(5)
            assert(x.evalNow == Maybe.empty)
        }

        "returns Defined for nested pure values" in {
            val x: Int < Any < Any = <(1: Int < Any)
            assert(x.evalNow.flatMap(_.evalNow) == Maybe(1))
        }
    }

end PendingTest
