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
        v.pipe(
            Resource.run,
            Abort.recover[Any] {
                case ex: Throwable => throw ex
                case e             => throw new IllegalStateException(s"Test aborted with $e")
            },
            Async.run,
            _.map(_.toFuture).map(_.flatten),
            IO.Unsafe.evalOrThrow
        )
    end run

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    override given executionContext: ExecutionContext = Platform.executionContext
end Test

object bug:
    val result: Result.Error[Any | Throwable] = Result.Fail(1)
    result.getFailure match
        case v: Throwable => println("exception " + v)
        case v            => println("value " + v)
end bug
