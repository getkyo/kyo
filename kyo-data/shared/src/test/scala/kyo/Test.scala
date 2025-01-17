package kyo

import kyo.internal.BaseKyoDataTest
import org.scalatest.Assertion
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.compiletime.testing.typeCheckErrors
import scala.util.Try

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoDataTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)
end Test
