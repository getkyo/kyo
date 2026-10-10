package kyo.test.runner.internal

import java.util.concurrent.atomic.AtomicReference
import kyo.Chunk
import kyo.discard
import kyo.test.RunConfig
import kyo.test.TestReport
import sbt.testing.Runner
import sbt.testing.Task
import sbt.testing.TaskDef

/** sbt [[Runner]] coordinator for kyo-test (V3 new-runner path).
  *
  * Created by [[kyo.test.runner.SbtFramework.runner]] once per test run. Parses `args` into a [[RunConfig]] (via [[Args]]), creates one
  * [[SbtTask]] per [[TaskDef]], and accumulates per-suite [[TestReport]] values for the final `done()` summary. [[SbtTask]] delegates
  * execution to the pure-Kyo [[kyo.test.runner.TestRunner]].
  *
  * Under `fork := true` sbt builds two runners. The one in sbt's own JVM only carries the arguments to the fork, is never asked for tasks,
  * and is the one whose `done()` sbt logs; the forked one runs every task, and sbt's `ForkMain` discards its `done()`. So a runner never
  * asked for tasks returns no summary, and a forked runner writes its summary to `summaryOut` itself, the stdout sbt forwards from the fork.
  *
  * The `results` queue is thread-safe; sbt may call `execute` on multiple tasks concurrently.
  */
