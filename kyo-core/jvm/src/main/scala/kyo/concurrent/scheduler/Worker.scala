package kyo.concurrent.scheduler

import kyo.ios.Preempt

import java.util.Comparator
import java.util.PriorityQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.LockSupport

private final class Worker(r: Runnable)
    extends Thread(r) {

  private val queue = new Queue[IOTask[_]]()

  @volatile private var running                = false
  @volatile private var currentTask: IOTask[_] = null
  @volatile private var parkedThread: Thread   = null

  def park() =
    parkedThread = this
    LockSupport.parkNanos(this, 100000000L)
    parkedThread = null

  def steal(w: Worker): IOTask[_] =
    queue.steal(w.queue)

  def enqueueLocal(t: IOTask[_]): Boolean =
    queue.offer(t)

  def enqueue(t: IOTask[_]): Boolean =
    isAvailable() && queue.offer(t) && {
      LockSupport.unpark(parkedThread)
      true
    }

  def isAvailable(): Boolean =
    running && {
      val t = currentTask
      (t == null || !t())
    }

  def cycle(): Unit = {
    val t = currentTask
    if (t != null && !queue.isEmpty()) {
      t.preempt()
    }
  }

  def flush(): Unit =
    queue.drain(Scheduler.submit)

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
    Scheduler.workers.add(this)
    while (!stop()) {
      if (task == null) {
        task = queue.poll()
      }
      if (task == null) {
        task = Scheduler.steal(this)
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
        def s     = Scheduler.workers.size()
        var spins = (((s & 0xffff) << 1) | 0xf)
        while (spins > 0 && task == null) {
          Thread.onSpinWait()
          task = queue.poll()
          spins -= 1
        }
        if (task == null) {
          Scheduler.idle(this)
        }
      }
    }
    Scheduler.workers.remove(this)
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
  def apply(): Worker =
    Thread.currentThread() match {
      case w: Worker => w
      case _         => null
    }
}
