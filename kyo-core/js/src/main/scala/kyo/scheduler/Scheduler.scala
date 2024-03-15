package kyo.scheduler

import kyo.*
import scala.collection.mutable.PriorityQueue
import scala.scalajs.js

object Scheduler:

    private val queue   = PriorityQueue[IOTask[?]]()
    private var running = false

    def schedule(t: IOTask[?]): Unit =
        queue.enqueue(t)
        if !running then
            running = true
            discard(
                js.Dynamic.global.setTimeout(
                    () =>
                        flush()
                        running = false
                    ,
                    0
                )
            )
        end if
    end schedule

    def flush(): Unit =
        while queue.nonEmpty do
            val task = queue.dequeue()
            if task.run() == Task.Preempted then
                queue.enqueue(task)
end Scheduler
