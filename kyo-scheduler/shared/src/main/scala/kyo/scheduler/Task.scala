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

object Task:

    private val ordering = new Ordering[Task]:
        def compare(x: Task, y: Task) =
            y.runtime() - x.runtime()

    inline given Ordering[Task] = ordering

    opaque type Result = Boolean
    val Preempted: Result = true
    val Done: Result      = false
    object Result:
        given CanEqual[Result, Result] = CanEqual.derived

    inline def apply(inline r: => Unit): Task =
        apply(r, 0)

    inline def apply(inline r: => Unit, inline runtime: Int): Task =
        val t =
            new Task:
                def run(startMillis: Long, clock: InternalClock) =
                    r
                    Task.Done
                end run
        t.setRuntime(runtime)
        t
    end apply
end Task
