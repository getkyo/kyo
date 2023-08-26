package kyo.concurrent.scheduler

import kyo._
import kyo.ios._

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import scala.annotation.tailrec
import org.jctools.queues.MpmcUnboundedXaddArrayQueue

private[kyo] object Scheduler {

  private val coreWorkers = Flag(
      "coreWorkers",
      Math.ceil(Runtime.getRuntime().availableProcessors().toDouble / 2).intValue()
  )

  @volatile
  private var concurrencyLimit = coreWorkers
  private val concurrency      = new AtomicInteger(0)

  private[scheduler] val workers = new CopyOnWriteArrayList[Worker]

  private val idle = new MpmcUnboundedXaddArrayQueue[Worker](8)
  private val pool = Executors.newCachedThreadPool(Threads("kyo-worker", new Worker(_)))

  startWorkers()
  Coordinator.load()

  def removeWorker(): Unit =
    if (concurrencyLimit > coreWorkers)
      concurrencyLimit = Math.max(1, concurrency.get() - 1)

  def addWorker(): Unit =
    concurrencyLimit = Math.max(concurrencyLimit, concurrency.get()) + 1
    startWorkers()

  private def startWorkers(): Unit = {
    var c = concurrency.get()
    while (c < concurrencyLimit && concurrency.compareAndSet(c, c + 1)) {
      pool.execute(() => Worker().runWorker(null))
      c = concurrency.get()
    }
  }

  def flush(): Unit = {
    val w = Worker()
    if (w != null) {
      w.flush()
    }
  }

  def schedule(t: IOTask[_]): Unit = {
    val w = Worker()
    if (w != null && w.enqueueLocal(t)) {
      return
    }
    schedule(t, w)
  }

  @tailrec private[concurrent] def schedule(t: IOTask[_], submitter: Worker): Unit = {
    val w = idle.poll()
    if (w != null) {
      val ok = w.enqueue(t)
      if (ok) {
        return
      }
    }
    var w0: Worker = randomWorker(submitter)
    var w1: Worker = randomWorker(submitter)
    if (w0.load() > w1.load()) {
      val w = w0
      w0 = w1
      w1 = w
    }
    if (!w0.enqueue(t) && !w1.enqueue(t)) {
      schedule(t, submitter)
    }
  }

  def steal(thief: Worker): IOTask[_] = {
    // p2c load stealing
    var r: IOTask[_] = null
    var w0: Worker   = randomWorker(thief)
    var w1: Worker   = randomWorker(thief)
    if (w0.load() < w1.load()) {
      val w = w0
      w0 = w1
      w1 = w
    }
    r = w0.steal(thief)
    if (r == null) {
      r = w1.steal(thief)
    }
    r
  }

  def loadAvg(): Double = {
    var sum = 0L
    val it  = workers.iterator()
    var c   = 0
    while (it.hasNext()) {
      sum += it.next().load()
      c += 1
    }
    sum.doubleValue() / c
  }

  def cycle(): Unit =
    workers.forEach(_.cycle())

  def idle(w: Worker): Unit =
    if (w.load() == 0) {
      idle.add(w)
      w.park()
    }

  def stopWorker(): Boolean = {
    val c = concurrency.get()
    c > concurrencyLimit && concurrency.compareAndSet(c, c - 1)
  }

  private def randomWorker(besides: Worker): Worker = {
    var w: Worker = null
    while (w == null || w == besides) {
      try {
        w = workers.get(XSRandom.nextInt(workers.size()))
      } catch {
        case _: ArrayIndexOutOfBoundsException | _: IllegalArgumentException =>
      }
    }
    w
  }

  override def toString =
    s"Scheduler(loadAvg=${loadAvg()},concurrency=$concurrency,limit=$concurrencyLimit)"

}
