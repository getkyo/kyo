package kyo.test

import kyo.Chunk
import kyo.Duration
import kyo.Maybe

/** Aggregated report for an entire test run.
  *
  * Holds one [[SuiteReport]] per executed suite and exposes derived counters (passed, failed, cancelled, etc.) as well as the total
  * wall-clock duration. Reporters receive this value in [[TestReporter.onRunComplete]].
  *
  * @param suiteReports
  *   one entry for every suite that participated in the run
  * @see
  *   [[kyo.test.SuiteReport]] per-suite results that compose this report
  * @see
  *   [[kyo.test.TestResult]] the leaf outcome enum tallied into [[TestReport.Counts]]
  * @see
  *   [[kyo.test.TestReporter.onRunComplete]] where this value is delivered to reporters
  * @see
  *   `kyo.test.runner.TestRunner.runReport` which assembles and returns a TestReport
  */
final case class TestReport(suiteReports: Chunk[SuiteReport]) derives CanEqual:

    /** All leaf-result counters, computed in a single fold over every leaf in this report.
      *
      * The result is cached as a `lazy val`; repeated calls to `passed`, `failed`, etc. do not re-walk the data.
      */
    lazy val counts: TestReport.Counts =
        suiteReports.iterator.flatMap(_.leafResults.iterator).foldLeft(TestReport.Counts())((acc, pair) => acc.tally(pair._2))

    /** Total number of leaf tests across all suites. */
    def totalLeaves: Int = counts.total

    /** Number of leaves that completed with [[TestResult.Passed]]. */
    def passed: Int = counts.passed

    /** Number of leaves that completed with [[TestResult.Failed]]. */
    def failed: Int = counts.failed

    /** Number of leaves that were cancelled (e.g., a required resource was unavailable). */
    def cancelled: Int = counts.cancelled

    /** Number of leaves recorded as pending (not yet implemented). */
    def pending: Int = counts.pending

    /** Number of leaves that were explicitly ignored. */
    def ignored: Int = counts.ignored

    /** Number of leaves that exceeded their configured timeout. */
    def timedOut: Int = counts.timedOut

    /** Number of leaves that were skipped (e.g., `only(false)` or a focus filter). */
    def skipped: Int = counts.skipped

    /** Sum of all suite durations; represents total wall-clock time for the run. */
    def totalDuration: Duration =
        suiteReports.foldLeft(Duration.Zero)(_ + _.duration)
end TestReport

object TestReport:
    /** Accumulated counts for all 7 result kinds across a set of leaf results.
      *
      * Produced by [[TestReport.counts]] via a single fold and also used directly by [[kyo.test.runner.internal.Summary]].
      *
      * @param passed
      *   number of [[TestResult.Passed]] leaves
      * @param failed
      *   number of [[TestResult.Failed]] leaves
      * @param cancelled
      *   number of [[TestResult.Cancelled]] leaves
      * @param pending
      *   number of [[TestResult.Pending]] leaves
      * @param ignored
      *   number of [[TestResult.Ignored]] leaves
      * @param timedOut
      *   number of [[TestResult.TimedOut]] leaves
      * @param skipped
      *   number of [[TestResult.Skipped]] leaves
      */
    final case class Counts(
        passed: Int = 0,
        failed: Int = 0,
        cancelled: Int = 0,
        pending: Int = 0,
        ignored: Int = 0,
        timedOut: Int = 0,
        skipped: Int = 0
    ) derives CanEqual:
        /** Total number of leaves across all result kinds. */
        def total: Int = passed + failed + cancelled + pending + ignored + timedOut + skipped

        /** Returns a new `Counts` with the counter for `r`'s kind incremented by one. */
        def tally(r: TestResult): Counts =
            r match
                case _: TestResult.Passed    => copy(passed = passed + 1)
                case _: TestResult.Failed    => copy(failed = failed + 1)
                case _: TestResult.Cancelled => copy(cancelled = cancelled + 1)
                case _: TestResult.Pending   => copy(pending = pending + 1)
                case _: TestResult.Ignored   => copy(ignored = ignored + 1)
                case _: TestResult.TimedOut  => copy(timedOut = timedOut + 1)
                case _: TestResult.Skipped   => copy(skipped = skipped + 1)
            end match
        end tally
    end Counts
