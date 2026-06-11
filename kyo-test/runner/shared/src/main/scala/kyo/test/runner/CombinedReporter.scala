package kyo.test.runner

import kyo.Chunk
import kyo.Duration
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestReporter
import kyo.test.TestResult

/** Fan-out reporter that dispatches each lifecycle event to all registered reporters in order.
  *
  * If a reporter throws during a lifecycle call, the exception is caught, accumulated into an internal queue, and subsequent reporters
  * still receive the event. This ensures that a misbehaving reporter cannot suppress output from healthy ones.
  *
  * In `onRunComplete`, after delegating to all reporters: if any errors were accumulated, a warning is emitted via the `diagnostics`
  * sink (default `java.lang.System.err`); if `strict` is `true`, the first accumulated exception is rethrown.
  *
  * Thread-safe: delegates to the underlying reporters and relies on each reporter's own thread-safety guarantees.
  *
  * @param reporters
  *   the delegate reporters; called in the order provided
  * @param strict
  *   when `true`, `onRunComplete` rethrows the first accumulated reporter exception after emitting the diagnostics warning
  * @param diagnostics
  *   sink for reporter-failure diagnostics (the per-reporter throw notices and the run-complete summary warning); defaults to
  *   `java.lang.System.err`. Injectable so callers and tests can capture diagnostics deterministically without mutating
  *   process-global `System.err`, which races under concurrent leaf execution.
  * @see
  *   [[kyo.test.TestReporter]] the reporter interface that CombinedReporter implements
  * @see
  *   [[kyo.test.runner.ConsoleReporter]] a typical delegate reporter
  * @see
  *   [[kyo.test.runner.TapReporter]] another typical delegate reporter for CI TAP output
  * @see
  *   [[kyo.test.runner.JUnitXmlReporter]] another typical delegate reporter for JUnit XML files
  */
final class CombinedReporter(
    reporters: Chunk[TestReporter],
    strict: Boolean = false,
    diagnostics: String => Unit = (s: String) => java.lang.System.err.println(s)
) extends TestReporter:

    private[runner] val errors = new java.util.concurrent.ConcurrentLinkedQueue[Throwable]()

    def onRunStart(info: RunInfo): Unit =
        dispatch(_.onRunStart(info))

    def onSuiteStart(info: SuiteInfo): Unit =
        dispatch(_.onSuiteStart(info))

    def onLeafStart(info: LeafInfo): Unit =
        dispatch(_.onLeafStart(info))

    def onLeafComplete(info: LeafInfo, result: TestResult): Unit =
        dispatch(_.onLeafComplete(info, result))

    override def onLeafHeartbeat(info: LeafInfo, elapsed: Duration): Unit =
        dispatch(_.onLeafHeartbeat(info, elapsed))

    def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit =
        dispatch(_.onSuiteComplete(info, report))

    def onRunComplete(report: TestReport): Unit =
        dispatch(_.onRunComplete(report))
        val errorCount = errors.size
        if errorCount > 0 then
            diagnostics(
                s"kyo-test: warning: $errorCount reporter(s) failed during the run; see stderr for details"
            )
            if strict then
                val top = errors.peek()
                if top != null then throw top
        end if
    end onRunComplete

    private def dispatch(f: TestReporter => Unit): Unit =
        reporters.foreach: r =>
            try f(r)
            catch
                case t: Throwable =>
                    errors.add(t): Unit
                    diagnostics(
                        s"[kyo-test] CombinedReporter: reporter ${r.getClass.getSimpleName} threw: $t"
                    )

end CombinedReporter

object CombinedReporter:

    /** Construct a [[CombinedReporter]] from a varargs list of reporters. */
    def apply(reporters: TestReporter*): CombinedReporter =
        new CombinedReporter(Chunk.from(reporters))

end CombinedReporter
