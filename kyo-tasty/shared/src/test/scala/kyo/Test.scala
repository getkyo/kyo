package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

/** Test base for kyo-tasty.
  *
  * Platform gating: this module gates platform-specific tests via ScalaTest tags (`taggedAs jvmOnly`,
  * `taggedAs jsOnly`, `taggedAs nativeOnly`) rather than the `runJVM` / `runJS` / `runNative` helpers
  * the CONTRIBUTING.md guide treats as canonical. The choice is deliberate. kyo-tasty has roughly thirty
  * platform-conditional test sites that all need the same ignored-on-non-matching-platform behaviour, and
  * the tag form lets each site declare its gate in one line beside the test name without wrapping the body
  * in an extra combinator. The two approaches are observationally equivalent under sbt: tagged tests are
  * reported as ignored on the non-matching platform, matching what `runJVM` would do.
  */
abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly    extends Tag(runWhen(kyo.internal.Platform.isJVM))
    object jsOnly     extends Tag(runWhen(kyo.internal.Platform.isJS))
    object nativeOnly extends Tag(runWhen(kyo.internal.Platform.isNative))

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext
end Test
