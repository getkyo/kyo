package kyo

import kyo.internal.TestClasspaths

/** Verifies that `withClasspath(roots)` cold-loads the live JVM classpath (discovered via java.class.path) and binds a non-empty Classpath
  * inside the callback.
  */
class WithClasspathColdLoadTest extends kyo.test.Test[Any]:

    "withClasspath(roots) cold-loads classpath and binds; symbols.size > 0" in {
        TestClasspaths.withClasspath() {
            Tasty.classpath.map { classpath =>
                val n = classpath.symbols.size
                assert(n > 0, s"withClasspath(roots) must bind a non-empty classpath; got $n symbols")
                succeed
            }
        }
    }

end WithClasspathColdLoadTest
