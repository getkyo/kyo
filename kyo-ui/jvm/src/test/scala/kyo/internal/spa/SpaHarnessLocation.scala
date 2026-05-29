package kyo.internal.spa

/** Stub implementation for the JVM test compile. The SPA harness is JS-only by construction (it serves a Scala.js bundle to Chrome).
  * `UITestSpa` lives in shared test sources so JVM/Native compiles succeed, but any test that actually invokes the harness must run on JS;
  * on JVM/Native the smoke test cancels before reaching this object.
  */
private[kyo] object SpaHarnessLocation:
    def bundleDir: String =
        sys.error("kyo.internal.spa.SpaHarnessLocation.bundleDir is JS-only; UITestSpa cannot run on JVM")
end SpaHarnessLocation
