package kyo.scheduler

trait Task {

    @volatile private var state: Task.State = Task.State.init

    def doPreempt(): Unit = {
        val s = this.state
        if (!s.preempting)
            this.state = s.preempt
    }

    protected def shouldPreempt(): Boolean =
        state.preempting

    def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result

    /** Marks this task for thread interrupt dispatch by the BlockingMonitor.
      *
      * Called by IOTask.interrupt when a fiber is interrupted. Sets the needsInterrupt flag and also forces preemption so the task yields at
      * the next effect boundary. The BlockingMonitor periodically checks this flag and, if the worker thread's CPU time is flat (thread is
      * blocked), dispatches Thread.interrupt() to wake it.
      */
    def requestInterrupt(): Unit =
        this.state = state.interrupt

    /** Whether this task has been marked for thread interrupt dispatch. */
    def needsInterrupt(): Boolean =
        state.needsInterrupt

    protected def runtime(): Int =
        state.runtime

    def addRuntime(v: Int) =
        this.state = state.addRuntime(v)
}

object Task {

    /** Bit-packed task state encoding preemption, interrupt request, and runtime priority in a single Int.
      *
      * The encoding avoids additional volatile fields on the hot path by packing three concerns into one value:
      *   - '''bit 0''': needsInterrupt — whether the BlockingMonitor should dispatch Thread.interrupt() for this task
      *   - '''bits 1-30''': runtime — accumulated execution time, used for priority ordering (lower = higher priority)
      *   - '''bit 31 (sign)''': preempting — when negative, the task should yield at the next effect boundary
      *
      * Key invariants:
      *   - Negation preserves bit 0 in two's complement (odd numbers stay odd when negated), so preemption doesn't clear the interrupt flag
      *   - addRuntime shifts by 1 (`v << 1`) to write into bits 1-30 without affecting the interrupt bit
      *   - addRuntime always produces a positive (non-preempting) state, since runtime is added after task execution when preemption is no
      *     longer relevant
      */
    private[scheduler] opaque type State = Int

    private[scheduler] object State {
        private inline def InterruptMask: Int = 1

        /** Initial state: runtime = 1, no interrupt, not preempting. */
        inline def init: State = 2

        extension (s: State)
            inline def preempting: Boolean     = s < 0
            inline def needsInterrupt: Boolean = (s & InterruptMask) != 0
            inline def runtime: Int            = (if (s < 0) -s else s) >>> 1

            /** Sets the preemption flag (negates). No-op if already preempting. */
            inline def preempt: State = -s

            /** Sets both interrupt flag and preemption. */
            inline def interrupt: State =
                val abs = if (s < 0) -s else s
                -(abs | InterruptMask)

            /** Adds execution time. Clears preemption (flips to positive) since runtime is
              * added after task execution when preemption is no longer relevant.
              */
            inline def addRuntime(v: Int): State =
                (if (s < 0) -s else s) + (v << 1)
        end extension
    }

    implicit val taskOrdering: Ordering[Task] =
        new Ordering[Task] {
            def compare(x: Task, y: Task) =
                y.runtime() - x.runtime()
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
