package kyo.scheduler

trait Task {

    @volatile private var state = 1 // Math.abs(state) => runtime; state < 0 => preempting

    def doPreempt(): Unit = {
        val state = this.state
        if (state > 0)
            this.state = -state
    }

    protected def shouldPreempt(): Boolean =
        state < 0

    def run(startMillis: Long, clock: InternalClock): Task.Result

    private def runtime(): Int = {
        val state = this.state
        if (state < 0) -state
        else state
    }

    private[kyo] def addRuntime(v: Int) = {
        val state = this.state
        this.state =
            if (state < 0) -state + v
            else state + v
    }
}

object Task {

    implicit val taskOrdering: Ordering[Task] =
        new Ordering[Task] {
            def compare(x: Task, y: Task) =
                y.runtime() - x.runtime()
        }

    type Result = Boolean
    val Preempted: Result = true
    val Done: Result      = false

    def apply(r: => Unit): Task =
        apply(r, 0)

    def apply(r: => Unit, runtime: Int): Task = {
        val t =
            new Task {
                def run(startMillis: Long, clock: InternalClock) = {
                    r
                    Task.Done
                }
            }
        t.addRuntime(runtime)
        t
    }
}
