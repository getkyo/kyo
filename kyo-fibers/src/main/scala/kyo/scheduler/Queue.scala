package kyo.scheduler

import java.util.concurrent.Semaphore
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

class Queue[T <: Comparable[T]] {

  private val _size = new AtomicInteger
  private val lock  = new ReentrantLock
  private val queue = new PriorityQueue[T]

  def size(): Int =
    _size.get()

  private def updateSize() =
    _size.lazySet(queue.size())

  def add(t: T): Unit =
    lock.lock()
    try {
      queue.add(t)
      updateSize()
    } finally {
      lock.unlock()
    }

  def offer(t: T): Boolean =
    lock.tryLock() && {
      try {
        queue.add(t)
        updateSize()
        true
      } finally {
        lock.unlock()
      }
    }

  def poll(): T =
    lock.lock()
    try {
      val r = queue.poll()
      updateSize()
      r
    } finally {
      lock.unlock()
    }

  def addAndPoll(t: T): T =
    lock.lock()
    try {
      queue.add(t)
      val r = queue.poll()
      updateSize()
      r
    } finally {
      lock.unlock()
    }

  def steal(to: Queue[T]): T =
    var t: T = null.asInstanceOf[T]
    if (_size.get() > 0 && lock.tryLock()) {
      try {
        if (to.lock.tryLock()) {
          try {
            t = queue.poll()
            val s = _size.get() - 1
            var i = s - (s / 2)
            while (i > 0) {
              to.queue.add(queue.poll())
              i -= 1
            }
            updateSize()
            to.updateSize()
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
    queue.forEach(f(_))
    queue.clear()
    updateSize()
    lock.unlock()

  override def toString =
    queue.toString()
}
