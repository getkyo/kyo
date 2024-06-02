package kyo.scheduler

import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicBoolean
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

    def add(value: T): Unit = {
        lock()
        try {
            items += 1
            queue += value
            ()
        } finally
            unlock()
    }

    def offer(value: T): Boolean =
        tryLock() && {
            try {
                items += 1
                queue += value
                true
            } finally
                unlock()
        }

    def poll(): T =
        if (isEmpty())
            null.asInstanceOf[T]
        else {
            lock()
            try {
                if (isEmpty())
                    null.asInstanceOf[T]
                else {
                    items -= 1
                    queue.dequeue()
                }
            } finally
                unlock()
        }

    def addAndPoll(value: T): T =
        if (isEmpty())
            value
        else {
            lock()
            try {
                if (isEmpty()) value
                else {
                    val r = queue.dequeue()
                    queue += value
                    r
                }
            } finally
                unlock()
        }

    def stealingBy(to: Queue[T]): T = {
        var t: T = null.asInstanceOf[T]
        !isEmpty() && tryLock() && {
            try {
                !isEmpty() && to.isEmpty() && to.tryLock() && {
                    try {
                        t = queue.dequeue()
                        val s = size() - 1
                        var i = s - Math.ceil(s.toDouble / 2).intValue()
                        items -= i + 1
                        to.items += i
                        while (i > 0) {
                            to.queue += queue.dequeue()
                            i -= 1
                        }
                        true
                    } finally
                        to.unlock()
                }
            } finally
                unlock()
        }
        t
    }

    def drain(f: T => Unit): Unit =
        if (!isEmpty()) {
            val tasks = {
                lock()
                try {
                    items = 0
                    queue.dequeueAll
                } finally
                    unlock()
            }
            tasks.foreach(f)
        }

    private def lock(): Unit =
        while (!compareAndSet(false, true)) {}

    private def tryLock(): Boolean =
        compareAndSet(false, true)

    private def unlock(): Unit =
        set(false)
}
