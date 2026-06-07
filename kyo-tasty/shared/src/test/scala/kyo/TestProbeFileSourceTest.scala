package kyo

import kyo.internal.tasty.query.TastyState

/** Compile-time invariants on the public surface:
  *   - `kyo.Tasty.SymbolKind` no longer resolves (relocated to internal)
  *   - `kyo.Tasty.bindingLocal` no longer resolves (removed)
  */
class TestProbeFileSourceTest extends kyo.test.Test[Any]:

    "kyo.Tasty.SymbolKind does not resolve after relocation" in {
        val err = compiletime.testing.typeCheckErrors("val k: kyo.Tasty.SymbolKind = ???")
        assert(err.nonEmpty, "kyo.Tasty.SymbolKind must not resolve; path removed from public API")
        succeed
    }

    "kyo.Tasty.bindingLocal does not resolve after deletion" in {
        val err = compiletime.testing.typeCheckErrors("val b = kyo.Tasty.bindingLocal")
        assert(err.nonEmpty, "kyo.Tasty.bindingLocal must not resolve; field removed from public API")
        succeed
    }

end TestProbeFileSourceTest
