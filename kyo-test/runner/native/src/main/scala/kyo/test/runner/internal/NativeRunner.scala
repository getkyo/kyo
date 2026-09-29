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

/** Scala Native [[Runner]] coordinator for kyo-test (V3 new-runner path).
  *
  * Parses `args` into a [[RunConfig]] (via [[Args]]) and creates one [[NativeTask]] per [[TaskDef]], which delegates execution to the
  * pure-Kyo [[kyo.test.runner.TestRunner]].
  *
  * Scala Native's test adapter runs one test process per sbt thread. The runner from [[kyo.test.runner.NativeFramework.runner]] is the
  * controller, and the only one whose `done()` sbt logs. A task sbt executes on another thread runs in a worker from
  * [[kyo.test.runner.NativeFramework.slaveRunner]], whose `send` the adapter routes to the controller's `receiveMessage`. So a worker
  * (`send` present) ships each suite's [[LeafRecord]]s to the controller, and the controller queues its own leaves and every worker's; the
  * adapter delivers a worker's messages before it calls the controller's `done()`.
  *
  * The `leaves` queue is thread-safe; sbt may call `execute` on multiple tasks concurrently on Native (which has real threads).
  */
final private[runner] class NativeRunner(
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
                ()

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
        new java.util.concurrent.ConcurrentLinkedQueue[LeafRecord]()

    private val unreadableMessages = new java.util.concurrent.atomic.AtomicInteger(0)

    private def record(report: TestReport): Unit =
        val records = LeafRecord.of(report)
        send match
            case Present(ship) => LeafRecord.encode(records).foreach(ship)
            case Absent        => records.foreach(leaf => kyo.discard(leaves.add(leaf)))
    end record

    def tasks(taskDefs: Array[TaskDef]): Array[Task] =
        parsedArgs match
            case Args.Result.Ok(_) => taskDefs.map(td => new NativeTask(td, baseOverlay, testClassLoader, record))
            case _                 =>
                Array.empty

    /** Queues the leaves a worker shipped with [[LeafRecord.encode]]. Never replies. */
    def receiveMessage(msg: String): Option[String] =
        LeafRecord.decode(msg) match
            case Present(records) => records.foreach(leaf => kyo.discard(leaves.add(leaf)))
            case Absent           => kyo.discard(unreadableMessages.incrementAndGet())
        None
    end receiveMessage

    /** Serialise a task for inter-runner transfer.
      *
      * Required by the Scala Native test bridge. kyo-test does not support distributed task serialization, so the task is serialized using
      * its fully qualified class name.
      */
    def serializeTask(task: Task, serializer: sbt.testing.TaskDef => String): String =
        serializer(task.taskDef())

    /** Deserialise a task that was serialised by [[serializeTask]].
      *
      * Required by the Scala Native test bridge.
      */
    def deserializeTask(task: String, deserializer: String => sbt.testing.TaskDef): sbt.testing.Task =
        new NativeTask(deserializer(task), baseOverlay, testClassLoader, record)

    def done(): String =
        parsedArgs match
            case Args.Result.Error(msg) => msg
            case Args.Result.Help       => ""
            case Args.Result.Ok(_)      =>
                import scala.jdk.CollectionConverters.*
                val summary    = Summary.renderLeaves(Chunk.from(leaves.asScala), Chunk.empty, positionalArgs)
                val unreadable = unreadableMessages.get()
                if unreadable == 0 then summary
                else s"$summary\n warning: $unreadable worker result message(s) could not be read; the counts above miss their leaves"
    end done

end NativeRunner
