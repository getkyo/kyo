package mle

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

object Main:
    def main(args: Array[String]): Unit =
        val a     = Ffi.load[AlphaBindings]
        val b     = Ffi.load[BetaBindings]
        val ax6   = a.mleAlphaDouble(3)
        val bx21  = b.mleBetaTriple(7)
        if ax6 != 6 then throw new AssertionError(s"expected alpha=6, got $ax6")
        if bx21 != 21 then throw new AssertionError(s"expected beta=21, got $bx21")
        println(s"OK: alpha=$ax6 beta=$bx21")
    end main
end Main
