package kyo.scheduler

import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import scala.collection.mutable.PriorityQueue

final private class Queue[T](using ord: Ordering[T]) extends AtomicBoolean:

    private val queue = PriorityQueue[T]()

    private var items = 0

    def isEmpty() =
        size() == 0

    def size(): Int =
        VarHandle.acquireFence()
        items

    def add(value: T): Unit =
        modify {
            items += 1
            queue.addOne(value)
            ()
        }

    def offer(value: T): Boolean =
        tryModify {
            items += 1
            queue.addOne(value)
            true
        }

    def poll(): T =
        if isEmpty() then
            null.asInstanceOf[T]
        else
            modify {
                if isEmpty() then
                    null.asInstanceOf[T]
                else
                    items -= 1
                    queue.dequeue()
            }

    def addAndPoll(value: T): T =
        if isEmpty() then
            value
        else
            modify {
                if isEmpty() then value
                else
                    val r = queue.dequeue()
                    queue.addOne(value)
                    r
            }

    def stealingBy(to: Queue[T]): T =
        var t: T = null.asInstanceOf[T]
        !isEmpty() && tryModify {
            !isEmpty() && to.isEmpty() && to.tryModify {
                t = queue.dequeue()
                val s = size() - 1
                var i = s - Math.ceil(s.toDouble / 2).intValue()
                items -= i + 1
                to.items += i
                while i > 0 do
                    to.queue.addOne(queue.dequeue())
                    i -= 1
                true
            }
        }
        t
    end stealingBy

    def drain(f: T => Unit): Unit =
        if !isEmpty() then
            val tasks =
                modify {
                    items = 0
                    queue.dequeueAll
                }
            tasks.foreach(f)

    private inline def modify[T](inline f: => T): T =
        while !compareAndSet(false, true) do {}
        try f
        finally set(false)
    end modify

    private inline def tryModify[T](inline f: => Boolean): Boolean =
        compareAndSet(false, true) && {
            try f
            finally set(false)
        }

    override def toString = modify { s"Queue(${queue.mkString(",")})" }
end Queue
