package kyo.scheduler

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
end Task
