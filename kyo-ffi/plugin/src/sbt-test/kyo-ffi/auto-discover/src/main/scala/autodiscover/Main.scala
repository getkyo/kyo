package autodiscover

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

object Main:
    def main(args: Array[String]): Unit =
        val b = Ffi.load[AutodiscoverBindings]
        val add = b.autodiscoverAdd(2, 3)
        val mul = b.autodiscoverMul(4, 5)
        if add != 5 then throw new AssertionError(s"expected add=5, got $add")
        if mul != 20 then throw new AssertionError(s"expected mul=20, got $mul")
        println(s"OK: add=$add mul=$mul")
