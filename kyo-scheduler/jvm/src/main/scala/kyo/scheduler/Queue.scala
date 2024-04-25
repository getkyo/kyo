package kyo.scheduler

import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import scala.collection.mutable.PriorityQueue

final private class Queue[T](implicit ord: Ordering[T]) extends AtomicBoolean {

    private val queue = PriorityQueue[T]()

    private var items = 0

    def isEmpty() =
        size() == 0

    def size(): Int = {
        VarHandle.acquireFence()
        items
    }

    def add(value: T): Unit =
        modify {
            items += 1
            queue.enqueue(value)
            ()
        }

    def offer(value: T): Boolean =
        tryModify {
            items += 1
            queue.enqueue(value)
            true
        }

    def poll(): T =
        if (isEmpty())
            null.asInstanceOf[T]
        else
            modify {
                if (isEmpty())
                    null.asInstanceOf[T]
                else {
                    items -= 1
                    queue.dequeue()
                }
            }

    def addAndPoll(value: T): T =
        if (isEmpty())
            value
        else
            modify {
                if (isEmpty()) value
                else {
                    val r = queue.dequeue()
                    queue.enqueue(value)
                    r
                }
            }

    def stealingBy(to: Queue[T]): T = {
        var t: T = null.asInstanceOf[T]
        !isEmpty() && tryModify {
            !isEmpty() && to.isEmpty() && to.tryModify {
                t = queue.dequeue()
                val s = size() - 1
                var i = s - Math.ceil(s.toDouble / 2).intValue()
                items -= i + 1
                to.items += i
                while (i > 0) {
                    to.queue.enqueue(queue.dequeue())
                    i -= 1
                }
                true
            }
        }
        t
    }

    def drain(f: T => Unit): Unit = {
        val tasks =
            modify {
                items = 0
                queue.dequeueAll
            }
        tasks.foreach(f)
    }

    private def modify[T](f: => T): T = {
        while (!compareAndSet(false, true)) {}
        try f
        finally set(false)
    }

    private def tryModify[T](f: => Boolean): Boolean =
        compareAndSet(false, true) && {
            try f
            finally set(false)
        }
}
