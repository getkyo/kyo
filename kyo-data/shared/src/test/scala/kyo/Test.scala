package kyo

import kyo.internal.BaseKyoDataTest
import org.scalatest.NonImplicitAssertions
import org.scalatest.Succeeded
import org.scalatest.freespec.AsyncFreeSpec

abstract class Test extends AsyncFreeSpec, NonImplicitAssertions, BaseKyoDataTest:
    override type Assertion = org.scalatest.Assertion
    override val assertionSuccess: Assertion              = Succeeded
    override def assertionFailure(msg: String): Assertion = fail(msg)
end Test
