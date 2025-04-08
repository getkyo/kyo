package kyo.stats.internal

import java.util.concurrent.atomic.LongAdder

class UnsafeCounter extends Serializable {
    private var last  = 0L
    private val adder = new LongAdder

    def get(): Long        = adder.sumThenReset()
    def inc(): Unit        = adder.increment()
    def add(v: Long): Unit = adder.add(v)

    private def addExact(a: Long, b: Long) = {
        val sum = a + b
        if (sum < 0) {
            (Long.MaxValue + sum) + 2
        } else {
            sum
        }
    }

    private def findDelta(a: Long, b: Long) = {
        (Long.MaxValue - a) + b
    }

    private[kyo] def delta() = {
        val curr  = addExact(get(), last)
        val delta = if (curr >= last) curr - last else findDelta(last, curr)
        last = curr
        delta
    }

    private[kyo] def getLast(): Long = last
}
