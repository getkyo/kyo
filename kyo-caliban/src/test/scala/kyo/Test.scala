package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with BaseKyoCoreTest with NonImplicitAssertions:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext

    def runZIO[A](v: zio.ZIO[Any, Any, A]): Future[A] =
        zio.Unsafe.unsafe(implicit u =>
            Future.successful(zio.Runtime.default.unsafe.run(v).getOrThrowFiberFailure())
        )
end Test
