package kyo

import kyo.internal.BaseKyoKernelTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoKernelTest[Any] with NonImplicitAssertions:

    def run(v: Future[Assertion] < Any)(using Frame): Future[Assertion] = v.eval

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
