package kyoTest

import kyo.*
import kyo.KyoApp.Effects
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.language.postfixOps
import zio.ZIO
import zio.ZLayer
import zio.internal.stacktracer.SourceLocation
import zio.test.*

abstract class KyoTestZIO[S] extends ZIOSpecAbstract:
    final val environmentTag: zio.EnvironmentTag[Any] = zio.EnvironmentTag[Any]

    private val trace: TestAnnotation[List[SourceLocation]] =
        TestAnnotation("trace", List.empty, _ ++ _)

    override type Environment = Any
    override def bootstrap: ZLayer[Any, Any, Any] = ZLayer.empty

    def spec: Spec[Any, Any]
    def timeout: Duration = Duration.Inf
    def run[In](v: In < S)(using ExecutionContext, Flat[In < S]): Future[In]

    def test[In <: TestResult](label: String)(assertion: In < S)(
        using
        fl: Flat[In < S],
        sl: SourceLocation,
        tr: zio.Trace
    ): Spec[Any, Throwable] =
        Spec.labeled(
            label,
            Spec
                .test(ZTest(label, ZIO.fromFuture { implicit ec => run(assertion) }), TestAnnotationMap.empty)
                .annotate(trace, sl :: Nil)
        )

    def suite[In](label: String)(specs: In*)(using
        suiteConstructor: SuiteConstructor[In],
        sourceLocation: SourceLocation,
        trace: zio.Trace
    ): Spec[suiteConstructor.OutEnvironment, suiteConstructor.OutError] =
        zio.test.suite(label)(specs*)

end KyoTestZIO

abstract class KyoTestDefault extends KyoTestZIO[KyoApp.Effects]:
    final override def run[In](v: In < KyoApp.Effects)(using ExecutionContext, Flat[In < KyoApp.Effects]): Future[In] =
        IOs.run(KyoApp.runFiber(timeout)(IOs(v)).toFuture).map(_.get)

object ExampleSpec extends KyoTestDefault:
    def spec: Spec[Any, Throwable] =
        suite("suite!")(
            test("pure") {
                assertTrue(true)
            },
            test("IOs Succeed") {
                IOs(assertTrue(true))
            },
            test("IOs Fail") {
                for
                    _ <- IOs.fail("ERROR!")
                yield assertTrue(true)
            } @@ TestAspect.failing,
            test("Aborts!") {
                for
                    _ <- Aborts[Throwable].fail(new RuntimeException("Aborts!"))
                yield assertTrue(true)
            } @@ TestAspect.failing
        )
end ExampleSpec
