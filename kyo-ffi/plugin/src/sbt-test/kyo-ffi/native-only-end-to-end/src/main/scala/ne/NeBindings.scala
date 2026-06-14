package ne

import kyo.AllowUnsafe
import kyo.ffi.*

trait NeBindings extends Ffi:
    def neAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def neSub(a: Int, b: Int)(using AllowUnsafe): Int
    def neMulI64(a: Long, b: Long)(using AllowUnsafe): Long
end NeBindings

object NeBindings extends Ffi.Config(library = "ne_lib")
