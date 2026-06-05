package kyo

import kyo.internal.tasty.query.TastyState

/** Phase 02 plan leaves 1-4: Cat 4 internal relocation and Cat 20 rename.
  *
  * Leaf 1: INV009-fourSites-preserved -- TastyState.global is reachable via Tasty.findClass without
  *         breaking the four-site INV-009 count.
  * Leaf 2: globalReadResolvesToTastyState -- no-scope Tasty.findClass resolves via TastyState.global.
  * Leaf 3: symbolKindNotPublic -- kyo.Tasty.SymbolKind no longer resolves after Phase 02 relocation.
  * Leaf 4: bindingLocalNotPublic -- kyo.Tasty.bindingLocal no longer resolves after Phase 02 deletion.
  *
  * Pins: INV-009, Cat 4 internalisation, Cat 20 rename, PRESERVE-I.
  */
class TestProbeFileSourceTest extends Test:

    // Leaf 1 (removed in Phase 04 sweep): the original leaf forced TastyState.global and asserted
    // non-null, but did not actually probe the four INV-009 sites. Behavioral coverage for all four
    // sites lives in Inv009BehavioralTest (14 leaves). That test is the authoritative INV-009 gate.

    // ── Leaf 2: globalReadResolvesToTastyState ───────────────────────────────
    // Given: no withClasspath scope is active.
    // When: Tasty.classpath is read.
    // Then: on JVM, PlatformFallback populates TastyState.global so the classpath is non-empty.
    //       The test verifies the binding is reachable (non-empty symbols or at least non-null).
    // Pins: Cat 20 rename does not change behavior; INV-009 site-2 location moves only.
    "Leaf 2: globalReadResolvesToTastyState -- Tasty.classpath uses TastyState.global as fallback" in runJVM {
        Tasty.classpath.map: cp =>
            assert(cp.symbols.size >= 1, s"JVM fallback via TastyState.global must yield non-empty classpath; got ${cp.symbols.size}")
            succeed
    }

    // ── Leaf 3: symbolKindNotPublic ──────────────────────────────────────────
    // Given: Cat 4 moved SymbolKind out of object Tasty.
    // When: user code attempts to refer to kyo.Tasty.SymbolKind.
    // Then: the path does not resolve (typeCheckErrors is non-empty).
    // Pins: Cat 4 internalisation; PRESERVE-I.
    "Leaf 3: symbolKindNotPublic -- kyo.Tasty.SymbolKind does not resolve after relocation" in {
        val err = compiletime.testing.typeCheckErrors("val k: kyo.Tasty.SymbolKind = ???")
        assert(err.nonEmpty, "kyo.Tasty.SymbolKind must not resolve; path removed in Phase 02")
        succeed
    }

    // ── Leaf 4: bindingLocalNotPublic ────────────────────────────────────────
    // Given: Cat 4 deleted bindingLocal from object Tasty.
    // When: user code attempts to access kyo.Tasty.bindingLocal.
    // Then: the path does not resolve (typeCheckErrors is non-empty).
    // Pins: Cat 4 internalisation.
    "Leaf 4: bindingLocalNotPublic -- kyo.Tasty.bindingLocal does not resolve after deletion" in {
        val err = compiletime.testing.typeCheckErrors("val b = kyo.Tasty.bindingLocal")
        assert(err.nonEmpty, "kyo.Tasty.bindingLocal must not resolve; field removed in Phase 02")
        succeed
    }

end TestProbeFileSourceTest
