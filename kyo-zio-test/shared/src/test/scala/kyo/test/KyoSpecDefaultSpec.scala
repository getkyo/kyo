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
                    Sync(assertCompletes)
                }
            ),
            suite("failing!")(
                test("Sync fail") {
                    Sync(throw new Exception("Fail!")).map(_ => assertCompletes)
                },
                test("Sync Succeed") {
                    Abort.fail[Throwable](new RuntimeException("Abort!")).map(_ => assertCompletes)
                },
                test("Async.delay") {
                    Async.delay(Duration.Infinity)(assertCompletes)
                } @@ TestAspect.timeout(Duration.Zero.toJava)
            ) @@ TestAspect.failing,
            suite("Gen")(
                test("check1")(
                    check(Gen.boolean) { b =>
                        assertTrue(b == b)
                    }
                ),
                test("check2")(
                    check(Gen.boolean, Gen.int) { (b, i) =>
                        assertTrue(b == b && i == i)
                    }
                ),
                test("check3")(
                    check(Gen.boolean, Gen.int, Gen.string) { (b, i, s) =>
                        assertTrue(b == b && i == i && s == s)
                    }
                ),
                test("checkKyo")(
                    check(Gen.boolean) { b =>
                        Sync(assertTrue(b == b))
                    }
                )
            )
        ) @@ TestAspect.timed

end KyoSpecDefaultSpec
