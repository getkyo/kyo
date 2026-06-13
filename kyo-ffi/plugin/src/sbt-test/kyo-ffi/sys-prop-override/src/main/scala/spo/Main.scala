package spo

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

object Main:
    def main(args: Array[String]): Unit =
        val b    = Ffi.load[SpoBindings]
        val out  = b.spoEcho(42)
        if out != 42 then throw new AssertionError(s"expected 42, got $out")
        // The packaged lib has been deleted from the classpath, so reaching here
        // proves the -Dkyo.ffi.spo_lib.path override was honored.
        val path = System.getProperty("kyo.ffi.spo_lib.path")
        if path == null || path.isEmpty then
            throw new AssertionError("kyo.ffi.spo_lib.path system property not set")
        println(s"OK: override honored, path=$path out=$out")
    end main
end Main
