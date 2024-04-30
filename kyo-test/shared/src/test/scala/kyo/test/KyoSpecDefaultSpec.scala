package kyo.test

import kyo.*
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
                    Aborts.fail[Throwable](new RuntimeException("Abort!")).map(_ => assertCompletes)
                },
                test("Fibers.delay") {
                    Fibers.delay(Duration.Infinity)(assertCompletes)
                } @@ TestAspect.timeout(Duration.Zero.toJava)
            ) @@ TestAspect.failing
        ) @@ TestAspect.timed

end KyoSpecDefaultSpec
