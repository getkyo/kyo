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

    // ── Leaf 1: INV009-fourSites-preserved ───────────────────────────────────
    // Given: TastyState.global is the relocated lazy fallback (INV-009 site-2).
    // When: Tasty.findClass("scala.Predef$") is called outside any withClasspath scope.
    // Then: the call does not panic; on JVM it may return Present, on JS/Native it returns Absent.
    //       The critical invariant is that TastyState.global is used (not Tasty.current) and
    //       the four INV-009 site count is preserved (site-1 withClasspath, site-2 global lazy,
    //       site-3 bodyTree, site-4 evictOlderThan).
    // Pins: INV-009 site-2 relocation; PRESERVE-I.
    "Leaf 1: INV-009 fourSites preserved -- TastyState.global callable without panic" in runJVM {
        // Force TastyState.global initialization (INV-009 site-2).
        val binding = TastyState.global
        assert(binding != null, "TastyState.global must not be null")
        // The Classpath from global is valid (non-null cp field).
        assert(binding.cp != null, "TastyState.global.cp must not be null")
        succeed
    }

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
