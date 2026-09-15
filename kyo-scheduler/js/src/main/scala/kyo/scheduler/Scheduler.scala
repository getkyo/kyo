package kyo.scheduler

import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import scala.concurrent.ExecutionContext

object Scheduler {
    val get = new Scheduler
}

class Scheduler {

    private val timeSlice = timeSliceMs()
    private val clock     = new InternalClock()

    def schedule(t: Task): Unit =
        MacrotaskExecutor.execute { () =>
            val now = clock.currentMillis()
            if (t.run(now, clock, now + timeSlice) == Task.Preempted)
                schedule(t)
        }

    // The jvm-native scheduler schedules onto a worker OTHER than the caller's so the task is not picked up
    // re-entrantly on the current worker. On JS the scheduler is single-threaded and `schedule` always defers
    // to the macrotask queue (never runs the task inline on the caller's stack), so there is no current worker
    // to exclude: `scheduleExcludingCurrent` is `schedule`.
    def scheduleExcludingCurrent(t: Task): Unit = schedule(t)

    def asExecutionContext: ExecutionContext = MacrotaskExecutor

    def flush(): Unit = {}

    def reject(): Boolean = false

    def reject(key: String): Boolean = false

    def reject(key: Int): Boolean = false

    def notifyInterrupt(): Unit = {}

}
