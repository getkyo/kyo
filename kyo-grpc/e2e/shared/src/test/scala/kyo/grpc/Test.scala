package kyo.grpc

import kyo.*
import kyo.internal.BaseKyoTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.exceptions.TestFailedException
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.language.implicitConversions

abstract class Test extends AsyncFreeSpec with BaseKyoTest[Async & Abort[Throwable] & Resource] with NonImplicitAssertions:

    def run(v: Future[Assertion] < (Async & Abort[Throwable] & Resource)): Future[Assertion] =
        Resource.run(v)
            .pipe(Async.run)
            .map(_.toFuture)
            .map(_.flatten)
            .pipe(IO.run)
            .eval
    end run

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
