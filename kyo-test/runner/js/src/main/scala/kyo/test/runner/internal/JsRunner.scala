package kyo.test.runner.internal

import kyo.Chunk
import kyo.test.RunConfig
import kyo.test.TestReport
import sbt.testing.Runner
import sbt.testing.Task
import sbt.testing.TaskDef

/** Scala.js [[Runner]] coordinator for kyo-test (V3 new-runner path).
  *
  * Created by [[kyo.test.runner.JsFramework.runner]] once per test run. Parses `args` into a [[RunConfig]] (via [[Args]]), creates one
  * [[JsTask]] per [[TaskDef]], and accumulates per-suite [[TestReport]] values for the final `done()` summary. [[JsTask]] delegates
  * execution to the pure-Kyo [[kyo.test.runner.TestRunner]].
  *
  * JS note: tasks are executed sequentially by the Scala.js test runner. The `results` buffer is a plain `ListBuffer` (no concurrent access
  * on single-threaded JS).
  *
  * A worker runner (`workerSend` present) runs its tasks in a JS run of its own, and the adapter prints only the controller's `done()`, so
  * each suite a worker runs is also sent to the controller as [[WorkerReports]] messages, which the controller's [[receiveMessage]] adds
  * to its results.
  */
final private[runner] class JsRunner(
    val args: Array[String],
    val remoteArgs: Array[String],
    val testClassLoader: ClassLoader,
    workerSend: kyo.Maybe[String => Unit] = kyo.Maybe.empty
) extends Runner:

    private val parsedArgs: Args.Result = Args.parse(args)

    locally:
        parsedArgs match
            case Args.Result.Help => java.lang.System.out.println(Args.usage)
            case _                => ()

    private[internal] val baseConfig: RunConfig =
        parsedArgs match
            case Args.Result.Ok(parsed) => parsed.config
            case _                      => RunConfig()

    private[internal] val positionalArgs: Chunk[String] =
        parsedArgs match
            case Args.Result.Ok(parsed) => parsed.positional
            case _                      => Chunk.empty

    private val results =
        scala.collection.mutable.ListBuffer.empty[TestReport]

    private def record(report: TestReport): Unit =
        results += report
        workerSend.foreach(send => WorkerReports.encode(report).foreach(send))

    /** One task per suite. Throws `IllegalArgumentException` when the arguments do not parse, which fails the sbt test task: answering
      * with no tasks would report "No tests to run" and succeed, so a mistyped flag would pass silently.
      */
    def tasks(taskDefs: Array[TaskDef]): Array[Task] =
        parsedArgs match
            case Args.Result.Ok(_)      => taskDefs.map(td => new JsTask(td, baseConfig, testClassLoader, record))
            case Args.Result.Error(msg) => throw Args.invalid(msg)
            case Args.Result.Help       => Array.empty

    /** Returns [[JsTask]] instances directly, avoiding polymorphic dispatch through [[sbt.testing.Task]] on Scala.js.
      *
      * The Scala.js linker traces all implementations of [[sbt.testing.Task.execute]] and would pull in ScalaTest's `TaskRunner` (which
      * calls `Await.result`) if the polymorphic interface is used at a call site. Using this method instead gives the linker a monomorphic
      * call to [[JsTask.execute]] only.
      */
    private[runner] def jsTasksTyped(taskDefs: Array[TaskDef]): Array[JsTask] =
        parsedArgs match
            case Args.Result.Ok(_)      => taskDefs.map(td => new JsTask(td, baseConfig, testClassLoader, record))
            case Args.Result.Error(msg) => throw Args.invalid(msg)
            case Args.Result.Help       => Array.empty

    /** Adds the suite a worker runner reports (see [[WorkerReports]]) to this controller's results. */
    def receiveMessage(msg: String): Option[String] =
        WorkerReports.decode(msg).foreach(report => results += report)
        None
    end receiveMessage

    /** Serialise a task for inter-runner transfer.
      *
      * Required by the Scala.js test bridge. kyo-test does not support distributed task serialization, so the task is serialized using its
      * fully qualified class name.
      */
    def serializeTask(task: Task, serializer: sbt.testing.TaskDef => String): String =
        serializer(task.taskDef())

    /** Deserialise a task that was serialised by [[serializeTask]].
      *
      * Required by the Scala.js test bridge.
      */
    def deserializeTask(task: String, deserializer: String => sbt.testing.TaskDef): sbt.testing.Task =
        new JsTask(deserializer(task), baseConfig, testClassLoader, record)

    def done(): String =
        parsedArgs match
            case Args.Result.Error(msg) => msg
            case Args.Result.Help       => ""
            case Args.Result.Ok(_)      => Summary.render(results, Chunk.empty, positionalArgs)
    end done

end JsRunner
