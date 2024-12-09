package kyo.scheduler

import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.PriorityQueue

class Queue[A](implicit ord: Ordering[A]) extends AtomicBoolean {
    private val queue = PriorityQueue[A]()

    private var items = 0

    private def lock(): Unit =
        while (!compareAndSet(false, true)) ()

    private def unlock(): Unit = set(false)

    def isEmpty() = size() == 0

    def size(): Int = {
        VarHandle.acquireFence()
        items
    }

    def add(value: A): Unit = {
        lock()
        try {
            items += 1
            queue += value
            ()
        } finally
            unlock()
    }

    def drainToSeq(): Seq[A] = {
        if (!isEmpty()) {
            lock()
            try {
                items = 0
                queue.dequeueAll
            } finally
                unlock()
        } else
            Seq.empty
    }
}
