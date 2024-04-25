package kyoTest

import kyo.*
import kyo.KyoApp.Effects
import scala.concurrent.Future
import scala.concurrent.duration.*
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

    def run[In](v: => In < S)(using Flat[In < S]): ZIO[Environment, Throwable, In]

    def test[In <: TestResult](label: String)(assertion: => In < S)(using
        fl: Flat[In < S],
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

abstract class KyoSpecDefault extends KyoSpecAbstract[KyoApp.Effects]:
    final override def run[In](v: => In < KyoApp.Effects)(using Flat[In < KyoApp.Effects]): ZIO[Environment, Throwable, In] =
        ZIO.fromFuture { implicit ec => IOs.run(KyoApp.runFiber(timeout)(v).toFuture).map(_.get) }

    def timeout: Duration = if Platform.isDebugEnabled then Duration.Inf else 5.seconds

    def spec: Spec[Any, Any]

end KyoSpecDefault

object ExampleSpec extends KyoSpecDefault:
    def spec: Spec[Any, Throwable] =
        suite("suite!")(
            test("pure") {
                assertCompletes
            },
            test("IOs Succeed") {
                IOs(assertCompletes)
            },
            test("IOs Fail") {
                for
                    _ <- IOs.fail("ERROR!")
                yield assertCompletes
            } @@ TestAspect.failing,
            test("Aborts!") {
                for
                    _ <- Aborts[Throwable].fail(new RuntimeException("Aborts!"))
                yield assertCompletes
            } @@ TestAspect.failing,
            test("Fibers.sleep") {
                for
                    _ <- Fibers.sleep(Duration.Inf)
                yield assertCompletes
            } @@ TestAspect.timeout(zio.Duration.Zero) @@ TestAspect.ignore
        )
end ExampleSpec
