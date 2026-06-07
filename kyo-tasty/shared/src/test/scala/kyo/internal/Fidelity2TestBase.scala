package kyo.internal

import kyo.*

/** Base class for decoder-fidelity-2 tests that need cold/warm classpath parity assertions.
  *
  * Provides `coldWarmEquiv(name)(body)` which runs `body` against both a cold-decoded classpath and a warm snapshot-loaded classpath,
  * asserting that the results are structurally equal. This makes every participating test leaf sensitive to cold/warm divergence, catching the
  *   bug class at any leaf rather than only the dedicated INV-013 leaf.
  *
  * The `coldWarmEquiv` leaf uses `TestClasspaths2.withSnapshotInMemory` which works on all three platforms (JVM, JS, Native). On JVM, JS,
  * and Native the cold load uses the embedded fixture set; the in-memory MemoryFileSource round-trip verifies snapshot serialization and
  * deserialization without requiring filesystem access.
  *
  * Subclasses live in shared/src/test and run on JVM, JS, and Native.
  *
  * Scaladoc: 8-35 lines.
  */
abstract class Fidelity2TestBase extends kyo.test.Test[Any]:

    /** Run `body` against both a cold classpath and a warm snapshot-loaded classpath.
      *
      * Asserts that `body(cold) == body(warm)`. Uses `TestClasspaths2.withSnapshotInMemory` which works cross-platform (JVM, JS, Native)
      * via a MemoryFileSource. Both cold and warm classpaths are built from the embedded fixture set on all platforms.
      *
      * @param name
      *   The test leaf name.
      * @param body
      *   The property to verify. Receives a `Tasty.Classpath` and returns a value of type `A`.
      */
    protected def coldWarmEquiv[A](
        name: String
    )(body: Tasty.Classpath => A)(using CanEqual[A, A], Frame): Unit =
        name in {
            TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
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
