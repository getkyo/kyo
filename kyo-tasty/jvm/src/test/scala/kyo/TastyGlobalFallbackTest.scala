package kyo

/** With no `withClasspath` scope active, a JVM `Tasty.classpath` read falls back to `Tasty.global`, which `PlatformFallback` populates
  * from the live classpath. The cross-platform `PlatformFallback` impls on JS and Native return an empty classpath.
  */
class TastyGlobalFallbackTest extends kyo.test.Test[Any]:

    "Tasty.classpath uses Tasty.global as fallback" in {
        Tasty.classpath.map { classpath =>
            assert(
                classpath.symbols.size >= 1,
                s"JVM fallback via Tasty.global must yield non-empty classpath; got ${classpath.symbols.size}"
            )
            succeed
        }
    }

end TastyGlobalFallbackTest
