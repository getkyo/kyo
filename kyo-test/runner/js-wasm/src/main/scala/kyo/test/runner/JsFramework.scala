package kyo.test.runner

import sbt.testing.Fingerprint
import sbt.testing.Framework
import sbt.testing.Runner
import sbt.testing.SubclassFingerprint

/** Scala.js test-interface Framework entry point for kyo-test.
  *
  * Structurally identical to [[SbtFramework]] on JVM. Discovered by the Scala.js test runner via the `sbt.testing.Framework` SPI. The
  * scalajs-test-interface re-uses the same `sbt.testing` package; fingerprint matching and runner creation are identical.
  *
  * The `@EnableReflectiveInstantiation` annotation is required by sbt-scalajs's `TestAdapter`, which uses
  * `scala.scalajs.reflect.Reflect.lookupInstantiatableClass` to load Framework instances at test-run time.
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

    /** Required by the Scala.js and Scala Native test bridge.
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
