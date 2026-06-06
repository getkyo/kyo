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

    // Populated on first tasks() invocation. SuiteDiscovery scans the META-INF/services file
    // and surfaces classloader / non-TestBase failures so they end up in Summary's warning line.
    private[runner] val discoveryErrors: AtomicReference[Chunk[String]] =
        new AtomicReference(Chunk.empty)

    def tasks(taskDefs: Array[TaskDef]): Array[Task] =
        parsedArgs match
            case Args.Result.Ok(_) =>
                discoveryErrors.set(SuiteDiscovery.discoverDetailed(testClassLoader).errors)
                taskDefs.map(td => new SbtTask(td, baseConfig, testClassLoader, results))
            case _ =>
                Array.empty

    def done(): String =
        parsedArgs match
            case Args.Result.Error(msg) => msg
            case Args.Result.Help       => ""
            case Args.Result.Ok(_) =>
                import scala.jdk.CollectionConverters.*
                Summary.render(results.asScala, discoveryErrors.get(), positionalArgs)
    end done

end SbtRunner
