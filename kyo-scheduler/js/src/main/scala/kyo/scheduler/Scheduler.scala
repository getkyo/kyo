package kyo.scheduler

import scala.scalajs.concurrent.JSExecutionContext

object Scheduler {
    val get = new Scheduler
}

class Scheduler {

    private val clock = new InternalClock()

    def schedule(t: Task): Unit = {
        JSExecutionContext.queue.execute { () =>
            if (t.run(clock.currentMillis(), clock) == Task.Preempted)
                schedule(t)
        }
    }

    def flush(): Unit = {}

}
