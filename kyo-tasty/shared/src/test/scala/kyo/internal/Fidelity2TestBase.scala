package kyo.internal

import kyo.*

/** Base class for decoder-fidelity-2 tests that need cold/warm classpath parity assertions.
  *
  * Provides `coldWarmEquiv(name)(body)` which runs `body` against both a cold-decoded classpath and a warm snapshot-loaded classpath,
  * asserting that the results are structurally equal. This makes every participating test leaf sensitive to cold/warm divergence, catching the
  * F-A4-001 bug class at any leaf rather than only the dedicated INV-013 leaf.
  *
  * On JVM the `coldWarmEquiv` leaf loads the standard classpath twice (once for cold, once for the snapshot round-trip) and asserts
  * equality. On JS and Native the leaf is skipped via the `jvmOnly` tag because `TestClasspaths2.standardWithSnapshot` relies on JVM
  * filesystem access (java.nio.file.Files, JvmFileSource) which is not available on those platforms.
  *
  * Subclasses live in shared/src/test and run on JVM, JS, and Native.
  *
  * Scaladoc: 8-35 lines.
  */
abstract class Fidelity2TestBase extends Test:

    /** Run `body` against both a cold classpath and a warm snapshot-loaded classpath.
      *
      * Asserts that `body(cold) == body(warm)`. On JVM: loads the standard classpath twice (cold and warm snapshot round-trip) and asserts
      * structural equality. On JS/Native: the leaf is skipped via the `jvmOnly` tag.
      *
      * @param name
      *   The test leaf name.
      * @param body
      *   The property to verify. Receives a `Tasty.Classpath` and returns a value of type `A`.
      */
    protected def coldWarmEquiv[A](
        name: String
    )(body: Tasty.Classpath => A)(using CanEqual[A, A], Frame): Unit =
        name taggedAs jvmOnly in run {
            import AllowUnsafe.embrace.danger
            TestClasspaths2.standardWithSnapshot().map: (cold, warm) =>
                val coldResult = body(cold)
                val warmResult = body(warm)
                assert(
                    coldResult == warmResult,
                    s"Cold/warm divergence for '$name': cold=$coldResult warm=$warmResult"
                )
                succeed
        }
    end coldWarmEquiv

end Fidelity2TestBase
