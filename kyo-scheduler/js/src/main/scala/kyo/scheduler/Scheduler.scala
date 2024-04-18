package kyo.scheduler

import scala.scalajs.concurrent.JSExecutionContext

object Scheduler:
    lazy val get = new Scheduler

class Scheduler:

    def schedule(t: Task): Unit =
        JSExecutionContext.queue.execute { () =>
            if t.run(0) == Task.Preempted then
                schedule(t)
        }
    end schedule

    def flush(): Unit = {}

end Scheduler
