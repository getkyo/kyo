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
                    IO(assertCompletes)
                }
            ),
            suite("failing!")(
                test("IO fail") {
                    IO(throw new Exception("Error!")).map(_ => assertCompletes)
                },
                test("IO Succeed") {
                    Abort.error[Throwable](new RuntimeException("Abort!")).map(_ => assertCompletes)
                },
                test("Async.delay") {
                    Async.delay(Duration.Infinity)(assertCompletes)
                } @@ TestAspect.timeout(Duration.Zero.toJava)
            ) @@ TestAspect.failing
        ) @@ TestAspect.timed

end KyoSpecDefaultSpec
