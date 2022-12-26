package kyo.scheduler

import java.util.concurrent.Semaphore
import java.util.PriorityQueue
import java.lang.invoke.VarHandle
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

class Queue[T <: Comparable[T]] {

  private val items = new AtomicInteger
  private val lock  = new ReentrantLock
  private val queue = new PriorityQueue[T]

  def size(): Int =
    items.get()

  def add(t: T): Unit =
    lock.lock()
    try {
      queue.add(t)
      items.lazySet(queue.size())
    } finally {
      lock.unlock()
    }

  def offer(t: T): Boolean =
    lock.tryLock() && {
      try {
        queue.add(t)
        items.lazySet(queue.size())
        true
      } finally {
        lock.unlock()
      }
    }

  def poll(): T =
    lock.lock()
    try {
      val r = queue.poll()
      items.lazySet(queue.size())
      r
    } finally {
      lock.unlock()
    }

  def addAndPoll(t: T): T =
    lock.lock()
    try {
      queue.add(t)
      val r = queue.poll()
      items.lazySet(queue.size())
      r
    } finally {
      lock.unlock()
    }

  def steal(to: Queue[T]): T =
    val i    = items.get()
    var t: T = null.asInstanceOf[T]
    if (items.get() > 0 && lock.tryLock()) {
      try {
        if (to.lock.tryLock()) {
          try {
            t = queue.poll()
            val s = items.get() - 1
            var i = s - (s / 2)
            while (i > 0) {
              to.queue.add(queue.poll())
              i -= 1
            }
            items.lazySet(queue.size())
            to.items.lazySet(to.queue.size())
          } finally {
            to.lock.unlock()
          }
        }
      } finally {
        lock.unlock()
      }
    }
    t

  def drain(f: T => Unit): Unit =
    lock.lock()
    items.set(0)
    queue.forEach(f(_))
    queue.clear()
    lock.unlock()

  override def toString =
    queue.toString()
}
