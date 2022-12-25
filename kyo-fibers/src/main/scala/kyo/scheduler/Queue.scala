package kyo.scheduler

import java.util.concurrent.Semaphore
import java.util.PriorityQueue
import java.lang.invoke.VarHandle
import java.util.concurrent.locks.ReentrantLock

class Queue[T <: Comparable[T]] {

  private var items = 0
  private val lock  = new ReentrantLock
  private val queue = new PriorityQueue[T]

  def size() =
    VarHandle.loadLoadFence()
    items

  def add(t: T): Unit =
    lock.lock()
    try {
      queue.add(t)
      items += 1
    } finally {
      lock.unlock()
    }

  def offer(t: T): Boolean =
    lock.tryLock() && {
      try {
        queue.add(t)
        items += 1
        true
      } finally {
        lock.unlock()
      }
    }

  def poll(): T =
    lock.lock()
    try {
      val r = queue.poll()
      if (r != null) {
        items -= 1
      }
      r
    } finally {
      lock.unlock()
    }

  def addAndPoll(t: T): T =
    lock.lock()
    try {
      queue.add(t)
      queue.poll()
    } finally {
      lock.unlock()
    }

  def steal(to: Queue[T]): T =
    val i    = items
    var t: T = null.asInstanceOf[T]
    if (items > 0 && lock.tryLock()) {
      try {
        if (to.lock.tryLock()) {
          try {
            t = queue.poll()
            val s = items - 1
            var i = s - (s / 2)
            items -= i + 1
            to.items += i
            while (i > 0) {
              to.queue.add(queue.poll())
              i -= 1
            }
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
    items = 0
    queue.forEach(f(_))
    queue.clear()
    lock.unlock()

  override def toString =
    queue.toString()
}