final private[runner] class SbtRunner(
    val args: Array[String],
    val remoteArgs: Array[String],
    val testClassLoader: ClassLoader,
    forked: Boolean = LeakCheck.isForked,
    summaryOut: java.io.PrintStream = java.lang.System.out
) extends Runner:

    private val parsedArgs: Args.Result = Args.parse(args)

    locally:
        parsedArgs match
            case Args.Result.Help =>
                java.lang.System.out.println(Args.usage)
            case Args.Result.Error(msg) =>
                java.lang.System.err.println(s"[kyo-test] CLI error: $msg")
            case Args.Result.Ok(_) =>
                ()

    /** The flags as an overlay over each suite's own config; see `TestRunner.runReport`. */
    private[internal] val baseOverlay: RunConfig => RunConfig =
        val fromArgs: RunConfig => RunConfig =
            parsedArgs match
                case Args.Result.Ok(parsed) => parsed.overlay
                case _                      => identity
        // Count-only can also be triggered by the `kyo.test.count` system property. This works with a plain `test` (no
        // `testOnly -- --count` needed) and, crucially, in modules that also register another test framework (e.g. ScalaTest),
        // whose runner rejects the unknown `--count` CLI arg and would abort the whole task before kyo-test counts.
        val withCount: RunConfig => RunConfig =
            if java.lang.System.getProperty("kyo.test.count") == "true" then fromArgs.andThen(_.copy(countOnly = true))
            else fromArgs
        if java.lang.System.getProperty("kyo.test.list") == "true" then withCount.andThen(_.copy(countOnly = true, listOnly = true))
        else withCount
        end if
    end baseOverlay

    private[internal] val positionalArgs: Chunk[String] =
        parsedArgs match
            case Args.Result.Ok(parsed) => parsed.positional
            case _                      => Chunk.empty

    private val results =
        new java.util.concurrent.ConcurrentLinkedQueue[TestReport]()

    // End-of-run leak detection runs once per forked test JVM, the one place the probe is both sound (the fork holds only this
    // run's resources) and safe to fail by exit. Enablement and the allowlist are per-suite RunConfig (default on), carried on
    // each SuiteReport and aggregated at done(); the fork check is resolved once, at construction (cheap: `sun.java.command` is set at
    // JVM launch). The baseline is captured now, in the constructor, before any suite runs, so the diff at done() excludes the JVM's
    // own startup descriptors and threads (including the sbt.ForkMain socket). In the main sbt JVM `forked` is false: no
    // baseline, no carrier tracking, no check (the diff would be polluted by sbt's own resources and a throw would fail sbt).
    private val leakBaseline      = if forked then LeakCheck.baseline() else LeakCheck.Baseline(kyo.Maybe.empty, Set.empty)
    private val endOfRunChecksRan = new java.util.concurrent.atomic.AtomicBoolean(false)
    private val suiteWindows      = new LeakCheck.SuiteWindows

    private val tasksRequested = new java.util.concurrent.atomic.AtomicBoolean(false)

    // sbt calls a forked runner's done() twice (after execution and from a shutdown hook); the summary is written once.
    private val summaryWritten = new java.util.concurrent.atomic.AtomicBoolean(false)

    // Leak-debug mode (KYO_TEST_LEAK_DEBUG=1): leaves are forced serial (LeafPool.globalK = 1), so install a per-leaf probe that snapshots the
    // open descriptors around each leaf and records which descriptors the leaf left open. The end-of-run leak report then attributes each leaked
    // descriptor to the test that opened it (see LeakCheck.originOf). Only in a forked JVM, where the descriptor probe is sound.
    if forked && LeakDebug.enabled then
        LeakDebug.leafProbe = kyo.Maybe((path: Chunk[String]) =>
            val before = LeakCheck.openFdTargets().getOrElse(Set.empty)
            () => LeakCheck.recordLeafOrigins(path.mkString(" > "), before, LeakCheck.openFdTargets().getOrElse(Set.empty))
        )
    end if

    // Populated on first tasks() invocation. SuiteDiscovery scans the META-INF/services file
    // and surfaces classloader / non-TestBase failures so they end up in Summary's warning line.
    private[runner] val discoveryErrors: AtomicReference[Chunk[String]] =
        new AtomicReference(Chunk.empty)

    // The suites handed to this runner and the ones whose task produced a report; done() fails the run on the difference.
    private val selected  = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    private val completed = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

    private val selectionChecked = new java.util.concurrent.atomic.AtomicBoolean(false)

    def tasks(taskDefs: Array[TaskDef]): Array[Task] =
        tasksRequested.set(true)
        parsedArgs match
            case Args.Result.Ok(_) =>
                discoveryErrors.set(SuiteDiscovery.discoverDetailed(testClassLoader).errors)
                taskDefs.foreach(td => discard(selected.add(td.fullyQualifiedName())))
                taskDefs.map(td => new SbtTask(td, baseOverlay, testClassLoader, results, completed, forked, suiteWindows))
            case _ =>
                Array.empty
        end match
    end tasks

    def done(): String =
        // The summary is written before the end-of-run checks, which fail by throwing, so a leak report never hides the counts.
        val summary =
            parsedArgs match
                case Args.Result.Error(msg) => msg
                case Args.Result.Help       => ""
                case Args.Result.Ok(_)      =>
                    if !tasksRequested.get() then ""
                    else
                        import scala.jdk.CollectionConverters.*
                        Summary.render(results.asScala, discoveryErrors.get(), positionalArgs)
        if forked && summary.nonEmpty && summaryWritten.compareAndSet(false, true) then summaryOut.println(summary)
        checkSelectionRan()
        runEndOfRunChecks()
        summary
    end done

    /** Fails the run when the selection ran nothing. sbt scores a suite by the events its task emits and counts a suite with none as
      * passed, so a selected suite whose task never produced a report, or a selection whose filter matched no leaf, would otherwise
      * finish green with Total 0. A suite that registers no leaves is failed by the runner itself, as a leaf of its report. Throws once,
      * after the summary is written, like the end-of-run checks.
      */
    private def checkSelectionRan(): Unit =
        val countOnly = baseOverlay(RunConfig.default).countOnly
        if !countOnly && !selected.isEmpty && selectionChecked.compareAndSet(false, true) then
            import scala.jdk.CollectionConverters.*
            val neverRan = selected.asScala.toSeq.filterNot(completed.contains).sorted
            if neverRan.nonEmpty then
                throw new IllegalStateException(s"kyo-test: selected suite(s) never ran: ${neverRan.mkString(", ")}")
            val leaves = results.asScala.iterator.flatMap(_.suiteReports.iterator).map(_.leafResults.size).sum
            if leaves == 0 then
                throw new IllegalStateException(s"kyo-test: the selection ran 0 tests: ${selected.asScala.toSeq.sorted.mkString(", ")}")
        end if
    end checkSelectionRan

    /** Runs the end-of-run leak and stranded-op probes once, only inside a forked test JVM, throwing on the first one that finds
      * something so sbt fails the test task. The leak settings are aggregated from the suites that ran in this fork (each
      * [[TestReport]] carries its suite's effective `leakCheck` and `leakCheckAllowlist`): [[LeakCheck]] runs if any suite enabled it,
      * against the union of their allowlists. [[StrandedOpCheck]] has no per-suite opt-out (unlike LeakCheck it always runs in a fork)
      * but reuses the same aggregated allowlist. sbt calls `done()` more than once per forked runner (once after execution, once from a
      * shutdown hook), so the compare-and-set guard fires the probes and any failure exactly once. Outside a fork (the main sbt JVM)
      * `forked` is false, so this is a no-op.
      */
    private def runEndOfRunChecks(): Unit =
        if forked && endOfRunChecksRan.compareAndSet(false, true) then
            import scala.jdk.CollectionConverters.*
            val suites    = results.asScala.flatMap(_.suiteReports)
            val allowlist = Chunk.from(suites.flatMap(_.leakCheckAllowlist)).distinct
            // Each category runs if any suite in the fork enabled it (master on AND that category on); a suite exempts a category by
            // turning just that one off, so the fork keeps detecting the rest. A descriptor category exemption covers the descriptors the
            // exempting suite opened (LeakCheck.exemptedFds); fibers and threads carry no opener, so exempting them fork-wide still takes
            // every suite opting out, which is why the per-category toggles live on the shared suite base.
            val checkFibers          = suites.exists(s => s.leakCheck && s.leakCheckFibers)
            val checkThreads         = suites.exists(s => s.leakCheck && s.leakCheckThreads)
            val checkFileDescriptors = suites.exists(s => s.leakCheck && s.leakCheckFileDescriptors)
            val checkSockets         = suites.exists(s => s.leakCheck && s.leakCheckSockets)
            if checkFibers || checkThreads || checkFileDescriptors || checkSockets then
                LeakCheck.detect(
                    leakBaseline,
                    allowlist = allowlist,
                    exemptedFds = suiteWindows.exempted,
                    checkFibers = checkFibers,
                    checkThreads = checkThreads,
                    checkFileDescriptors = checkFileDescriptors,
                    checkSockets = checkSockets,
                    // Both budgets are ceilings a fork spends only when it holds work nothing accounts for: awaitSchedulerIdle returns as
                    // soon as the scheduler holds no unaccounted work for one settle window (load zero, or every busy worker allowlisted),
                    // and awaitFdDrain returns immediately when its first sample is empty. A fork carrying a process-lifetime transport is
                    // therefore not charged for it. The ceiling is sized for the slowest runner rather than the common case: 2s was tuned
                    // on an unloaded box and is not enough on a CI runner with four contended cores, where a teardown cascade (a FIN round
                    // trip, a pump unwind, a deferred close discharging on the io_uring reap thread) can outlast it and read as a leak that
                    // would have drained a moment later.
                    idleBudgetNanos = 30_000_000_000L,
                    // Paid on every run, twice: the scheduler must stay idle this long before quiescence is believed, and the post-gc park
                    // waits this long for Cleaner-closed channels to drop out. Kept short for that reason.
                    settleNanos = 500_000_000L,
                    pollNanos = 10_000_000L,
                    fdDrainBudgetNanos = 30_000_000_000L
                ) match
                    case kyo.Maybe.Present(report) => throw new LeakCheck.Detected(report)
                    case kyo.Maybe.Absent          => ()
                end match
            end if
            // Runs regardless of every suite's leakCheck settings (LeakCheck.detect above already settled the scheduler via
            // awaitSchedulerIdle when any category ran; StrandedOpCheck's own two-sample settle window is independent of that and runs
            // unconditionally, since a stranded op is never acceptable suite behavior).
            StrandedOpCheck.detect(allowlist, settleNanos = 200_000_000L) match
                case kyo.Maybe.Present(report) => throw new StrandedOpCheck.Detected(report)
                case kyo.Maybe.Absent          => ()
            end match
            // Authoritative teardown violations, checked alongside StrandedOpCheck's probe-based inference: a component that already,
            // definitively determined (not sampled) that it stranded an obligation reports it directly via
            // kyo.internal.Diagnostics.reportViolation. StrandedOpCheck's own `after.closed => Ok` exemption cannot see these (it is
            // deliberately scoped to a live loop's lost-wakeup class, not a closed component's leaked fd/engine reclaim).
            val violations = kyo.internal.Diagnostics.drainViolations()
            if violations.nonEmpty then throw new TeardownViolationCheck.Detected(violations)
    end runEndOfRunChecks

end SbtRunner
