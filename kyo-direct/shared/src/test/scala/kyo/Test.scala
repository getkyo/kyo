package kyo

import kyo.internal.BaseKyoTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoTest[IO] with NonImplicitAssertions:

    def run(v: Future[Assertion] < IO): Future[Assertion] =
        import AllowUnsafe.embrace.danger
        IO.Unsafe.evalOrThrow(v)

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
