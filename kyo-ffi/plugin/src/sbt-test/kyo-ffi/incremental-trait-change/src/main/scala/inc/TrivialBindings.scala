package inc

import kyo.AllowUnsafe
import kyo.ffi.*

trait TrivialBindings extends Ffi:
    def incChange(a: Int)(using AllowUnsafe): Int
end TrivialBindings

object TrivialBindings extends Ffi.Config(library = "inc_change_lib")