end TestReport

/** Per-suite results collected after executing one test class.
  *
  * Each entry in [[leafResults]] pairs the full path (list of nested group names plus the leaf name) with its [[TestResult]]. Reporters
  * receive this value in [[TestReporter.onSuiteComplete]].
  *
  * @param name
  *   display name of the suite (typically the class simple name)
  * @param leafResults
  *   all leaf results for this suite, in execution order
  * @param duration
  *   wall-clock time from suite start to suite complete
  * @param leakCheck
  *   the suite's effective `RunConfig.leakCheck` master setting, carried here so the run-level end-of-run leak check (performed once per forked
  *   JVM after all suites finish) can honor each suite's override
  * @param leakCheckSockets
  *   the suite's effective `RunConfig.leakCheckSockets` setting, aggregated across the fork's suites to gate the socket-descriptor probe
  * @param leakCheckFileDescriptors
  *   the suite's effective `RunConfig.leakCheckFileDescriptors` setting, aggregated to gate the non-socket descriptor probe
  * @param leakCheckThreads
  *   the suite's effective `RunConfig.leakCheckThreads` setting, aggregated to gate the thread probe
  * @param leakCheckFibers
  *   the suite's effective `RunConfig.leakCheckFibers` setting, aggregated to gate the fiber probe
  * @param leakCheckAllowlist
  *   the suite's effective `RunConfig.leakCheckAllowlist`, unioned across the fork's suites by the leak check
  * @see
  *   [[kyo.test.TestReport]] which collects multiple SuiteReports into the run-level aggregate
  * @see
  *   [[kyo.test.TestResult]] the leaf outcome enum stored in [[leafResults]]
  * @see
  *   [[kyo.test.TestReporter.onSuiteComplete]] where this value is dispatched
  * @see
  *   [[kyo.test.LeafInfo]] the static descriptor paired with this report in reporter callbacks
  */
final case class SuiteReport(
    name: String,
    leafResults: Chunk[(Chunk[String], TestResult)],
    duration: Duration,
    leakCheck: Boolean = true,
    leakCheckSockets: Boolean = true,
    leakCheckFileDescriptors: Boolean = true,
    leakCheckThreads: Boolean = true,
    leakCheckFibers: Boolean = true,
    leakCheckAllowlist: Chunk[String] = Chunk.empty
) derives CanEqual

/** Static description of a leaf test, passed to reporters before and after execution.
  *
  * @param suite
  *   the containing suite's display name
  * @param path
  *   the full path from the suite root to this leaf (outermost group first)
  * @param tags
  *   the set of tags applied to this leaf via `.tagged(...)`
  * @see
  *   [[kyo.test.TestReporter.onLeafStart]] and [[kyo.test.TestReporter.onLeafComplete]] where LeafInfo is supplied
  * @see
  *   [[kyo.test.TestBuilder]] the source of the tags and path segments stored here
  * @see
  *   [[kyo.test.TestFilter]] which matches against the path and tags fields of this value
  */
final case class LeafInfo(suite: String, path: Chunk[String], tags: Set[String]) derives CanEqual

/** Static description of a suite, passed to reporters when the suite is discovered and completed.
  *
  * @param name
  *   display name of the suite
  * @param className
  *   fully qualified class name used for JVM reflection and JUnit XML class-name attributes
  * @param expectedLeafCount
  *   hint from the runner about how many leaves are expected; `Absent` when not yet enumerated
  */
final case class SuiteInfo(name: String, className: String, expectedLeafCount: Maybe[Int])
    derives CanEqual

/** Top-level metadata about a test run, passed to [[TestReporter.onRunStart]].
  *
  * @param suiteCount
  *   the number of suite classes scheduled to run
  * @param parallelism
  *   the global pool's concurrency bound (globalK) in effect for this run, as reported from [[RunConfig.parallelism]]; 1 means
  *   within-suite sequential, 0 and any N > 1 mean parallel
  */
final case class RunInfo(suiteCount: Int, parallelism: Int) derives CanEqual
