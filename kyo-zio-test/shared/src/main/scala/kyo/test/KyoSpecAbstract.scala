package kyo.test

import kyo.*
import scala.language.postfixOps
import zio.EnvironmentTag
import zio.Trace
import zio.ZIO
import zio.ZLayer
import zio.internal.stacktracer.SourceLocation
import zio.test.*

abstract class KyoSpecAbstract[S] extends ZIOSpecAbstract:
    override type Environment = Any
    override val environmentTag: EnvironmentTag[Any] = EnvironmentTag[Environment]
    override def bootstrap: ZLayer[Any, Any, Any]    = ZLayer.empty

    // copied from `TestAnnotation` as it's `private[zio]`
    // https://github.com/zio/zio/blob/series/2.x/test/shared/src/main/scala/zio/test/TestAnnotation.scala#L91-L92
    private val trace: TestAnnotation[List[SourceLocation]] =
        TestAnnotation("trace", List.empty, _ ++ _)

    def spec: Spec[Environment, Any]

    protected def run[In](v: => In < S)(using Frame): ZIO[Environment, Throwable, In]

    final def test[In](label: String)(assertion: => In)(using
        tc: TestConstructor[Nothing, In],
        sl: SourceLocation,
        tr: Trace
    ): tc.Out =
        tc(label)(assertion)

    final def suite[In](label: String)(specs: In*)(using
        sc: SuiteConstructor[In],
        sl: SourceLocation,
        tr: Trace
    ): Spec[sc.OutEnvironment, sc.OutError] =
        zio.test.suite(label)(specs*)

    given KyoTestConstructor[S1 >: S, A <: TestResult](using Frame): TestConstructor.WithOut[Any, A < S1, Spec[Any, Throwable]] =
        new TestConstructor[Any, A < S1]:
            type Out = Spec[Any, Throwable]
            def apply(
                label: String
            )(assertion: => A < S1)(using sl: SourceLocation, tr: Trace): Out =
                Spec.labeled(
                    label,
                    Spec
                        .test(ZTest(label, run[A](assertion)), TestAnnotationMap.empty)
                        .annotate(trace, sl :: Nil)
                )

    given KyoCheckConstructor[S1 >: S, A <: TestResult](using Frame): CheckConstructor.WithOut[Any, A < S1, Any, Throwable] =
        new CheckConstructor[Any, A < S1]:
            type OutEnvironment = Any
            type OutError       = Throwable
            def apply(input: => A < S1)(using Trace): ZIO[OutEnvironment, OutError, TestResult] = run(input)

end KyoSpecAbstract
