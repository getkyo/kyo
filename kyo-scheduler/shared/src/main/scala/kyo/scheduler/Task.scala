package kyo.scheduler

trait Task:

    @volatile private var state = 1 // Math.abs(state) => runtime; state < 0 => preempting

    def doPreempt(): Unit =
        val state = this.state
        if state > 0 then
            this.state = -state
    end doPreempt

    final def preempt(): Boolean =
        state < 0

    def run(startMillis: Long, clock: InternalClock): Task.Result

    def runtime(): Int =
        val state = this.state
        if state < 0 then -state
        else state
    end runtime

    private[kyo] def addRuntime(v: Int) =
        val state = this.state
        this.state =
            if state < 0 then -state + v
            else state + v
    end addRuntime
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
        t.addRuntime(runtime)
        t
    end apply
end Task
