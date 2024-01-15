package kyo.scheduler

import scala.collection.mutable.PriorityQueue
import scala.scalajs.js

object Scheduler {

  private val queue   = PriorityQueue[IOTask[_]]()
  private var running = false

  def schedule(t: IOTask[_]): Unit = {
    queue.enqueue(t)
    if (!running) {
      running = true
      js.Dynamic.global.setTimeout(
          () => {
            flush()
            running = false
          },
          0
      )
    }
  }

  def flush(): Unit = {
    while (queue.nonEmpty) {
      val task = queue.dequeue()
      task.run()
      if (task.reenqueue()) {
        queue.enqueue(task)
      }
    }
  }

}
