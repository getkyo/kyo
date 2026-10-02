package kyo.compat

import org.scalatest.Assertion
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.AsyncTimeLimitedTests
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

final case class TestError(msg: String) extends Exception(msg)

/** Base for every kyo-compat binding test.
  *
  * Tests pass their `CIO[Assertion]` to `run`, which bounds it with `CIO.timeoutWithError(testTimeout)`, the binding's own cross-platform
  * timeout, so a CIO that never completes fails with the binding's timeout error. That bound lives on the binding's runtime, and a
  * runtime whose timer dies (a fatal error escaping on its thread) can fire nothing: `timeLimit` is a second bound on ScalaTest's own
  * timer, above the first so the binding's error wins whenever the binding is alive, and a leaf never freezes the suite.
  */
class CompatTest extends AsyncFreeSpec, NonImplicitAssertions, AsyncTimeLimitedTests:

    // Override scalatest's default `SerialExecutionContext`. Its `runNow` pump throws on JS/Native
    // ("Queue is empty while future is not completed") the instant a test's `Future` is driven by a
    // timer/scheduler outside the serial queue — which every async CIO op is. `AsyncEngine.runTestImpl`
    // only calls `runNow` when `executionContext` is a `SerialExecutionContext`; a non-serial EC routes
    // it to the callback-based async path that works on all platforms. This is also the implicit EC the
    // test bodies and the Ox binding's `unsafeRun` resolve.
    implicit override def executionContext: ExecutionContext = ExecutionContext.global

    protected def testTimeout: FiniteDuration = 60.seconds

    val timeLimit: Span = Span(testTimeout.toSeconds + 30, Seconds)

    /** Runs a test's `CIO[Assertion]`, bounded by `testTimeout`. `c` is by-value: a `CIO` is a lazy description, so building it eagerly has
      * no side effects, and a by-name argument would be mis-inlined into `CIO.timeoutWithError`'s `inline` parameter.
      */
    protected def run(c: CIO[Assertion]): Future[Assertion] =
        CIO.timeoutWithError(testTimeout)(
            new RuntimeException(s"test did not complete within $testTimeout")
        )(c).unsafeRun

end CompatTest
