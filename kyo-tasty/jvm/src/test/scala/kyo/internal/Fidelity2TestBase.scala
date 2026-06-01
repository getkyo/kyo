package kyo.internal

import kyo.*

/** Base class for decoder-fidelity-2 tests that need cold/warm classpath parity assertions.
  *
  * Provides `coldWarmEquiv(name)(body)` which runs `body` against both a cold-decoded classpath and a warm snapshot-loaded classpath,
  * asserting that the results are structurally equal. This makes every participating test leaf sensitive to cold/warm divergence, catching the
  * F-A4-001 bug class at any leaf rather than only the dedicated INV-013 leaf.
  *
  * Subclasses live in jvm/src/test (real-classpath fidelity tests). This class is in jvm/src/test because TestClasspaths2 (which provides
  * `standardWithSnapshot`) is JVM-only: it relies on the JVM filesystem for snapshot write/read and on java.class.path discovery.
  *
  * Note on 2x wall-clock: each `coldWarmEquiv` call loads the standard classpath twice (once for cold, once for the snapshot round-trip).
  * This is the intended trade-off per prep.md Proposal 4.
  */
abstract class Fidelity2TestBase extends Test:

    /** Run `body` against both a cold classpath and a warm snapshot-loaded classpath.
      *
      * Asserts that `body(cold) == body(warm)`. Fails the test with a clear message if cold and warm diverge. This assertion is HARD: it
      * must not be weakened to succeed or skip on divergence.
      *
      * @param name
      *   The test leaf name. Registered as a test case via `name in run { ... }`.
      * @param body
      *   The property to verify. Receives a `Tasty.Classpath` and returns a value of type `A`.
      */
    protected def coldWarmEquiv[A](
        name: String
    )(body: Tasty.Classpath => A)(using CanEqual[A, A], Frame): Unit =
        name in run {
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
