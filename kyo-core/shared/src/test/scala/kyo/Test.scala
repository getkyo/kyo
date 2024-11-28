package kyo

import kyo.internal.BaseKyoTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoTest[Abort[Any] & Async & Resource] with NonImplicitAssertions:

    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly extends Tag(runWhen(kyo.kernel.Platform.isJVM))
    object jsOnly  extends Tag(runWhen(kyo.kernel.Platform.isJS))

    def run(v: Future[Assertion] < (Abort[Any] & Async & Resource)): Future[Assertion] =
        import AllowUnsafe.embrace.danger
        val a = Async.run(Abort.run(Resource.run(v)).map(_.fold {
            _.getFailure match
                case ex: Throwable => throw ex
                case e             => throw new IllegalStateException(s"Test aborted with $e")
        }(identity)))
        val b = a.map(_.toFuture).map(_.flatten)
        IO.Unsafe.run(b).eval
    end run

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
