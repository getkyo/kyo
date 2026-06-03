package kyo.internal.tasty.symbol

import kyo.AllowUnsafe
import kyo.Tasty
import kyo.Tasty.Name.asString

/** Renders a Symbol to a human-readable string.
  *
  * Format: `"<kind> <fullName>"` (e.g., `"Class kyo.fixtures.Foo"`). Called by `Symbol.show(using cp)`.
  */
private[kyo] object SymbolShow:

    def show(sym: Tasty.Symbol, cp: Tasty.Classpath)(using AllowUnsafe): String =
        val kind = sym.kind.toString
        val fqn  = cp.fullName(sym).asString
        if fqn.isEmpty then kind
        else s"$kind $fqn"
    end show

end SymbolShow
