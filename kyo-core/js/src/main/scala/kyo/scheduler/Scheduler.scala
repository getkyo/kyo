package kyo.scheduler

import scala.collection.mutable.PriorityQueue
import scala.scalajs.js

object Scheduler:

    private val queue   = PriorityQueue[IOTask[_]]()
    private var running = false

    def schedule(t: IOTask[?]): Unit =
        queue.enqueue(t)
        if !running then
            running = true
            js.Dynamic.global.setTimeout(
                () =>
                    flush()
                    running = false
                ,
                0
            )
        end if
    end schedule

    def flush(): Unit =
        while queue.nonEmpty do
            val task = queue.dequeue()
            task.run()
            if task.reenqueue() then
                queue.enqueue(task)
end Scheduler
