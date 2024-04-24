package kyo.scheduler

trait Task:

    @volatile final private[kyo] var runtime = 0

    final private[kyo] def doRun(clock: InternalClock): Task.Result =
        val start = clock.currentMillis()
        try run(start, clock)
        finally runtime += (clock.currentMillis() - start).toInt
    end doRun

    def run(startMillis: Long, clock: InternalClock): Task.Result

    final private[kyo] def setRuntime(v: Int) =
        runtime = v
end Task

object Task:

    private val ordering = new Ordering[Task]:
        override def lt(x: Task, y: Task): Boolean =
            y.runtime < x.runtime
        def compare(x: Task, y: Task): Int =
            y.runtime - x.runtime

    inline given Ordering[Task] = ordering

    opaque type Result = Boolean
    inline def Preempted: Result = true
    inline def Done: Result      = false

    object Result:
        given CanEqual[Result, Result] = CanEqual.derived

    inline def apply(inline r: => Unit, runtime: Int = 0): Task =
        val task =
            new Task:
                def run(startMillis: Long, clock: InternalClock) =
                    r
                    Task.Done
                end run
        task.setRuntime(runtime)
        task
    end apply
end Task
