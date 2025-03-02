package kyo.kernel

import kyo.*
import kyo.kernel.internal.*

class IsolateTest extends Test:

    sealed trait TestEffect1      extends ContextEffect[Int]
    sealed trait TestEffect2      extends ContextEffect[String]
    sealed trait TestEffect3      extends ContextEffect[Boolean]
    sealed trait NotContextEffect extends ArrowEffect[Const[Int], Const[Int]]

    given Isolate.Contextual[TestEffect1, Any] = Isolate.Contextual.derive
    given Isolate.Contextual[TestEffect2, Any] = Isolate.Contextual.derive
    given Isolate.Contextual[TestEffect3, Any] = Isolate.Contextual.derive

    "derive" - {
        "creates an isolate for context effects" in {
            val isolate = Isolate.Contextual.derive[TestEffect1 & TestEffect2, Any]
            assert(isolate.isInstanceOf[Isolate.Contextual[TestEffect1 & TestEffect2, Any]])
        }

        val error = "This operation requires Contextual isolation for effects"

        "fails compilation for non-context effects" in {
            typeCheckFailure("Isolate.Contextual.derive[Int, Any]")(error)
            typeCheckFailure("Isolate.Contextual.derive[String, Any]")(error)
        }

        "fails compilation for non-context effect traits" in {
            typeCheckFailure("Isolate.Contextual.derive[NotContextEffect, Any]")(error)
        }
    }

    "isolate application" - {
        "no context effect suspension" in {
            val isolate = Isolate.Contextual.derive[TestEffect1 & TestEffect2, Any]
            val effect: Context < Any = isolate.runInternal { (trace: Trace, context: Context) =>
                context
            }
            assert(effect.eval.isEmpty)
        }

        "allows access to context" in {
            val isolate = Isolate.Contextual.derive[TestEffect1, Any]
            val effect = isolate.runInternal { (trace, context) =>
                context.getOrElse[Int, TestEffect1, Int](Tag[TestEffect1], 42)
            }
            val result = ContextEffect.handle(Tag[TestEffect1], 10, _ + 1)(effect)
            assert(result.eval == 10)
        }

        "isolates runtime effect" in {
            val isolate = Isolate.Contextual.derive[TestEffect1, Any]
            val effect: Int < TestEffect1 = isolate.runInternal { (trace, context) =>
                ContextEffect.suspend(Tag[TestEffect1])
            }
            val result = ContextEffect.handle(Tag[TestEffect1], 42, _ + 1)(effect)
            assert(result.eval == 42)
        }
    }

    "nested isolates" in {
        val outerBoundary = Isolate.Contextual.derive[TestEffect1, Any]
        val innerBoundary = Isolate.Contextual.derive[TestEffect2, Any]

        val effect: Int < (TestEffect1 & TestEffect2) =
            outerBoundary.runInternal { (outerTrace, outerContext) =>
                innerBoundary.runInternal { (innerTrace, innerContext) =>
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

        val isolate = Isolate.Contextual.derive[TestEffect1, Any]
        val effect: Int < Any = isolate.runInternal { (trace, context) =>
            Isolate.restoring(trace, interceptor) {
                (10: Int < Any).map(_ + 1)
            }
        }

        assert(effect.eval == 11)
        assert(interceptorCalled)
    }

    "with non-context effect" in {
        val isolate = Isolate.Contextual.derive[TestEffect1, Any]
        val effect: Int < (TestEffect1 & NotContextEffect) = isolate.runInternal { (trace, context) =>
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
        val isolate     = Isolate.Contextual.derive[TestEffect1, Any]

        val effect: Int < TestEffect1 =
            for
                outer <- outerEffect
                inner <- isolate.runInternal { (trace, context) => innerEffect }
            yield outer + inner

        val result = ContextEffect.handle(Tag[TestEffect1], 20, _ + 1)(effect)
        assert(result.eval == 40)
    }

    "residual effects" - {
        sealed trait ResidualEffect    extends ArrowEffect[Const[Int], Const[Int]]
        sealed trait SubResidualEffect extends ResidualEffect

        "supports residual effects in S type parameter" in {
            val isolate                                            = Isolate.Contextual.derive[TestEffect1, ResidualEffect]
            val _: Isolate.Contextual[TestEffect1, ResidualEffect] = isolate
            succeed
        }

        "allows using residual effects within isolate" in {
            val isolate = Isolate.Contextual.derive[TestEffect1, ResidualEffect]
            val effect: Int < (TestEffect1 & ResidualEffect) =
                isolate.runInternal { (trace, context) =>
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

        "preserves residual effects after isolate application" in {
            val isolate = Isolate.Contextual.derive[TestEffect1, ResidualEffect]
            val effect: Int < (TestEffect1 & ResidualEffect) = isolate.runInternal { (trace, context) =>
                ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1])
            }

            val result = ContextEffect.handle(Tag[TestEffect1], 5, _ + 1)(effect)

            val finalResult = ArrowEffect.handle(Tag[ResidualEffect], result) {
                [C] => (input, cont) => cont(input * 2)
            }
            assert(finalResult.eval == 5)
        }

        "supports subclasses of residual effects" in {
            val isolate = Isolate.Contextual.derive[TestEffect1, ResidualEffect]
            val effect: Int < (TestEffect1 & SubResidualEffect) = isolate.runInternal { (trace, context) =>
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
        sealed trait IsolatedEffect    extends ContextEffect[Int] with ContextEffect.Noninheritable
        sealed trait NonIsolatedEffect extends ContextEffect[String]

        given Isolate.Contextual[NonIsolatedEffect, Any] = Isolate.Contextual.derive

        "should propagate only non-isolated effects" in {
            val isolate = Isolate.Contextual.derive[NonIsolatedEffect, Any]

            val context =
                ContextEffect.handle(Tag[IsolatedEffect], 24, _ + 1) {
                    ContextEffect.handle(Tag[NonIsolatedEffect], "test", _.toUpperCase) {
                        isolate.runInternal { (trace, context) => context }
                    }
                }.eval

            assert(!context.contains(Tag[IsolatedEffect]))
            assert(context.contains(Tag[NonIsolatedEffect]))
        }
    }

end IsolateTest
