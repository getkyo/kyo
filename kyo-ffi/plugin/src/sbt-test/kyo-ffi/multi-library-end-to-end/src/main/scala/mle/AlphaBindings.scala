package mle

import kyo.AllowUnsafe
import kyo.ffi.*

trait AlphaBindings extends Ffi:
    def mleAlphaDouble(x: Int)(using AllowUnsafe): Int
end AlphaBindings

object AlphaBindings extends Ffi.Config(library = "mle_alpha")
