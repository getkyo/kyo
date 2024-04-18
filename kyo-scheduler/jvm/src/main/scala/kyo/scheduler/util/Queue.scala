package kyo.scheduler.util

import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import scala.collection.mutable.PriorityQueue

final private[kyo] class Queue[T](using ord: Ordering[T]) extends AtomicBoolean:

    private val queue = PriorityQueue[T]()

    private var items = 0

    def isEmpty() =
        size() == 0

    def size(): Int =
        VarHandle.acquireFence()
        items

    def add(t: T): Unit =
        modify {
            items += 1
            queue.addOne(t)
            ()
        }

    def offer(t: T): Boolean =
        tryModify {
            items += 1
            queue.addOne(t)
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

    def addAndPoll(t: T): T =
        if isEmpty() then
            t
        else
            modify {
                if isEmpty() then t
                else
                    val r = queue.dequeue()
                    queue.addOne(t)
                    r
            }

    def steal(to: Queue[T]): T =
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
    end steal

    def drain(f: T => Unit): Unit =
        if !isEmpty() then
            modify {
                items = 0
                queue.foreach(f)
                queue.clear()
            }

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
