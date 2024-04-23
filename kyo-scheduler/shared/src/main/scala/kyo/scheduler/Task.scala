package kyo.scheduler

trait Task:

    @volatile private var state = 1 // Math.abs(state) => runtime; state < 0 => preempting

    private[kyo] def doRun(clock: InternalClock): Task.Result =
        val start = clock.currentMillis()
        try run(start, clock)
        finally state = (Math.abs(state) + clock.currentMillis() - start).toInt
    end doRun

    def doPreempt(): Unit =
        if state > 0 then
            state = -state

    final def preempt(): Boolean =
        state < 0

    def run(startMillis: Long, clock: InternalClock): Task.Result

    def runtime(): Int = Math.abs(state)

    private[kyo] def setRuntime(v: Int) =
        state = Math.max(state, v)
end Task

private[kyo] object Task:

    private val ordering = new Ordering[Task]:
        override def lt(x: Task, y: Task): Boolean =
            val r = y.runtime()
            r == 0 || r < x.runtime()
        def compare(x: Task, y: Task): Int =
            x.state - y.state

    inline given Ordering[Task] = ordering

    opaque type Result = Boolean
    val Preempted: Result = true
    val Done: Result      = false
    object Result:
        given CanEqual[Result, Result] = CanEqual.derived

    inline def apply(inline r: => Unit): Task =
        new Task:
            def run(startMillis: Long, clock: InternalClock) =
                r
                Task.Done
            end run
end Task
