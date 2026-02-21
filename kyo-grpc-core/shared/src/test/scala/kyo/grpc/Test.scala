package kyo.grpc

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalactic.TripleEquals
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with TripleEquals with BaseKyoCoreTest:

    type Assertion = org.scalatest.compatible.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
