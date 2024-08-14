package kyo.kernel

import kyo.*
import kyo.Tag
import kyo.Test

class BoundaryTest extends Test:

    sealed trait TestEffect1      extends ContextEffect[Int]
    sealed trait TestEffect2      extends ContextEffect[String]
    sealed trait TestEffect3      extends ContextEffect[Boolean]
    sealed trait NotContextEffect extends Effect[Const[Int], Const[Int]]

    "apply" - {
        "creates a boundary for context effects" in {
            val boundary = Boundary.derive[TestEffect1 & TestEffect2, Any]
            assert(boundary.isInstanceOf[Boundary[TestEffect1 & TestEffect2, Any]])
        }

        "fails compilation for non-context effects" in {
            assertDoesNotCompile("Boundary.derive[Int, Any]")
            assertDoesNotCompile("Boundary.derive[String, Any]")
        }

        "fails compilation for non-context effect traits" in {
            assertDoesNotCompile("Boundary.derive[NotContextEffect, Any]")
        }
    }

    "boundary application" - {
        "no context effect suspension" in {
            val boundary = Boundary.derive[TestEffect1 & TestEffect2, Any]
            val effect: Context < Any = boundary { (trace: Trace, context: Context) =>
                context
            }
            assert(effect.eval.isEmpty)
        }

        "allows access to context" in {
            val boundary = Boundary.derive[TestEffect1, Any]
            val effect = boundary { (trace, context) =>
                context.getOrElse[Int, TestEffect1, Int](Tag[TestEffect1], 42)
            }
            val result = ContextEffect.handle(Tag[TestEffect1], 10, _ + 1)(effect)
            assert(result.eval == 10)
        }

        "isolates runtime effect" in {
            val boundary = Boundary.derive[TestEffect1, Any]
            val effect: Int < TestEffect1 = boundary { (trace, context) =>
                ContextEffect.suspend(Tag[TestEffect1])
            }
            val result = ContextEffect.handle(Tag[TestEffect1], 42, _ + 1)(effect)
            assert(result.eval == 42)
        }
    }

    "nested boundaries" in {
        val outerBoundary = Boundary.derive[TestEffect1, Any]
        val innerBoundary = Boundary.derive[TestEffect2, Any]

        val effect: Int < (TestEffect1 & TestEffect2) = outerBoundary { (outerTrace, outerContext) =>
            innerBoundary { (innerTrace, innerContext) =>
                for
                    x <- ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1])
                    y <- ContextEffect.suspend[String, TestEffect2](Tag[TestEffect2])
                yield x + y.length
            }
        }

        val result = ContextEffect.handle(Tag[TestEffect1], 10, _ + 1) {
            ContextEffect.handle(Tag[TestEffect2], "test", _.toUpperCase)(effect)
        }
        assert(result.eval == 14)
    }

    "restoring" in {
        var interceptorCalled = false
        val interceptor = new Safepoint.Interceptor:
            def addEnsure(f: () => Unit): Unit    = ()
            def removeEnsure(f: () => Unit): Unit = ()
            def enter(frame: Frame, value: Any): Boolean =
                interceptorCalled = true
                true
            def exit(): Unit = ()

        val boundary = Boundary.derive[TestEffect1, Any]
        val effect: Int < Any = boundary { (trace, context) =>
            Boundary.restoring(trace, interceptor) {
                (10: Int < Any).map(_ + 1)
            }
        }

        assert(effect.eval == 11)
        assert(interceptorCalled)
    }

    "with non-context effect" in {
        val boundary = Boundary.derive[TestEffect1, Any]
        val effect: Int < (TestEffect1 & NotContextEffect) = boundary { (trace, context) =>
            for
                x <- ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1])
                y <- Effect.suspend[Int](Tag[NotContextEffect], 1)
            yield x + y
        }

        val result = ContextEffect.handle(Tag[TestEffect1], 10, _ + 1) {
            Effect.handle(Tag[NotContextEffect], effect) {
                [C] => (input, cont) => cont(input + 1)
            }
        }

        assert(result.eval == 12)
    }

    "preserves outer runtime effects" in {
        val outerEffect = ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1])
        val innerEffect = ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1])
        val boundary    = Boundary.derive[TestEffect1, Any]

        val effect: Int < TestEffect1 =
            for
                outer <- outerEffect
                inner <- boundary { (trace, context) => innerEffect }
            yield outer + inner

        val result = ContextEffect.handle(Tag[TestEffect1], 20, _ + 1)(effect)
        assert(result.eval == 40)
    }

end BoundaryTest
