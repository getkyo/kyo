package ne

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

object Main:
    def main(args: Array[String]): Unit =
        val b   = Ffi.load[NeBindings]
        val add = b.neAdd(2, 3)
        val sub = b.neSub(10, 4)
        val mul = b.neMulI64(1_000_000_000L, 3L)
        if add != 5 then throw new AssertionError(s"expected add=5, got $add")
        if sub != 6 then throw new AssertionError(s"expected sub=6, got $sub")
        if mul != 3_000_000_000L then throw new AssertionError(s"expected mul=3_000_000_000, got $mul")
        println(s"OK: add=$add sub=$sub mul=$mul")
    end main
end Main
