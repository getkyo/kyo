package kyo

import kyo.internal.BaseKyoTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoTest[Async & Abort[Throwable]] with NonImplicitAssertions:

    def run(v: Future[Assertion] < (Async & Abort[Throwable])): Future[Assertion] =
        IO.run(Async.run(v).map(_.toFuture)).eval.flatten

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
