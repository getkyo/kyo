package kyo.stats.otel

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest {

    private def runWhen(cond: => Boolean) = if (cond) "" else "org.scalatest.Ignore"
    object jvmOnly extends Tag(runWhen(kyo.internal.Platform.isJVM))
    object jsOnly  extends Tag(runWhen(kyo.internal.Platform.isJS))

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    implicit override def executionContext: ExecutionContext = Platform.executionContext
}
