package autodiscover

import kyo.AllowUnsafe
import kyo.ffi.*

trait AutodiscoverBindings extends Ffi:
    def autodiscoverAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def autodiscoverMul(a: Int, b: Int)(using AllowUnsafe): Int

object AutodiscoverBindings extends Ffi.Config(library = "autodiscover")
