package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly extends Tag(runWhen(kyo.kernel.Platform.isJVM))
    object jsOnly  extends Tag(runWhen(kyo.kernel.Platform.isJS))

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    object WipTest extends org.scalatest.Tag("org.scalatest.Ignore")

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
