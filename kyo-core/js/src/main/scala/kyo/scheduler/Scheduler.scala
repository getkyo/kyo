package kyo.scheduler

import kyo.*
import scala.collection.mutable
import scala.scalajs.concurrent.JSExecutionContext

object Scheduler:

    def schedule(t: Task): Unit =
        JSExecutionContext.queue.execute { () =>
            if t.run() == Task.Preempted then
                schedule(t)
        }
    end schedule

    def flush(): Unit = {}
end Scheduler
