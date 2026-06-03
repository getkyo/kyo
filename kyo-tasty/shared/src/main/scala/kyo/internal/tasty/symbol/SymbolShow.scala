package kyo.internal.tasty.symbol

import kyo.*
import kyo.AllowUnsafe
import kyo.Tasty
import kyo.Tasty.Name.asString

/** Renders a Symbol to a human-readable string.
  *
  * Format: `"<kind> <fullName>"` (e.g., `"Class kyo.fixtures.Foo"`). Called by `Symbol.show(using cp)`.
  */
private[kyo] object SymbolShow:

    def show(sym: Tasty.Symbol, cp: Tasty.Classpath)(using Frame): String < Sync =
        Sync.Unsafe.defer:
            val kind = sym.kind.toString
            val fqn  = cp.fullNameUnsafe(sym).asString
            if fqn.isEmpty then kind
            else s"$kind $fqn"
    end show

end SymbolShow
