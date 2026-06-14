package spo

import kyo.AllowUnsafe
import kyo.ffi.*

trait SpoBindings extends Ffi:
    def spoEcho(x: Int)(using AllowUnsafe): Int
end SpoBindings

object SpoBindings extends Ffi.Config(library = "spo_lib")
