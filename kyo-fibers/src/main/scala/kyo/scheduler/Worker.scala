package kyo.scheduler

import java.util.concurrent.Semaphore
import java.util.PriorityQueue
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicInteger

class Worker(r: Runnable)
    extends Thread(r) {

  val queue        = new Queue[Task]
  val maxParkNanos = 1_000_000_000

  @volatile var running           = false
  @volatile var currentTask: Task = null
  @volatile var cycle             = 0L

  def steal(w: Worker): Task =
    queue.steal(w.queue)

  def enqueue(t: Task): Boolean =
    val c = currentTask
    val r = running && (c == null || cycle == Coordinator.cycle()) && queue.offer(t)
    r

  def enqueueLocal(t: Task): Unit =
    queue.add(t)

  def load(): Int =
    var s = queue.size()
    if (currentTask != null)
      s += 1
    s

  def runWorker(init: Task) =
    var task      = init
    var parkNanos = 1024L
    var stopped   = false
    val stop = () =>
      stopped ||= Scheduler.stopWorker()
      stopped
    val preempt = () =>
      stop() || (Coordinator.cycle() != cycle && queue.size() > 0)
    running = true
    Scheduler.workers.add(this)
    while (!stop()) {
      if (task == null) {
        task = queue.poll()
      }
      if (task == null) {
        task = Scheduler.steal(this)
      }
      if (task == null) {
        Scheduler.park(this, parkNanos)
        if (parkNanos < maxParkNanos)
          parkNanos *= 2;
      }
      if (task != null) {
        parkNanos = 1024
        cycle = Coordinator.cycle()
        currentTask = task
        val done = task.run(preempt)
        currentTask = null
        if (!done) {
          task = queue.addAndPoll(task)
        } else {
          task = null
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
    s"Worker(thread=${getName},load=${load()},task=$currentTask"//,queue=$queue)"
}

object Worker {
  def apply(): Worker =
    Thread.currentThread() match {
      case w: Worker => w
      case _         => null
    }
}
