package kyo.test.interop

import kyo.*
import scala.concurrent.duration.Duration
import scala.language.postfixOps
import zio.ZIO
import zio.duration2DurationOps
import zio.durationInt
import zio.test.*

object zioSpec extends ZIOSpecDefault:

    case object TestFail extends Throwable("Fail!")
    def spec = suite("ZIO.fromKyoFiber")(
        test("ZIO.timeout") {
            val kyoNever = ZIO.fromKyoFiber { IOs.run(Fibers.run(Fibers.sleep(Duration.Inf))) }
            val message  = "Successful interrupt!"

            for
                result <- kyoNever.timeoutFail(TestTimeoutException(message))(10.millis).either
            yield assertTrue(result.is(_.left).getMessage == message)
        },
        test("completes") {
            for
                result <- ZIO.fromKyoFiber {
                    KyoApp.runFiber(Fibers.delay(10.millis.asScala)(Fibers.init(IOs(21 * 2)).map(_.get)))
                }.flatMap(ZIO.fromTry)
            yield assertTrue(result == 42)
        },
        test("Fiber.value") {
            for
                result <- ZIO.fromKyoFiber {
                    Fiber.value(true)
                }
            yield assertTrue(result)
        },
        test("Fiber.fail") {
            for
                result <- ZIO.fromKyoFiber {
                    Fiber.fail(TestFail)
                }.either
            yield assertTrue(result.is(_.left).getMessage == TestFail.getMessage)
        }
    ) @@ TestAspect.withLiveClock @@ TestAspect.timed
end zioSpec
