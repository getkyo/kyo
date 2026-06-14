package sc

import kyo.AllowUnsafe
import kyo.ffi.*

trait ScBindings extends Ffi:
    // Resolves to C symbol `signal`, on the retention allowlist. Takes a function
    // param and has no Ffi.Guard. Under ffiStrictCallbacks this is an error.
    def signal(sig: Int, handler: Int => Unit)(using AllowUnsafe): Int
end ScBindings

object ScBindings extends Ffi.Config(library = "scbk_lib")
