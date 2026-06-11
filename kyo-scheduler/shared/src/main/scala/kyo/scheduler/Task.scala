package kyo.scheduler

trait Task {
    import Task.State.*

    @volatile private var state: Task.State = Task.State.init

    def doPreempt(): Unit = {
        val s = this.state
        if (!s.preempting)
            this.state = s.preempt
    }

    protected def shouldPreempt(): Boolean =
        state.preempting

    def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result

    /** Whether the BlockingMonitor should dispatch Thread.interrupt() for this task.
      *
      * Defaults to false — plain tasks are never interrupted. IOTask overrides this to derive interruption from its own promise state, the
      * single source of truth for fiber interruption.
      */
    def needsInterrupt(): Boolean = false

    private[scheduler] def runtime(): Int =
        state.runtime

    def addRuntime(v: Int) =
        this.state = state.addRuntime(v)

    /** Drops accumulated runtime to the minimum so this task is scheduled ahead of tasks that have
      * run longer. Used when a fiber is interrupted: it must run promptly to observe the interrupt
      * and release its worker and finalizers, but its accumulated runtime would otherwise
      * deprioritize it.
      */
    def resetRuntime(): Unit =
        this.state = state.resetRuntime
}

object Task {

    /** Bit-packed task state encoding preemption and runtime priority in a single Int.
      *
      *   - **bits 0-30**: runtime — accumulated execution time, used for priority ordering (lower = higher priority)
      *   - **bit 31 (sign)**: preempting — set by `preempt` (a bit-set, not a negation, so it is valid even at runtime 0) to signal the
      *     task should yield at the next effect boundary so the worker can serve other queued tasks (time-slice fairness)
      *
      * `state` is mutated by non-atomic read-modify-writes from two threads — the worker (addRuntime) and the coordinator (doPreempt, via
      * Worker.checkStalling). A lost update can only drop a best-effort time-slice preemption (retried on the next checkStalling pass) or a
      * runtime increment (a priority heuristic), so plain @volatile is sufficient. Fiber interruption is intentionally NOT tracked here: it
      * is observed from IOPromise's CAS-updated state, so no cross-thread interrupt write exists to be lost.
      */
    private[scheduler] type State = Int

    private[scheduler] object State {

        /** Initial state: runtime = 1, not preempting. */
        def init: State = 1

        implicit class StateOps(private val s: State) extends AnyVal {
            def preempting: Boolean = s < 0
            def runtime: Int        = s & Int.MaxValue

            /** Sets the preemption flag by setting bit 31. No-op if already preempting. Uses a bit-set rather than
              * negation so it works for every runtime including 0: negating 0 yields 0 (`-0 == 0`), which is not
              * negative, so a 0-runtime task could never be flagged for preemption and would hog its worker until
              * it completed or was interrupted, wedging the scheduler under a 0-runtime CPU-bound loop.
              */
            def preempt: State = s | Int.MinValue

            /** Adds execution time, clearing the preemption flag (clears bit 31): a time-slice preemption has been
              * consumed by the time runtime is recorded.
              */
            def addRuntime(v: Int): State =
                (s & Int.MaxValue) + v

            /** Drops accumulated runtime to 0, the minimum, giving the task the highest scheduling priority so an
              * interrupted fiber is rescheduled ahead of all others (including freshly-submitted runtime-1 tasks) to
              * observe the interrupt and run its finalizers. Safe at 0 because preemption is a dedicated bit set by
              * `preempt`, not the sign of a negation.
              */
            def resetRuntime: State = 0
        }
    }

    type Result = Boolean
    val Preempted: Result = true
    val Done: Result      = false

    def apply(runnable: Runnable): Task =
        new Task {
            def run(startMillis: Long, clock: InternalClock, deadline: Long) = {
                runnable.run()
                Task.Done
            }
        }

    def apply(r: => Unit): Task =
        apply(r, 0)

    def apply(r: => Unit, runtime: Int): Task = {
        val t =
            new Task {
                def run(startMillis: Long, clock: InternalClock, deadline: Long) = {
                    r
                    Task.Done
                }
            }
        t.addRuntime(runtime)
        t
    }
}
