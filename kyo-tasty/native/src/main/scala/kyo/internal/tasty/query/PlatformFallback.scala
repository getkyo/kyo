package kyo.internal.tasty.query

import kyo.*

/** Native fallback: empty Binding. Queries return predictable empty results.
  *
  * There is no boot classpath enumeration available on Native; callers must use
  * Tasty.withClasspath explicitly to bind a classpath.
  */
private[kyo] object PlatformFallback:
    def initFallback: Binding = Binding.empty
