package vali

import kyo.AllowUnsafe
import kyo.ffi.*

trait ValiBindings extends Ffi:
    def add(a: Int, b: Int)(using AllowUnsafe): Int
end ValiBindings

// Intentional mismatch: this id is NOT present in `ffiLibraries`. The plugin
// must fail the build with a clear error naming the trait and the missing id.
object ValiBindings extends Ffi.Config(library = "missing")
