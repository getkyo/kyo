package kyo.test.runner

import sbt.testing.Fingerprint
import sbt.testing.Framework
import sbt.testing.Runner
import sbt.testing.SubclassFingerprint

/** sbt test-interface Framework entry point for kyo-test.
  *
  * sbt finds this class by name from `Test / testFrameworks`, not through a service loader; the META-INF/services/sbt.testing.Framework
  * file in this jar serves tools that do discover by SPI, such as scala-cli's test runner. Once loaded, sbt uses [[fingerprints]] to scan
  * the test classpath for classes that extend `kyo.test.Test` and have a no-arg constructor, then passes each match to [[runner]] as a
  * [[sbt.testing.TaskDef]].
  *
  * A framework sbt cannot find contributes no fingerprints, so a wiring mistake surfaces as zero tests and a successful exit rather than
  * as an error.
  */
class SbtFramework extends Framework:

    def name(): String = "kyo-test"

    def fingerprints(): Array[Fingerprint] =
        Array(SuiteFingerprint)

    def runner(
        args: Array[String],
        remoteArgs: Array[String],
        testClassLoader: ClassLoader
    ): Runner =
        new internal.SbtRunner(args, remoteArgs, testClassLoader)

end SbtFramework

/** Fingerprint that matches all non-module subclasses of `kyo.test.SuiteFingerprintMarker` with a no-arg constructor.
  *
  * The V3 discovery path: suites that extend `kyo.test.Test[S]` pick up the non-parametric `SuiteFingerprintMarker` ancestor in their
  * linearization and match here (`xsbt.api.Discovery.simpleName` drops parameterized parent types, so the non-parametric marker is what is
  * matched rather than `Test[S]` itself); the runner Task routes them to the pure-Kyo `kyo.test.runner.TestRunner`. Internal fixtures
  * extend `kyo.test.internal.TestBase[S]` directly (without the marker mixin) and are intentionally not auto-discovered.
  *
  * Defined as a file-level object (not nested in SbtFramework) so its class name is stable across classloader boundaries.
  */
private[runner] object SuiteFingerprint extends SubclassFingerprint:
    def isModule(): Boolean                = false
    def superclassName(): String           = "kyo.test.SuiteFingerprintMarker"
    def requireNoArgConstructor(): Boolean = true
end SuiteFingerprint
