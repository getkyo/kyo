package kyo.kernel

import kyo.*
import kyo.kernel.internal.*

class BoundaryTest extends Test:

    sealed trait TestEffect1      extends ContextEffect[Int]
    sealed trait TestEffect2      extends ContextEffect[String]
    sealed trait TestEffect3      extends ContextEffect[Boolean]
    sealed trait NotContextEffect extends ArrowEffect[Const[Int], Const[Int]]

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
            def addFinalizer(f: () => Unit): Unit    = ()
            def removeFinalizer(f: () => Unit): Unit = ()
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
                y <- ArrowEffect.suspend[Int](Tag[NotContextEffect], 1)
            yield x + y
        }

        val result = ContextEffect.handle(Tag[TestEffect1], 10, _ + 1) {
            ArrowEffect.handle(Tag[NotContextEffect], effect) {
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

    "residual effects" - {
        sealed trait ResidualEffect    extends ArrowEffect[Const[Int], Const[Int]]
        sealed trait SubResidualEffect extends ResidualEffect

        "supports residual effects in S type parameter" in {
            val boundary                                 = Boundary.derive[TestEffect1, ResidualEffect]
            val _: Boundary[TestEffect1, ResidualEffect] = boundary
            succeed
        }

        "allows using residual effects within boundary" in {
            val boundary = Boundary.derive[TestEffect1, ResidualEffect]
            val effect: Int < (TestEffect1 & ResidualEffect) = boundary { (trace, context) =>
                for
                    x <- ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1])
                    y <- ArrowEffect.suspend[Int](Tag[ResidualEffect], x)
                yield y
            }

            val result = ContextEffect.handle(Tag[TestEffect1], 10, _ + 1) {
                ArrowEffect.handle(Tag[ResidualEffect], effect) {
                    [C] => (input, cont) => cont(input * 2)
                }
            }

            assert(result.eval == 20)
        }

        "preserves residual effects after boundary application" in {
            val boundary = Boundary.derive[TestEffect1, ResidualEffect]
            val effect: Int < (TestEffect1 & ResidualEffect) = boundary { (trace, context) =>
                ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1])
            }

            val result = ContextEffect.handle(Tag[TestEffect1], 5, _ + 1)(effect)

            val finalResult = ArrowEffect.handle(Tag[ResidualEffect], result) {
                [C] => (input, cont) => cont(input * 2)
            }
            assert(finalResult.eval == 5)
        }

        "supports subclasses of residual effects" in {
            val boundary = Boundary.derive[TestEffect1, ResidualEffect]
            val effect: Int < (TestEffect1 & SubResidualEffect) = boundary { (trace, context) =>
                for
                    x <- ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1])
                    y <- ArrowEffect.suspend[Int](Tag[SubResidualEffect], x)
                yield y
            }

            val result = ContextEffect.handle(Tag[TestEffect1], 15, _ + 1) {
                ArrowEffect.handle(Tag[SubResidualEffect], effect) {
                    [C] => (input, cont) => cont(input * 2)
                }
            }

            assert(result.eval == 30)
        }
    }

    "context inheritance" - {
        sealed trait IsolatedEffect    extends ContextEffect[Int] with ContextEffect.Isolated
        sealed trait NonIsolatedEffect extends ContextEffect[String]

        "should propagate only non-isolated effects" in {
            val boundary = Boundary.derive[NonIsolatedEffect, Any]

            val context =
                ContextEffect.handle(Tag[IsolatedEffect], 24, _ + 1) {
                    ContextEffect.handle(Tag[NonIsolatedEffect], "test", _.toUpperCase) {
                        boundary { (trace, context) => context }
                    }
                }.eval

            assert(!context.contains(Tag[IsolatedEffect]))
            assert(context.contains(Tag[NonIsolatedEffect]))
        }
    }

end BoundaryTest
