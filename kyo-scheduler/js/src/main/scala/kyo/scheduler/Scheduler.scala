package kyo.scheduler

import org.scalajs.macrotaskexecutor.MacrotaskExecutor

object Scheduler {
    val get = new Scheduler
}

class Scheduler {

    private val clock = new InternalClock()

    def schedule(t: Task): Unit =
        MacrotaskExecutor.execute { () =>
            if (t.run(clock.currentMillis(), clock) == Task.Preempted)
                schedule(t)
        }

    def flush(): Unit = {}

    def reject(): Boolean = false

    def reject(key: String): Boolean = false

    def reject(key: Int): Boolean = false

}
