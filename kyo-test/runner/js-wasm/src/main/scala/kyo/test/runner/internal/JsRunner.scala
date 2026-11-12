package kyo.test.runner.internal

import kyo.Absent
import kyo.Chunk
import kyo.Maybe
import kyo.Present
import kyo.test.RunConfig
import kyo.test.TestReport
import sbt.testing.Runner
import sbt.testing.Task
import sbt.testing.TaskDef

/** Scala.js [[Runner]] coordinator for kyo-test (V3 new-runner path).
  *
  * Parses `args` into a [[RunConfig]] (via [[Args]]) and creates one [[JsTask]] per [[TaskDef]], which delegates execution to the pure-Kyo
  * [[kyo.test.runner.TestRunner]].
  *
  * The Scala.js test adapter runs one JS environment per sbt thread. The runner from [[kyo.test.runner.JsFramework.runner]] is the
  * controller, and the only one whose `done()` sbt logs. A task sbt executes on another thread runs in a worker from
  * [[kyo.test.runner.JsFramework.slaveRunner]], whose `send` the adapter routes to the controller's `receiveMessage`. So a worker (`send`
  * present) ships each suite's [[LeafRecord]]s to the controller, and the controller buffers its own leaves and every worker's.
  *
  * JS note: tasks are executed sequentially by the Scala.js test runner. The `leaves` buffer is a plain `ListBuffer` (no concurrent access
  * on single-threaded JS).
  */
final private[runner] class JsRunner(
    val args: Array[String],
    val remoteArgs: Array[String],
    val testClassLoader: ClassLoader,
    send: Maybe[String => Unit] = Absent
) extends Runner:

    private val parsedArgs: Args.Result = Args.parse(args)

    locally:
        parsedArgs match
            case Args.Result.Help =>
                java.lang.System.out.println(Args.usage)
            case Args.Result.Error(msg) =>
                java.lang.System.err.println(s"[kyo-test] CLI error: $msg")
            case Args.Result.Ok(_) =>
                EventLoopWatchdog.start()

    /** The flags as an overlay over each suite's own config; see `TestRunner.runReport`. */
    private[internal] val baseOverlay: RunConfig => RunConfig =
        parsedArgs match
            case Args.Result.Ok(parsed) => parsed.overlay
            case _                      => identity

    private[internal] val positionalArgs: Chunk[String] =
        parsedArgs match
            case Args.Result.Ok(parsed) => parsed.positional
            case _                      => Chunk.empty

    private val leaves =
        scala.collection.mutable.ListBuffer.empty[LeafRecord]

    private var unreadableMessages = 0

    private def record(report: TestReport): Unit =
        val records = LeafRecord.of(report)
        send match
            case Present(ship) => LeafRecord.encode(records).foreach(ship)
            case Absent        => kyo.discard(leaves ++= records)
    end record

    def tasks(taskDefs: Array[TaskDef]): Array[Task] =
        parsedArgs match
            case Args.Result.Ok(_) => taskDefs.map(td => new JsTask(td, baseOverlay, testClassLoader, record))
            case _                 => Array.empty

    /** Returns [[JsTask]] instances directly, avoiding polymorphic dispatch through [[sbt.testing.Task]] on Scala.js.
      *
      * The Scala.js linker traces all implementations of [[sbt.testing.Task.execute]] and would pull in ScalaTest's `TaskRunner` (which
      * calls `Await.result`) if the polymorphic interface is used at a call site. Using this method instead gives the linker a monomorphic
      * call to [[JsTask.execute]] only.
      */
    private[runner] def jsTasksTyped(taskDefs: Array[TaskDef]): Array[JsTask] =
        parsedArgs match
            case Args.Result.Ok(_) => taskDefs.map(td => new JsTask(td, baseOverlay, testClassLoader, record))
            case _                 => Array.empty

    /** Buffers the leaves a worker shipped with [[LeafRecord.encode]]. Never replies. */
    def receiveMessage(msg: String): Option[String] =
        LeafRecord.decode(msg) match
            case Present(records) => kyo.discard(leaves ++= records)
            case Absent           => unreadableMessages += 1
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
        new JsTask(deserializer(task), baseOverlay, testClassLoader, record)

    def done(): String =
        parsedArgs match
            case Args.Result.Error(msg) => msg
            case Args.Result.Help       => ""
            case Args.Result.Ok(_)      =>
                val summary = Summary.renderLeaves(Chunk.from(leaves), Chunk.empty, positionalArgs)
                if unreadableMessages == 0 then summary
                else
                    s"$summary\n warning: $unreadableMessages worker result message(s) could not be read; the counts above miss their leaves"
    end done

end JsRunner
