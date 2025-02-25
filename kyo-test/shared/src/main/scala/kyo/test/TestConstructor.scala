package kyo.test

import kyo.*
// TODO: Verify disableAutoTrace functionality in Kyo (previously imported from zio.stacktracer.TracingImplicits.disableAutoTrace)

trait TestConstructor[-Environment, In]:
    type Out <: Spec[Environment, Any]
    def apply(label: String)(assertion: => In)(implicit position: FramePosition, trace: Trace): Out

object TestConstructor extends TestConstructorLowPriority1:
    type WithOut[Environment, In, Out0] = TestConstructor[Environment, In] { type Out = Out0 }

    implicit def AssertConstructor[A <: TestResult]: TestConstructor.WithOut[Any, A, Spec[Any, Nothing]] =
        new TestConstructor[Any, A]:
            type Out = Spec[Any, Nothing]
            def apply(label: String)(
                assertion: => A
            )(implicit position: FramePosition, trace: Trace): Spec[Any, Nothing] =
                // Converted from Kyo.succeed(assertion) - pure values are auto-lifted in Kyo
                test(label)(assertion)
end TestConstructor

trait TestConstructorLowPriority1 extends TestConstructorLowPriority2:

    implicit def AssertKyoConstructor[R, E, A <: TestResult]: TestConstructor.WithOut[R, A < Env[R] & Abort[E], Spec[R, E]] =
        new TestConstructor[R, A < Env[R] & Abort[E]]:
            type Out = Spec[R, E]
            def apply(
                label: String
            )(assertion: => A < Env[R] & Abort[E])(implicit position: FramePosition, trace: Trace): Spec[R, E] =
                Spec.labeled(
                    label,
                    Spec
                        .test(
                            ZTest(label, assertion),
                            TestAnnotationMap.empty
                        ) // TODO: Convert ZTest to corresponding Kyo test executor if available
                        .annotate(TestAnnotation.trace, position :: Nil)
                )
end TestConstructorLowPriority1

trait TestConstructorLowPriority2 extends TestConstructorLowPriority3:

    implicit def AssertZSTMConstructor[R, E, A <: TestResult]: TestConstructor.WithOut[R, ZSTM[R, E, A], Spec[R, E]] =
        new TestConstructor[R, ZSTM[R, E, A]]:
            type Out = Spec[R, E]
            def apply(label: String)(
                assertion: => ZSTM[R, E, A]
            )(implicit position: FramePosition, trace: Trace): Spec[R, E] =
                // TODO: Convert ZSTM commit to use Kyo effect system if available
                test(label)(assertion.commit)
end TestConstructorLowPriority2

trait TestConstructorLowPriority3:

    implicit def AssertEitherConstructor[E, A <: TestResult]: TestConstructor.WithOut[Any, Either[E, A], Spec[Any, E]] =
        new TestConstructor[Any, Either[E, A]]:
            type Out = Spec[Any, E]
            def apply(label: String)(
                assertion: => Either[E, A]
            )(implicit position: FramePosition, trace: Trace): Spec[Any, E] =
                // Converted from Kyo.fromEither(assertion) to Kyo.fromEither(assertion)
                test(label)(Kyo.fromEither(assertion))
end TestConstructorLowPriority3
