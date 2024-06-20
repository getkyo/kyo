package kyo2

import internal.BaseKyoTest
import kyo2.internal.BaseKyoTest
import kyo2.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoTest[Abort[Any] & Async & Resource] with NonImplicitAssertions:

    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly extends Tag(runWhen(kyo2.kernel.Platform.isJVM))
    object jsOnly  extends Tag(runWhen(kyo2.kernel.Platform.isJS))

    def run(v: Future[Assertion] < (Abort[Any] & Async & Resource)): Future[Assertion] =
        val a = Async.run(Abort.run(Resource.run(v)).map(_.fold(e => throw new IllegalStateException(s"Test aborted with $e"))(identity)))
        val b = a.map(_.toFuture).map(_.flatten)
        IO.run(b).eval
    end run

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
