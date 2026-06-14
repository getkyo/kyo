package mle

import kyo.AllowUnsafe
import kyo.ffi.*

trait BetaBindings extends Ffi:
    def mleBetaTriple(x: Int)(using AllowUnsafe): Int
end BetaBindings

object BetaBindings extends Ffi.Config(library = "mle_beta")
