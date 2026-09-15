package kyo.test.runner

import sbt.testing.Fingerprint
import sbt.testing.Framework
import sbt.testing.Runner
import sbt.testing.SubclassFingerprint

/** Scala.js test-interface Framework entry point for kyo-test.
  *
  * Structurally identical to [[SbtFramework]] on JVM. The scalajs-test-interface re-uses the same `sbt.testing` package; fingerprint
  * matching and runner creation are identical.
  *
  * sbt-scalajs's `TestAdapter` loads this class by name from `Test / testFrameworks`, using
  * `scala.scalajs.reflect.Reflect.lookupInstantiatableClass`; that is what makes the `@EnableReflectiveInstantiation` annotation
  * load-bearing. The META-INF/services/sbt.testing.Framework file in this jar serves SPI-based tools such as scala-cli's test runner, not
  * sbt. A class the adapter cannot instantiate is dropped silently, so a wiring mistake surfaces as zero tests and a successful exit
  * rather than as an error.
  *
  * JS-specific behaviour:
  *   - `parallelism > 1` is silently capped to 1 (JS is single-threaded). [[JsTask]] logs a warning to stderr at task execution time when
  *     the configured parallelism is > 1.
  *   - [[JsTask.execute]] does not block (no `Await.result`). It registers an `onComplete` callback on the run Future; events are emitted
  *     inside that callback. The Scala.js test runner drains microtasks between calls, so this is safe.
  */
@scala.scalajs.reflect.annotation.EnableReflectiveInstantiation
class JsFramework extends Framework:

    def name(): String = "kyo-test"

    def fingerprints(): Array[Fingerprint] =
        Array(JsSuiteFingerprint)

    def runner(
        args: Array[String],
        remoteArgs: Array[String],
        testClassLoader: ClassLoader
    ): Runner =
        new internal.JsRunner(args, remoteArgs, testClassLoader)

    /** The worker runner the Scala.js test adapter starts, in a JS run of its own, for a task sbt executes on another thread than the one
      * that created the controller. Events still flow through the [[sbt.testing.EventHandler]] passed to [[sbt.testing.Task.execute]];
      * `send` carries each suite's result to the controller, whose `done()` is the summary sbt prints.
      */
    def slaveRunner(
        args: Array[String],
        remoteArgs: Array[String],
        testClassLoader: ClassLoader,
        send: String => Unit
    ): Runner =
        new internal.JsRunner(args, remoteArgs, testClassLoader, kyo.Maybe(send))

end JsFramework

/** Fingerprint that matches all non-module subclasses of `kyo.test.SuiteFingerprintMarker` with a no-arg constructor.
  *
  * The V3 discovery path. Mirrors [[kyo.test.runner.SuiteFingerprint]] on JVM. Suites discovered here are routed by [[JsTask]] to the
  * pure-Kyo `kyo.test.runner.TestRunner`.
  *
  * Defined as a file-level object (not nested in JsFramework) so its class name is stable.
  */
private[runner] object JsSuiteFingerprint extends SubclassFingerprint:
    def isModule(): Boolean                = false
    def superclassName(): String           = "kyo.test.SuiteFingerprintMarker"
    def requireNoArgConstructor(): Boolean = true
end JsSuiteFingerprint
