package kyo.scheduler

import kyo.scheduler.util.Flag
import org.scalajs.macrotaskexecutor.MacrotaskExecutor

object Scheduler {
    val get = new Scheduler
}

class Scheduler {

    private val timeSlice = Flag("timeSliceMs", 10)
    private val clock     = new InternalClock()

    def schedule(t: Task): Unit =
        MacrotaskExecutor.execute { () =>
            val now = clock.currentMillis()
            if (t.run(now, clock, now + timeSlice) == Task.Preempted)
                schedule(t)
        }

    def flush(): Unit = {}

    def reject(): Boolean = false

    def reject(key: String): Boolean = false

    def reject(key: Int): Boolean = false

}
