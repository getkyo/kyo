package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for opaque type detection and dual FQN indexing.
  *
  * Pins findings F-E-001, F-E-002, and F-I-002. All leaves are PENDING until Phase 06 un-pends them by adding `Symbol.OpaqueType` to
  * `Tasty.scala`, detecting the OPAQUE flag during TYPEDEF in `AstUnpickler`, and dual-indexing opaque types in
  * `ClasspathOrchestrator`.
  */
class OpaqueTypeFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-E-001 / INV-006 leaf 1 (Phase 06): kyo-maybe
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: calling cp.findSymbol("kyo.Maybe")
    // Then: post-fix Present(s: Symbol.OpaqueType) with s.canonicalFqn == "kyo.Maybe";
    //       before fix Absent because the opaque type body was indexed under
    //       "kyo.Maybe$package$.Maybe" with kind=Val and cp.findSymbol("kyo.Maybe") returned Absent
    // Pins: INV-006 producer (F-E-001, F-I-002)
    "F-E-001 / INV-006 (Phase 06): cp.findSymbol(kyo.Maybe) returns Present(Symbol.OpaqueType)" in pending

    // F-E-002 leaf 2 (Phase 06): kyo-result-kyo-duration
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: calling cp.findClassLike("kyo.Result") and cp.findClassLike("kyo.Duration")
    // Then: post-fix both return Present(_: Symbol.OpaqueType);
    //       before fix both returned Absent (same root cause as F-E-001)
    // Pins: F-E-002
    "F-E-002 (Phase 06): cp.findClassLike(kyo.Result) and kyo.Duration return Present(Symbol.OpaqueType)" in pending

    // Q-003 / F-I-002 leaf 3 (Phase 06): binary-fqn-still-findable
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: calling cp.findSymbol("kyo.Maybe$package$.Maybe")
    // Then: post-fix Present(_) (binary FQN is preserved as a secondary key via dual-indexing);
    //       before fix only the Val forwarder was indexed at this key, not the OpaqueType
    // Pins: Q-003 dual-index contract (HARD RULE 4: layer-don't-restrict)
    "Q-003 (Phase 06): opaque type is findable via its binary FQN kyo.Maybe$package$.Maybe" in pending

    // INV-006 leaf 4 (Phase 06): no-opaque-as-val
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: iterating cp.allSymbols.filter(s => s.name.asString == "Maybe" && s.canonicalFqn == "kyo.Maybe")
    // Then: post-fix every element is Symbol.OpaqueType;
    //       before fix the query returned Symbol.Val (the forwarder, not the opaque type)
    // Pins: INV-006 (no opaque classified as Val)
    "INV-006 (Phase 06): kyo.Maybe symbol is Symbol.OpaqueType, not Symbol.Val" in pending

end OpaqueTypeFidelityTest
