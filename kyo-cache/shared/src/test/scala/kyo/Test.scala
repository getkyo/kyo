package kyo

import kyo.internal.BaseKyoTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoTest[Async] with NonImplicitAssertions:

    def run(v: Future[Assertion] < Async): Future[Assertion] =
        import AllowUnsafe.embrace.danger
        Async.run(v).map(_.toFuture).pipe(IO.run).eval.flatten

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
