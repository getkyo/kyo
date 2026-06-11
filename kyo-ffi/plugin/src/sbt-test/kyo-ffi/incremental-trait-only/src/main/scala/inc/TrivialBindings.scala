package inc

import kyo.AllowUnsafe
import kyo.ffi.*

trait TrivialBindings extends Ffi:
    def incTrivial(a: Int)(using AllowUnsafe): Int
end TrivialBindings

object TrivialBindings extends Ffi.Config(library = "inc_trait_lib")
