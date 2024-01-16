package kyo.scheduler

import kyo.iosInternal._

import java.util.Comparator
import java.util.PriorityQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.CopyOnWriteArrayList
import kyo.scheduler.IOTask
import kyo.scheduler.Queue
import kyo.scheduler.Scheduler

private final class Worker(r: Runnable)
    extends Thread(r) {

  private val queue = new Queue[IOTask[_]]()

  @volatile private var running                = false
  @volatile private var currentTask: IOTask[_] = null
  @volatile private var parkedThread: Thread   = null

  private val schedule = (t: IOTask[_]) => Scheduler.schedule(t, this)

  def park() = {
    parkedThread = this
    LockSupport.parkNanos(this, 1000000L)
    parkedThread = null
  }

  def steal(thief: Worker): IOTask[_] =
    queue.steal(thief.queue)

  def enqueueLocal(t: IOTask[_]): Boolean =
    running && queue.offer(t)

  def enqueue(t: IOTask[_]): Boolean =
    running && queue.offer(t) && {
      LockSupport.unpark(parkedThread)
      true
    }

  def cycle(): Unit = {
    val t = currentTask
    if (t != null && !queue.isEmpty()) {
      t.preempt()
    }
  }

  def flush(): Unit =
    queue.drain(schedule)

  def load(): Int = {
    var s = queue.size()
    if (currentTask != null)
      s += 1
    s
  }

  def runWorker(init: IOTask[_]) = {
    var task = init
    def stop() =
      !running || {
        val stop = Scheduler.stopWorker()
        if (stop) {
          running = false
        }
        stop
      }
    running = true
    Worker.all.add(this)
    while (!stop()) {
      if (task == null) {
        task = queue.poll()
      }
      if (task != null) {
        currentTask = task
        task.run()
        currentTask = null
        if (task.reenqueue()) {
          task = queue.addAndPoll(task)
        } else {
          task = null
        }
      } else {
        task = Scheduler.steal(this)
        if (task == null) {
          Scheduler.idle(this)
        }
      }
    }
    Worker.all.remove(this)
    running = false
    if (task != null) {
      queue.add(task)
    }
    flush()
  }

  override def toString =
    s"Worker(thread=${getName},load=${load()},task=$currentTask,queue.size=${queue.size()},frame=${this.getStackTrace()(0)})"
}

private object Worker {
  private[kyo] val all = new CopyOnWriteArrayList[Worker]

  def apply(): Worker =
    Thread.currentThread() match {
      case w: Worker => w
      case _         => null
    }
}
