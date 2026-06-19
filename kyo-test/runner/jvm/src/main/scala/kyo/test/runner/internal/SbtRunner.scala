package kyo.test.runner.internal

import java.util.concurrent.atomic.AtomicReference
import kyo.Chunk
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
  * The `results` queue is thread-safe; sbt may call `execute` on multiple tasks concurrently.
  */
final private[runner] class SbtRunner(
    val args: Array[String],
    val remoteArgs: Array[String],
    val testClassLoader: ClassLoader
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

    private[internal] val baseConfig: RunConfig =
        val fromArgs =
            parsedArgs match
                case Args.Result.Ok(parsed) => parsed.config
                case _                      => RunConfig()
        // Count-only can also be triggered by the `kyo.test.count` system property. This works with a plain `test` (no
        // `testOnly -- --count` needed) and, crucially, in modules that also register another test framework (e.g. ScalaTest),
        // whose runner rejects the unknown `--count` CLI arg and would abort the whole task before kyo-test counts.
        val withCount =
            if java.lang.System.getProperty("kyo.test.count") == "true" then fromArgs.copy(countOnly = true)
            else fromArgs
        if java.lang.System.getProperty("kyo.test.list") == "true" then withCount.copy(countOnly = true, listOnly = true)
        else withCount
        end if
    end baseConfig

    private[internal] val positionalArgs: Chunk[String] =
        parsedArgs match
            case Args.Result.Ok(parsed) => parsed.positional
            case _                      => Chunk.empty

    private val results =
        new java.util.concurrent.ConcurrentLinkedQueue[TestReport]()

    // End-of-run leak detection runs once per forked test JVM, the one place the probe is both sound (the fork holds only this
    // run's resources) and safe to fail by exit. Enablement and the whitelist are per-suite RunConfig (default on), carried on
    // each SuiteReport and aggregated at done(); the fork check is resolved once here (cheap: `sun.java.command` is set at JVM
    // launch). The baseline is captured now, in the constructor, before any suite runs, so the diff at done() excludes the JVM's
    // own startup descriptors and threads (including the sbt.ForkMain socket). In the main sbt JVM `forked` is false: no
    // baseline, no carrier tracking, no check (the diff would be polluted by sbt's own resources and a throw would fail sbt).
    private val forked       = LeakCheck.isForked
    private val leakBaseline = if forked then LeakCheck.baseline() else LeakCheck.Baseline(kyo.Maybe.empty, Set.empty)
    private val leakCheckRan = new java.util.concurrent.atomic.AtomicBoolean(false)

    // Populated on first tasks() invocation. SuiteDiscovery scans the META-INF/services file
    // and surfaces classloader / non-TestBase failures so they end up in Summary's warning line.
    private[runner] val discoveryErrors: AtomicReference[Chunk[String]] =
        new AtomicReference(Chunk.empty)

    def tasks(taskDefs: Array[TaskDef]): Array[Task] =
        parsedArgs match
            case Args.Result.Ok(_) =>
                discoveryErrors.set(SuiteDiscovery.discoverDetailed(testClassLoader).errors)
                taskDefs.map(td => new SbtTask(td, baseConfig, testClassLoader, results, forked))
            case _ =>
                Array.empty

    def done(): String =
        runLeakCheck()
        parsedArgs match
            case Args.Result.Error(msg) => msg
            case Args.Result.Help       => ""
            case Args.Result.Ok(_) =>
                import scala.jdk.CollectionConverters.*
                Summary.render(results.asScala, discoveryErrors.get(), positionalArgs)
        end match
    end done

    /** Runs the end-of-run leak probes once, only inside a forked test JVM, and throws [[LeakCheck.Detected]] on a leak so sbt fails the test
      * task. The leak settings are aggregated from the suites that ran in this fork (each [[TestReport]] carries its suite's effective
      * `leakCheck` and `leakCheckWhitelist`): the check runs if any suite enabled it, against the union of their whitelists. sbt calls `done()`
      * more than once per forked runner (once after execution, once from a shutdown hook), so the compare-and-set guard fires the probes and
      * any failure exactly once. Outside a fork (the main sbt JVM) `forked` is false, so this is a no-op.
      */
    private def runLeakCheck(): Unit =
        if forked && leakCheckRan.compareAndSet(false, true) then
            import scala.jdk.CollectionConverters.*
            val suites    = results.asScala.flatMap(_.suiteReports)
            val enabled   = suites.exists(_.leakCheck)
            val whitelist = Chunk.from(suites.flatMap(_.leakCheckWhitelist)).distinct
            if enabled then
                LeakCheck.detect(
                    leakBaseline,
                    whitelist = whitelist,
                    idleBudgetNanos = 2_000_000_000L,
                    settleNanos = 200_000_000L,
                    pollNanos = 10_000_000L
                ) match
                    case kyo.Maybe.Present(report) => throw new LeakCheck.Detected(report)
                    case kyo.Maybe.Absent          => ()
                end match
            end if
    end runLeakCheck

end SbtRunner
