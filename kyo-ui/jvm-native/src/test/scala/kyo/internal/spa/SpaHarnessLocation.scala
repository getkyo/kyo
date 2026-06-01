package kyo.internal.spa

/** Stub for the non-JS test compile. The SPA harness is JS-only by construction (it serves a Scala.js bundle to Chrome).
  * `UITestSpa` lives in shared test sources so JVM and Native compiles succeed, but any test that actually invokes the harness must run on
  * JS; on JVM/Native the smoke test cancels before reaching this object. Shared by both non-JS platforms via the `jvm-native` source set.
  */
private[kyo] object SpaHarnessLocation:
    def bundleDir: String =
        sys.error("kyo.internal.spa.SpaHarnessLocation.bundleDir is JS-only; UITestSpa cannot run on this platform")
end SpaHarnessLocation
