package java.util.concurrent.atomic

class DoubleAdder():
    var curr: Double   = 0
    def add(v: Double) = curr += v
    def sum(): Double  = curr
    def reset()        = curr = 0
    def sumThenReset(): Double =
        val res = curr
        curr = 0
        res
    end sumThenReset
end DoubleAdder

class LongAdder():
    var curr: Long   = 0
    def add(v: Long) = curr += v
    def decrement()  = curr -= 1
    def increment()  = curr += 1
    def sum(): Long  = curr
    def reset()      = curr = 0
    def sumThenReset(): Long =
        val res = curr
        curr = 0
        res
    end sumThenReset
end LongAdder
