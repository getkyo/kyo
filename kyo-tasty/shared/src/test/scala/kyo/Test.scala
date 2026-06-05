package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

/** Test base for kyo-tasty.
  *
  * Cross-platform default: every leaf runs on JVM, JS, and Native. When a leaf genuinely requires a
  * JVM-only primitive, use `runJVM { ... }` from BaseKyoKernelTest. Do NOT use `taggedAs jvmOnly`; the
  * canonical form is `runJVM` (BIND-010 / Q-012).
  *
  * The `jvmOnly`, `jsOnly`, and `nativeOnly` tag objects below are retained for the few remaining
  * tag-based leaves (e.g. `taggedAs jsOnly`, `taggedAs nativeOnly` in FallbackBindingTest).
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
