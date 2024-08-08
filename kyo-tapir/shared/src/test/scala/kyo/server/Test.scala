package kyo

import kyo.internal.BaseKyoTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoTest[Async & Abort[String | Closed] & Resource] with NonImplicitAssertions:

    def run(v: Future[Assertion] < (Async & Abort[String | Closed] & Resource)): Future[Assertion] =
        Abort.run[Any](v)
            .map(_.fold(e => throw new Exception("Test failed with " + e))(identity))
            .pipe(Resource.run)
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
