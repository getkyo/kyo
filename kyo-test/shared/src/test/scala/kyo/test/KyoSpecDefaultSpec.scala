package kyo.test

import kyo.*
import zio.Duration
import zio.duration2DurationOps
import zio.test.*
object KyoSpecDefaultSpec extends KyoSpecDefault:
    def spec: Spec[Any, Any] =
        suite("all")(
            suite("passing!")(
                test("pure") {
                    assertCompletes
                },
                test("IOs Succeed") {
                    IOs(assertCompletes)
                }
            ),
            suite("failing!")(
                test("IOs.fail") {
                    IOs.fail("Fail!").map(_ => assertCompletes)
                },
                test("IOs Succeed") {
                    Aborts[Throwable].fail(new RuntimeException("Abort!")).map(_ => assertCompletes)
                },
                test("Fibers.delay") {
                    Fibers.delay(Duration.Infinity.asScala)(assertCompletes)
                } @@ TestAspect.timeout(Duration.Zero)
            ) @@ TestAspect.failing
        ) @@ TestAspect.timed

end KyoSpecDefaultSpec
