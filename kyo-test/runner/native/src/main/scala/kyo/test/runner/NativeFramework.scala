package kyo.test.runner

import sbt.testing.Fingerprint
import sbt.testing.Framework
import sbt.testing.Runner
import sbt.testing.SubclassFingerprint

/** Scala Native test-interface Framework entry point for kyo-test.
  *
  * Structurally identical to [[SbtFramework]] on JVM and [[JsFramework]] on Scala.js. The scala-native-test-interface re-uses the same
  * `sbt.testing` package; fingerprint matching and runner creation are identical.
  *
  * The Scala Native `TestAdapter` loads this class by name from `Test / testFrameworks`, via
  * `scala.scalanative.reflect.Reflect.lookupInstantiatableClass`; that is what makes the `@EnableReflectiveInstantiation` annotation
  * below load-bearing. The META-INF/services/sbt.testing.Framework file in this jar serves SPI-based tools such as scala-cli's test
  * runner, not sbt. A class the adapter cannot instantiate is dropped silently, so a wiring mistake surfaces as zero tests and a
  * successful exit rather than as an error.
  *
  * Native-specific behaviour:
  *   - Parallelism is kept at 1 by default (our test fixture is single-threaded for simplicity, matching the plan).
  *   - [[NativeTask.execute]] blocks via `Await.result` (Native supports real threads, unlike JS).
  *   - [[slaveRunner]] is required by the Scala Native test bridge; kyo-test does not use distributed execution, so it delegates to
  *     [[runner]].
  */
@scala.scalanative.reflect.annotation.EnableReflectiveInstantiation
class NativeFramework extends Framework:

    def name(): String = "kyo-test"

    def fingerprints(): Array[Fingerprint] =
        Array(NativeSuiteFingerprint)

    def runner(
        args: Array[String],
        remoteArgs: Array[String],
        testClassLoader: ClassLoader
    ): Runner =
        new internal.NativeRunner(args, remoteArgs, testClassLoader)

    /** Required by the Scala Native test bridge.
      *
      * kyo-test does not support distributed (master/slave) execution, so this delegates to [[runner]]. The `send` callback (used by the
      * slave to communicate results back to the master) is ignored; all events flow through the [[sbt.testing.EventHandler]] passed to
      * [[sbt.testing.Task.execute]] instead.
      */
    def slaveRunner(
        args: Array[String],
        remoteArgs: Array[String],
        testClassLoader: ClassLoader,
        send: String => Unit
    ): Runner =
        runner(args, remoteArgs, testClassLoader)

end NativeFramework

/** Fingerprint that matches all non-module subclasses of `kyo.test.SuiteFingerprintMarker` with a no-arg constructor.
  *
  * The V3 discovery path. Mirrors [[kyo.test.runner.SuiteFingerprint]] on JVM. Suites discovered here are routed by [[NativeTask]] to the
  * pure-Kyo `kyo.test.runner.TestRunner`.
  *
  * Defined as a file-level object (not nested in NativeFramework) so its class name is stable.
  */
private[runner] object NativeSuiteFingerprint extends SubclassFingerprint:
    def isModule(): Boolean                = false
    def superclassName(): String           = "kyo.test.SuiteFingerprintMarker"
    def requireNoArgConstructor(): Boolean = true
end NativeSuiteFingerprint
