package kyo2.kernel

import kyo.Tag
import kyo2.Maybe
import kyo2.Test
import kyo2.kernel.*

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

    "evalPartial" - {
        "evaluates pure values" in {
            val x: Int < Any = 5
            val result = x.evalPartial(new Safepoint.Interceptor:
                def enter(frame: Frame, value: Any) = true
                def exit()                          = ()
            )
            assert(result.eval == 5)
        }

        "suspends at effects" in {
            val x: Int < TestEffect = testEffect(5).map(_ + 1)
            val result = x.evalPartial(new Safepoint.Interceptor:
                def enter(frame: Frame, value: Any) = true
                def exit()                          = ()
            )
            assert(result.evalNow.isEmpty)
        }

        "respects the interceptor" in {
            var called       = false
            val x: Int < Any = Effect.defer(5)
            val result = x.evalPartial(new Safepoint.Interceptor:
                def enter(frame: Frame, value: Any) =
                    called = true; false
                def exit() = ()
            )
            assert(called)
            assert(result.evalNow.isEmpty)
        }

        "evaluates nested suspensions" in {
            val x: Int < Any = Effect.defer(Effect.defer(5))
            val result = x.evalPartial(new Safepoint.Interceptor:
                def enter(frame: Frame, value: Any) = true
                def exit()                          = ()
            )
            assert(result.eval == 5)
        }
    }
end PendingTest
