package kyo.internal

import kyo.*

/** Base class for fidelity tests that need cold/warm classpath parity assertions.
  *
  * Provides `coldWarmEquiv(name)(body)` which runs `body` against both a cold-decoded classpath
  * and a warm snapshot-loaded classpath and asserts structural equality, through
  * `TestClasspaths2.withSnapshotInMemory`.
  *
  * In memory names the SNAPSHOT half only: it is serialized and read back as bytes, never written
  * to a file. The cold classpath under it is whatever `TestClasspaths.withClasspath` gives this
  * host, which walks a real classpath on the JVM and stages the embedded fixtures through a temp
  * directory on JS and Native. Only a page, which has neither, decodes them as pickles. So these
  * suites need a file system everywhere except in a browser, and a leaf here that needs one of its
  * own is the ordinary case rather than the exception.
  */
abstract class Fidelity2TestBase extends kyo.test.Test[Any]:

    /** Run `body` against both a cold classpath and a warm snapshot-loaded classpath, asserting equality. */
    protected def coldWarmEquiv[A](
        name: String
    )(body: Tasty.Classpath => A)(using CanEqual[A, A], Frame): Unit =
        name in {
            TestClasspaths2.withSnapshotInMemory().map { (cold, warm) =>
                val coldResult = body(cold)
                val warmResult = body(warm)
                assert(
                    coldResult == warmResult,
                    s"Cold/warm divergence for '$name': cold=$coldResult warm=$warmResult"
                )
                succeed
            }
        }
    end coldWarmEquiv

end Fidelity2TestBase
