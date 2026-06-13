package cf

import kyo.AllowUnsafe
import kyo.ffi.*

trait CfBindings extends Ffi:
    def cfTrivial(a: Int)(using AllowUnsafe): Int
end CfBindings

object CfBindings extends Ffi.Config(library = "cf_lib")
