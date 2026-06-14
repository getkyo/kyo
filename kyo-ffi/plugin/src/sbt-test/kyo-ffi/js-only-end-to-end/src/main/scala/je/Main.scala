package je

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

object Main:
    def main(args: Array[String]): Unit =
        val b   = Ffi.load[JeBindings]
        val add = b.jeAdd(2, 3)
        val sub = b.jeSub(10, 4)
        if add != 5 then throw new AssertionError(s"expected add=5, got $add")
        if sub != 6 then throw new AssertionError(s"expected sub=6, got $sub")
        println(s"OK: add=$add sub=$sub")
    end main
end Main
