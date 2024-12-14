package java.util.concurrent.atomic

class DoubleAdder():
    private var curr: Double = 0
    def add(v: Double)       = synchronized { curr += v }
    def sum(): Double        = synchronized { curr }
    def reset()              = synchronized { curr = 0 }
    def sumThenReset(): Double =
        synchronized {
            val res = curr
            curr = 0
            res
        }
end DoubleAdder

class LongAdder():
    private var curr: Long = 0
    def add(v: Long)       = synchronized { curr += v }
    def decrement()        = synchronized { curr -= 1 }
    def increment()        = synchronized { curr += 1 }
    def sum(): Long        = synchronized { curr }
    def reset()            = synchronized { curr = 0 }
    def sumThenReset(): Long =
        synchronized {
            val res = curr
            curr = 0
            res
        }
end LongAdder
