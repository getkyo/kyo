package kyo.compat

/** What this binding's carriers guarantee beyond the shared surface; the conformance suites read it to run a leaf or leave it pending. */
private[compat] object CompatCapabilities:

    /** `AsyncStream.flatMap` evaluates a nested chain through `concat` frames, one run per nesting level, so a deep left-nested chain
      * overflows the stack.
      */
    val stackSafeDeepFlatMap: Boolean = false
end CompatCapabilities
