package stale

import kyo.AllowUnsafe
import kyo.ffi.*

trait StaleBindings extends Ffi:
    def addOne(a: Int)(using AllowUnsafe): Int
end StaleBindings

object StaleBindings extends Ffi.Config(library = "stale_lib")
