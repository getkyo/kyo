package kyo.scheduler

import java.util.Comparator
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.LockSupport
import scala.annotation.tailrec
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLongFieldUpdater
import java.lang.invoke.VarHandle
import scala.util.Random
import java.util.concurrent.atomic.AtomicLong

object test extends App {
  Executors.newSingleThreadExecutor().execute { () =>
    while (true) {
      Thread.sleep(1000)
      println(Scheduler)
      println(Coordinator)
      println(taskk)
    }
  }

  for (_ <- 0 until 200)
    Scheduler(taskk())
  LockSupport.park()
}

object taskk {
  val prev: Long = 0
  override def toString =
    s"latency=${latency},tasks=${tasks.getAndSet(0)},conc=$conc"
  @volatile var latency = 0L
  val conc  = new AtomicInteger
  val tasks = new AtomicLong
  def apply(): Preemptable = new Preemptable {
    def run(preempt: () => Boolean): Preemptable =
      var r = 0d
      var i = 0
      conc.incrementAndGet()
      val start = System.nanoTime()
      while (!preempt() && i < 100_000) {
        r /= Random.nextDouble()
        // if (i == 50_000)
        //   Thread.sleep(1000)
        i += 1
      }
      latency = System.nanoTime() - start
      conc.decrementAndGet()
      tasks.incrementAndGet()
      if (i == 100_000)
        Scheduler(taskk())
        Preemptable.Done
      else
        this
  }
}
