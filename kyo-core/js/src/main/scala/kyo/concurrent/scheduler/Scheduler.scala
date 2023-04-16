package kyo.concurrent.scheduler

import scala.collection.mutable.Queue

object Scheduler {

  private val queue   = Queue[IOTask[_]]()
  private var running = false

  def schedule(t: IOTask[_]): Unit =
    queue.enqueue(t)
    if (!running) {
      running = true
      flush()
      running = false
    }

  def flush() =
    while (queue.nonEmpty) {
      val task = queue.dequeue()
      task.run()
    }
}
