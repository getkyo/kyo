package kyo

/** With no `withClasspath` scope active, a JVM `Tasty.classpath` read falls back to `TastyState.global`, which `PlatformFallback` populates
  * from the live classpath. The cross-platform `PlatformFallback` impls on JS and Native return an empty classpath.
  */
class TastyStateGlobalFallbackTest extends kyo.test.Test[Any]:

    "Tasty.classpath uses TastyState.global as fallback" in {
        Tasty.classpath.map: cp =>
            assert(cp.symbols.size >= 1, s"JVM fallback via TastyState.global must yield non-empty classpath; got ${cp.symbols.size}")
            succeed
    }

end TastyStateGlobalFallbackTest
