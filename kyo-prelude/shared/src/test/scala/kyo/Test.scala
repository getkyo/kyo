package kyo

import kyo.internal.BaseKyoKernelTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.compiletime.testing.typeCheckErrors
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoKernelTest[Abort[Throwable]]:

    def run(v: Future[Assertion] < Abort[Throwable])(using Frame): Future[Assertion] =
        Abort.run(v).eval.getOrThrow

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
