package kyo

import kyo.internal.TestClasspaths

/** With no `withClasspath` scope active, a JVM `Tasty.classpath` read falls back to `Tasty.global`, which `PlatformFallback` populates
  * from the live classpath. The cross-platform `PlatformFallback` impls on JS and Native return an empty classpath.
  *
  * `Tasty.global` is a process-wide lazy singleton that cold-loads the entire `java.class.path`. In the forked test JVM that is the full
  * transitive test classpath (every kyo module plus scala3 plus all test deps), and decoding all of it eagerly exhausts the test heap.
  * Several JVM suites force `global`; they all route the single real force through `TestClasspaths.forceGlobalNarrowed`, which narrows
  * `java.class.path` to the scala-library jar so the singleton loads one real root instead of the whole classpath. Proving the fallback
  * yields a non-empty classpath needs one real root, not the whole classpath.
  */
class TastyGlobalFallbackTest extends kyo.test.Test[Any]:

    "Tasty.classpath uses Tasty.global as fallback" in {
        TestClasspaths.forceGlobalNarrowed()
        Tasty.classpath.map { classpath =>
            assert(
                classpath.symbols.size >= 1,
                s"JVM fallback via Tasty.global must yield non-empty classpath; got ${classpath.symbols.size}"
            )
            succeed
        }
    }

end TastyGlobalFallbackTest
