package kyoTest

import kyo.*
import kyo.internal.BaseKyoTest
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

abstract class KyoTest extends AsyncFreeSpec with BaseKyoTest with NonImplicitAssertions {

    type Assertion = org.scalatest.compatible.Assertion
    def success = succeed

    implicit override def executionContext: ExecutionContext = kyo.internal.Platform.executionContext
}
