package kyo2.kernel

import kyo.Tag
import kyo2.Test

class BoundaryTest extends Test:
    sealed trait TestEffect       extends ContextEffect[Int]
    sealed trait AnotherEffect    extends ContextEffect[String]
    sealed trait NotRuntimeEffect extends Effect[Const[Int], Const[Int]]

    "isolates runtime effect" in {
        val a = ContextEffect.suspend(Tag[TestEffect])
        val b: Int < TestEffect =
            Boundary(a) { cont =>
                val _: Int < Any = cont
                cont.map(_ * 2)
            }
        val c = ContextEffect.handle(Tag[TestEffect], 2, _ => 3)(b)
        assert(c.eval == 4)
    }

    "continuation can be evaluated" in {
        var called = 0
        val a =
            ContextEffect.suspend(Tag[TestEffect]).map { v =>
                called += 1
                v
            }
        val b: Int < TestEffect =
            Boundary(a) { cont =>
                assert(called == 0)
                val _: Int < Any = cont
                assert(called == 0)
                val r = cont.map(_ * 2).eval
                assert(called == 1)
                r
            }
        val c = ContextEffect.handle(Tag[TestEffect], 2, _ => 3)(b)
        assert(c.eval == 4)
        assert(called == 1)
    }

    "preserve outer runtime effects" in {
        val outerEffect = ContextEffect.suspend(Tag[TestEffect])
        val innerEffect = ContextEffect.suspend(Tag[TestEffect])
        val result =
            for
                outer <- outerEffect
                inner <- Boundary(innerEffect) { isolatedEffect =>
                    isolatedEffect.map(_ * 2)
                }
            yield outer + inner
        val handled = ContextEffect.handle(Tag[TestEffect], 20, _ => 20)(result)
        assert(handled.eval == 60)
    }

    "nested boundaries" in {
        val effect: Int < TestEffect = ContextEffect.suspend(Tag[TestEffect])
        val result: Int < TestEffect =
            Boundary(effect) { outer =>
                Boundary(outer) { inner =>
                    inner.map(_ * 2)
                }
            }
        val handled: Int < Any = ContextEffect.handle(Tag[TestEffect], 10, _ => 21)(result)
        assert(handled.eval == 20)
    }

    "two effects" in {
        val effect1 = ContextEffect.suspend[Int, TestEffect](Tag[TestEffect])
        val effect2 = ContextEffect.suspend[String, AnotherEffect](Tag[AnotherEffect])

        val effect: String < (TestEffect & AnotherEffect) =
            for
                i <- effect1
                s <- effect2
            yield s"$i-$s"

        val result = Boundary(effect) { isolated =>
            val _: String < Any = isolated
            isolated.map(_.toUpperCase)
        }

        val handled = ContextEffect.handle(Tag[TestEffect], 10, _ => 42) {
            ContextEffect.handle(Tag[AnotherEffect], "default", _.reverse)(result)
        }

        assert(handled.eval == "10-DEFAULT")
    }
end BoundaryTest
