package kyo2

import internal.BaseKyoTest
import kyo2.internal.BaseKyoTest
import kyo2.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoTest[Any] with NonImplicitAssertions:

    def run(v: Future[Assertion] < Any): Future[Assertion] = v.eval

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
