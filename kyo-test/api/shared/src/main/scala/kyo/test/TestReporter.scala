package kyo.test

/** Receives lifecycle events from the test runner and turns them into output or side effects.
  *
  * The runner calls these methods in a well-defined order: `onRunStart`, then for each suite `onSuiteStart` / (per leaf: `onLeafStart` /
  * `onLeafComplete`) / `onSuiteComplete`, and finally `onRunComplete`. Implementations must be thread-safe when
  * `RunConfig.parallelism > 1`.
  *
  * @see
  *   `kyo.test.runner.ConsoleReporter` the default human-readable reporter implementation
  * @see
  *   `kyo.test.runner.CombinedReporter` fan-out reporter that delegates to multiple reporters
  * @see
  *   `kyo.test.runner.TapReporter` TAP version 13 reporter for CI integration
  * @see
  *   `kyo.test.runner.JUnitXmlReporter` JUnit XML reporter consumed by sbt/Maven/Jenkins
  * @see
  *   `kyo.test.runner.Reporters` factory namespace for constructing reporter instances
  * @see
  *   [[kyo.test.RunConfig]] where the active reporter is configured for a run
  */
trait TestReporter:
    /** Called once before any suite begins; receives top-level run metadata. */
    def onRunStart(info: RunInfo): Unit

    /** Called when the runner is about to execute the first leaf of a suite. */
    def onSuiteStart(info: SuiteInfo): Unit

    /** Called immediately before each individual leaf test begins. */
    def onLeafStart(info: LeafInfo): Unit

    /** Called after each leaf completes (or is skipped/ignored); carries the final result. */
    def onLeafComplete(info: LeafInfo, result: TestResult): Unit

    /** Called after all leaves in a suite have finished; carries the aggregated suite report. */
    def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit

    /** Called once after all suites have finished; carries the aggregated run report. */
    def onRunComplete(report: TestReport): Unit
end TestReporter

object TestReporter
