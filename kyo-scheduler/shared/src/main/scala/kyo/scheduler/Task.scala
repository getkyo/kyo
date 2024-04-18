package kyo.scheduler

trait Task(initialRuntime: Int) extends Ordered[Task]:

    @volatile private var state = Math.max(1, initialRuntime) // Math.abs(state) => runtime; state < 0 => preempting

    private[kyo] def doRun(): Task.Result =
        val start = System.nanoTime()
        try run(start)
        finally state = (Math.abs(state) + System.nanoTime() - start).toInt
    end doRun

    private[kyo] def doPreempt(): Unit =
        if state > 0 then
            state = -state

    def preempt(): Boolean =
        state < 0

    def compare(that: Task) =
        (Math.abs(that.state) - Math.abs(state)).asInstanceOf[Int]

    def run(startNanos: Long): Task.Result

    def runtime(): Int = Math.abs(state)
end Task

private[kyo] object Task:
    opaque type Result = Boolean
    val Preempted: Result = true
    val Done: Result      = false
    object Result:
        given CanEqual[Result, Result] = CanEqual.derived

    inline def apply(inline r: => Unit): Task =
        new Task(0):
            def run(startNanos: Long) =
                r
                Task.Done
            end run
end Task
