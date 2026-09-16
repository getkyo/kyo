package sn

import kyo.AllowUnsafe
import kyo.ffi.*

trait SnBindings extends Ffi:
    def snAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def snSub(a: Int, b: Int)(using AllowUnsafe): Int
end SnBindings

object SnBindings extends Ffi.Config(library = "sn_lib")
