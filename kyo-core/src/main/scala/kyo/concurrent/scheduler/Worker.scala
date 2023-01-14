package kyo.concurrent.scheduler

import kyo.ios.Preempt
import kyo.concurrent.scheduler.IOTask

import java.util.Comparator
import java.util.PriorityQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

class Worker(r: Runnable)
    extends Thread(r) {

  val queue = Queue[IOTask[_]]

  @volatile var running                = false
  @volatile var currentTask: IOTask[_] = null
  @volatile var parkedThread: Thread   = null

  val delay = MovingStdDev(7)

  def park() =
    parkedThread = this
    LockSupport.parkNanos(this, 100_000_000)
    parkedThread = null

  def steal(w: Worker): IOTask[_] =
    queue.steal(w.queue)

  def enqueue(t: IOTask[_]): Boolean =
    running && {
      val curr = currentTask
      val ok   = (curr == null || !curr()) && queue.offer(t)
      if (ok) {
        LockSupport.unpark(parkedThread)
      }
      ok
    }

  def cycle() =
    val t = currentTask
    if (t != null && !queue.isEmpty()) {
      t.preempt()
    }

  def enqueueLocal(t: IOTask[_]): Unit =
    queue.add(t)

  def load(): Int =
    var s = queue.size()
    if (currentTask != null)
      s += 1
    s

  def runWorker(init: IOTask[_]) =
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
      if (task != null) {
        currentTask = task
        val done = task.run()
        currentTask = null
        if (!done) {
          task = queue.addAndPoll(task)
        } else {
          delay.observe(task.delay())
          task = null
        }
      } else {
        task = Scheduler.steal(this)
        if (task == null) {
          Scheduler.idle(this)
        }
      }
    }
    Scheduler.workers.remove(this)
    running = false
    if (task != null) {
      Scheduler.submit(task)
      task = null
    }
    queue.drain(Scheduler.submit)

  override def toString =
    s"Worker(thread=${getName},load=${load()},delay=${delay.avg()},task=$currentTask,queue.size=${queue.size()},frame=${this.getStackTrace()(0)})"
}

object Worker {
  def apply(): Worker =
    Thread.currentThread() match {
      case w: Worker => w
      case _         => null
    }
}
