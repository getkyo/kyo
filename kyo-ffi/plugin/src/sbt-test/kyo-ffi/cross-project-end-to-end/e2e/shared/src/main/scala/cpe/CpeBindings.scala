package cpe

import kyo.AllowUnsafe
import kyo.ffi.*

trait CpeBindings extends Ffi:
    def cpeAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def cpeSub(a: Int, b: Int)(using AllowUnsafe): Int
    def cpeMulI64(a: Long, b: Long)(using AllowUnsafe): Long
end CpeBindings

object CpeBindings extends Ffi.Config(library = "cpe_lib")
