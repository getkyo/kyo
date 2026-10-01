package kyo.compat

/** What this binding's carriers guarantee beyond the shared surface; the conformance suites read it to run a leaf or leave it pending. */
private[compat] object CompatCapabilities:
    val stackSafeDeepFlatMap: Boolean = true
end CompatCapabilities
