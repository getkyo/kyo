package kyo.compat

import org.scalatest.Assertion
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

final case class TestError(msg: String) extends Exception(msg)

/** Base for every kyo-compat binding test.
  *
  * Tests pass their `CIO[Assertion]` to `run`, which bounds it with `CIO.timeoutWithError(testTimeout)` — the binding's own cross-platform
  * timeout. A test whose CIO never completes fails with a timeout instead of freezing the whole suite.
  */
class CompatTest extends AsyncFreeSpec, NonImplicitAssertions:

    // Override scalatest's default `SerialExecutionContext`. Its `runNow` pump throws on JS/Native
    // ("Queue is empty while future is not completed") the instant a test's `Future` is driven by a
    // timer/scheduler outside the serial queue — which every async CIO op is. `AsyncEngine.runTestImpl`
    // only calls `runNow` when `executionContext` is a `SerialExecutionContext`; a non-serial EC routes
    // it to the callback-based async path that works on all platforms. This is also the implicit EC the
    // test bodies and the Ox binding's `unsafeRun` resolve.
    implicit override def executionContext: ExecutionContext = ExecutionContext.global

    protected def testTimeout: FiniteDuration = 60.seconds

    /** Runs a test's `CIO[Assertion]`, bounded by `testTimeout`. `c` is by-value: a `CIO` is a lazy description, so building it eagerly has
      * no side effects, and a by-name argument would be mis-inlined into `CIO.timeoutWithError`'s `inline` parameter.
      */
    protected def run(c: CIO[Assertion]): Future[Assertion] =
        CIO.timeoutWithError(testTimeout)(
            new RuntimeException(s"test did not complete within $testTimeout")
        )(c).unsafeRun

end CompatTest
