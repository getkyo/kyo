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

    def run[In: Flat](v: => In < S): ZIO[Environment, Throwable, In]

    def test[In <: TestResult: Flat](label: String)(assertion: => In < S)(using
        sl: SourceLocation,
        tr: Trace
    ): Spec[Any, Throwable] =
        Spec.labeled(
            label,
            Spec
                .test(ZTest(label, run(assertion)), TestAnnotationMap.empty)
                .annotate(trace, sl :: Nil)
        )

    def suite[In](label: String)(specs: In*)(using
        sc: SuiteConstructor[In],
        sl: SourceLocation,
        tr: Trace
    ): Spec[sc.OutEnvironment, sc.OutError] =
        zio.test.suite(label)(specs*)

end KyoSpecAbstract
