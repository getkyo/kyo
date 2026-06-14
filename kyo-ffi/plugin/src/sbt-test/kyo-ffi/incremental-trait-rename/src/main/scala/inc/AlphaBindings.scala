package inc

import kyo.AllowUnsafe
import kyo.ffi.*

trait AlphaBindings extends Ffi:
    def op(a: Int)(using AllowUnsafe): Int
end AlphaBindings

object AlphaBindings extends Ffi.Config(library = "inc_rename_lib")
