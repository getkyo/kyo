package inc

import kyo.AllowUnsafe
import kyo.ffi.*

// Renamed from AlphaBindings (#247 facet 2). ffiGenerate must regenerate from fresh TASTy: the old
// AlphaBindings must no longer be discovered, so its generated AlphaBindingsImpl is removed as stale
// (#34) rather than left behind referencing a trait that no longer exists.
trait BetaBindings extends Ffi:
    def op(a: Int)(using AllowUnsafe): Int
end BetaBindings

object BetaBindings extends Ffi.Config(library = "inc_rename_lib")
