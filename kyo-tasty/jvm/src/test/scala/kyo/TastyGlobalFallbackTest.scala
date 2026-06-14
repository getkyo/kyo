package kyo

import kyo.internal.TestClasspaths

/** With no `withClasspath` scope active, a JVM `Tasty.classpath` read falls back to `Tasty.global`, which `PlatformFallback` populates
  * from the live classpath. The cross-platform `PlatformFallback` impls on JS and Native return an empty classpath.
  *
  * `Tasty.global` is a process-wide lazy singleton that cold-loads the entire `java.class.path`. In the forked test JVM that is the full
  * transitive test classpath (every kyo module plus scala3 plus all test deps), and decoding all of it eagerly exhausts memory. This is the
  * only test that forces `global`, so it narrows `java.class.path` to the scala-library jar for the single force and restores it afterwards:
  * proving the fallback yields a non-empty classpath needs one real root, not the whole classpath. The suite runs sequentially so no
  * concurrent leaf observes the narrowed property.
  */
class TastyGlobalFallbackTest extends kyo.test.Test[Any]:

    override def config = super.config.sequential

    "Tasty.classpath uses Tasty.global as fallback" in {
        Sync.defer(java.lang.System.getProperty("java.class.path")).map { saved =>
            Sync.defer(discard(java.lang.System.setProperty("java.class.path", TestClasspaths.scala3LibraryJar))).andThen {
                Sync.ensure(Sync.defer(discard(java.lang.System.setProperty("java.class.path", saved)))) {
                    Tasty.classpath.map { classpath =>
                        assert(
                            classpath.symbols.size >= 1,
                            s"JVM fallback via Tasty.global must yield non-empty classpath; got ${classpath.symbols.size}"
                        )
                        succeed
                    }
                }
            }
        }
    }

end TastyGlobalFallbackTest
