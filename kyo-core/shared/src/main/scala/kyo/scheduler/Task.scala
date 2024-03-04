package kyo.scheduler

import kyo.Logs
import scala.util.control.NonFatal

trait Task extends Ordered[Task]:
    def compare(that: Task) =
        (that.runtime() - runtime()).asInstanceOf[Int]
    def run(): Task.Result
    def runtime(): Int
    def preempt(): Unit
end Task

object Task:
    opaque type Result = Boolean
    val Preempted: Result = true
    val Done: Result      = false

    inline def apply(inline r: => Unit): Task =
        new Task:
            def runtime() = 0
            def preempt() = {}
            def run() =
                try r
                catch
                    case ex if NonFatal(ex) =>
                        Logs.logger.error("Failed task.")
                end try
                Task.Done
            end run
end Task
