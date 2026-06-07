package kyo.internal

import kyo.*

/** Base class for fidelity tests that need cold/warm classpath parity assertions.
  *
  * Provides `coldWarmEquiv(name)(body)` which runs `body` against both a cold-decoded classpath
  * and a warm snapshot-loaded classpath and asserts structural equality. Uses
  * `TestClasspaths2.withSnapshotInMemory` which works cross-platform (JVM, JS, Native) via
  * MemoryFileSource without filesystem access.
  */
abstract class Fidelity2TestBase extends kyo.test.Test[Any]:

    /** Run `body` against both a cold classpath and a warm snapshot-loaded classpath, asserting equality. */
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
