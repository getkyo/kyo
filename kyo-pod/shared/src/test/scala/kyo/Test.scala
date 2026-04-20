package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    override def timeout = 60.seconds

    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly         extends Tag(runWhen(kyo.internal.Platform.isJVM))
    object jsOnly          extends Tag(runWhen(kyo.internal.Platform.isJS))
    object containerOnly   extends Tag(runWhen(ContainerRuntime.available.nonEmpty))
    object httpBackendOnly extends Tag(runWhen(true))

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
