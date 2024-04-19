package kyo.scheduler

import scala.scalajs.concurrent.JSExecutionContext

object Scheduler:
    lazy val get = new Scheduler

class Scheduler:

    private val clock = InternalClock()

    def schedule(t: Task): Unit =
        JSExecutionContext.queue.execute { () =>
            if t.run(0, clock) == Task.Preempted then
                schedule(t)
        }
    end schedule

    def flush(): Unit = {}

end Scheduler
