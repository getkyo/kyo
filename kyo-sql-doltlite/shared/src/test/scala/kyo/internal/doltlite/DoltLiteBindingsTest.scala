package kyo.internal.doltlite

import kyo.*
import kyo.Test

/** Guards [[DoltLiteBindings]]'s `Ffi.Config`, which restates every symbol rather than inheriting one, because the
  * generator reads the symbol map out of TASTy as string literals. A wrong entry binds nothing and fails at library
  * load, which no compile catches.
  */
class DoltLiteBindingsTest extends Test:

    "the library and header are this engine's own" in {
        assert(DoltLiteBindings.library == "kyo_doltlite", DoltLiteBindings.library)
        assert(DoltLiteBindings.headers == Chunk("doltlite.h"), s"${DoltLiteBindings.headers}")
    }

    "this binding is native-bundled, because its shim is compiled into the binary" in {
        assert(DoltLiteBindings.nativeBundled)
    }

    "every symbol the binding names is one of this engine's own two families" in {
        // Either a raw sqlite3_* entry point, which the fork keeps, or one of the shim's kyo_sqlite3_* wrappers.
        val stray = DoltLiteBindings.symbols.toSeq
            .filterNot((_, symbol) => symbol.startsWith("sqlite3_") || symbol.startsWith("kyo_sqlite3_"))
            .sortBy(_._1)
        assert(stray.isEmpty, s"symbols outside the two known families: $stray")
    }

end DoltLiteBindingsTest
