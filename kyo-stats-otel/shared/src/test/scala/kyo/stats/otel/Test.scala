package kyo.stats.otel

import kyo.*
import kyo.internal.BaseKyoTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoTest[IO] with NonImplicitAssertions {

    def run(v: Future[Assertion] < IO): Future[Assertion] = {
        import AllowUnsafe.embrace.danger
        IO.run(v).eval
    }

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    implicit override def executionContext: ExecutionContext = Platform.executionContext
}
