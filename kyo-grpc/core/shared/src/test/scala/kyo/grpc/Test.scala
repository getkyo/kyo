package kyo.grpc

import kyo.*
import kyo.internal.BaseKyoCoreTest
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.exceptions.TestFailedException
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.language.implicitConversions

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    type Assertion = org.scalatest.compatible.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
