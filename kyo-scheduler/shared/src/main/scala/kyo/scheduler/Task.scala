package kyo.scheduler

private[kyo] trait Task extends Ordered[Task]:
    def compare(that: Task) =
        (that.runtime() - runtime()).asInstanceOf[Int]
    def run(): Task.Result
    def runtime(): Int
    def preempt(): Unit
end Task

private[kyo] object Task:
    opaque type Result = Boolean
    val Preempted: Result = true
    val Done: Result      = false
    object Result:
        given CanEqual[Result, Result] = CanEqual.derived

    inline def apply(inline r: => Unit): Task =
        new Task:
            def runtime() = 1
            def preempt() = {}
            def run() =
                r
                Task.Done
            end run
end Task
