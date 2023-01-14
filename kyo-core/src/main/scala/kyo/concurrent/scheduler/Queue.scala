package kyo.concurrent.scheduler

import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.PriorityQueue

private final class Queue[T <: Comparable[T]] extends AtomicBoolean {

  private val queue = PriorityQueue[T]()

  @volatile private var items = 0

  def isEmpty() =
    items == 0

  def size(): Int =
    items

  def add(t: T): Unit =
    modify {
      queue.addOne(t)
    }

  def offer(t: T): Boolean =
    tryModify {
      queue.addOne(t)
      true
    }

  def poll(): T =
    if (isEmpty()) {
      null.asInstanceOf[T]
    } else {
      modify {
        if (isEmpty()) null.asInstanceOf[T]
        else queue.dequeue()
      }
    }

  def addAndPoll(t: T): T =
    if (isEmpty()) {
      t
    } else {
      modify {
        queue.addOne(t)
        queue.dequeue()
      }
    }

  def steal(to: Queue[T]): T =
    var t: T = null.asInstanceOf[T]
    !isEmpty() && tryModify {
      !isEmpty() && to.isEmpty() && to.tryModify {
        t = queue.dequeue()
        val s = size() - 1
        var i = s - (s / 2)
        while (i > 0) {
          to.queue.addOne(queue.dequeue())
          i -= 1
        }
        true
      }
    }
    t

  def drain(f: T => Unit): Unit =
    modify {
      queue.foreach(f)
      queue.clear()
    }

  private inline def modify[T](inline f: => T): T =
    while (!compareAndSet(false, true)) {}
    try f
    finally {
      items = queue.size
      set(false)
    }

  private inline def tryModify[T](inline f: => Boolean): Boolean =
    compareAndSet(false, true) && {
      try f
      finally {
        items = queue.size
        set(false)
      }
    }

  override def toString = modify { s"Queue(${queue.mkString(",")})" }
}
