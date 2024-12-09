package kyo.scheduler

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable.PriorityQueue

class Queue[A](implicit ord: Ordering[A]) extends AtomicBoolean {
    private val queue = PriorityQueue[A]()

    private var items = new AtomicInteger(0)

    private def lock(): Unit =
        while (!compareAndSet(false, true)) ()

    private def unlock(): Unit = set(false)

    def isEmpty(): Boolean = size() == 0

    def size(): Int = {
        items.intValue()
    }

    def add(value: A): Unit = {
        lock()
        try {
            items.incrementAndGet()
            queue += value
            ()
        } finally
            unlock()
    }

    def drainToSeq(): Seq[A] = {
        if (!isEmpty()) {
            lock()
            try {
                items.set(0)
                queue.dequeueAll
            } finally
                unlock()
        } else
            Seq.empty
    }
}
