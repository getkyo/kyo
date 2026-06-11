package kyo.test

/** Non-parametric marker trait for sbt test discovery.
  *
  * sbt's `xsbt.api.Discovery.simpleName` drops `Parameterized` parent types when walking a class's linearized supertypes, so the bare
  * `Test[S]` cannot itself be the fingerprint target. The platform `Framework` classes for kyo-test use this non-parametric ancestor
  * (`kyo.test.SuiteFingerprintMarker`) as their sole `SubclassFingerprint.superclassName`.
  *
  * Mixed in by [[Test]] but NOT by [[kyo.test.internal.TestBase]]. Internal fixtures inside `kyo-test-runner` extend `TestBase` directly so
  * that sbt's discovery does not auto-execute them as standalone test suites; the runner's own self-tests instantiate fixtures explicitly
  * by FQN.
  *
  * Distinct from [[kyo.test.internal.KyoTestReflect]] which enables cross-platform reflective instantiation.
  */
trait SuiteFingerprintMarker
