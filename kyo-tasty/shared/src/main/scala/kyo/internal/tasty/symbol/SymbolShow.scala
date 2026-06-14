package kyo.internal.tasty.symbol

import kyo.*
import kyo.AllowUnsafe
import kyo.Tasty
import kyo.Tasty.Name.asString

/** Renders a Symbol to a human-readable string.
  *
  * Format: `"<kind> <fullName>"` (e.g., `"Class kyo.fixtures.Foo"`). Called by `Symbol.show(using classpath)`.
  */
private[kyo] object SymbolShow:

    def show(symbol: Tasty.Symbol, classpath: Tasty.Classpath)(using Frame): String < Sync =
        Sync.Unsafe.defer {
            val kind     = symbol.kind.toString
            val fullName = classpath.computeFullName(symbol).asString
            if fullName.isEmpty then kind
            else s"$kind $fullName"
        }
    end show

end SymbolShow
