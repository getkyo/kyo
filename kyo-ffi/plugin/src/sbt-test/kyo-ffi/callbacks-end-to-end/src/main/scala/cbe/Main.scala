package cbe

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

object Main:
    def main(args: Array[String]): Unit =
        val b = Ffi.load[CbeBindings]

        // 1. Sum pairs: the callback returns a+b for i in [0,5). Expected:
        // sum over i in 0..4 of (i + 2*i) = 3*(0+1+2+3+4) = 30.
        val sum = b.cbeSumPairs((x, y) => x + y, 5)
        if sum != 30 then throw new AssertionError(s"expected sum=30, got $sum")

        // 2. qsort with a descending comparator, exercising transient callback
        // + Buffer marshalling.
        Buffer.use[Int, Unit](5) { buf =>
            val xs = Array(1, 4, 2, 5, 3)
            var i = 0
            while i < xs.length do
                buf.set(i, xs(i))
                i += 1
            b.cbeQsortDemo(buf, xs.length, (a, c) => if a > c then -1 else if a < c then 1 else 0)
            val sorted = (0 until xs.length).map(buf.get).toList
            if sorted != List(5, 4, 3, 2, 1) then
                throw new AssertionError(s"expected sorted descending, got $sorted")
            println(s"OK: sum=$sum sorted=$sorted")
        }

        // 3. Retained callback: the guard keeps the upcall stub live across two
        // separate FFI calls (`cbeRegisterHandler` then `cbeFireHandler`). If the
        // stub were not retained, `cbeFireHandler` would segfault or throw when C
        // tries to dereference the invalidated function pointer.
        @volatile var observed: Int = -1
        Ffi.Guard.use { guard =>
            b.cbeRegisterHandler((x: Int) => observed = x, guard)
            b.cbeFireHandler(42)
            if observed != 42 then
                throw new AssertionError(s"expected retained handler to have observed 42, got $observed")
            println(s"OK: retained handler observed=$observed")
        }
    end main
end Main
