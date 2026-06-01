package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for FQN computation correctness.
  *
  * Pins findings F-I-001 and F-A-008. All leaves are PENDING until Phase 02 un-pends them by fixing `ClasspathOrchestrator.computeFqn`
  * (halt walk at Package owners) and `TypeUnpickler` (TYPEREFin always concatenates).
  */
class FqnFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-I-001 / INV-002 leaf 1 (Phase 02): no-doubled-segments
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: scanning every key of cp.fqnIndex
    // Then: post-fix zero keys contain the substring "scala.scala" or "kyo.kyo";
    //       zero keys begin with "<empty>.";
    //       before fix keys like "scala.scala.collection.scala.collection.immutable.List" appear
    // Pins: INV-002 producer (F-I-001)
    "F-I-001 (Phase 02): fqnIndex contains no doubled-package-segment keys" in pending

    // F-I-001 leaf 2 (Phase 02): list-resolves-at-canonical-fqn
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.findClassLike("scala.collection.immutable.List")
    // Then: post-fix returns Present(_: Symbol.Class) with .canonicalFqn == "scala.collection.immutable.List";
    //       before fix returns Absent because the symbol is indexed at the doubled key
    //       "scala.scala.collection.scala.collection.immutable.List"
    // Pins: F-I-001
    "F-I-001 (Phase 02): cp.findClassLike(scala.collection.immutable.List) returns Present" in pending

    // F-I-001 / F-A-008 leaf 3 (Phase 02): option-resolves-at-canonical-fqn
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.findClassLike("scala.Option") and cp.findClassLike("scala.Symbol")
    // Then: post-fix both return Present;
    //       before fix both return Absent (FQN doubling for both scala and scala.Symbol-vs-Symbol cases)
    // Pins: F-I-001, F-A-008
    "F-I-001 / F-A-008 (Phase 02): cp.findClassLike(scala.Option) and cp.findClassLike(scala.Symbol) return Present" in pending

    // F-A-008 leaf 4 (Phase 02): typerefin-preserves-same-name-duplication
    // Given: a TYPEREFin whose qual.simpleName matches the selected simpleName (e.g. Map.Map inner type)
    // When: reconstructing the inner FQN
    // Then: post-fix the FQN is "Map.Map" (the legitimate duplication is preserved);
    //       before fix TypeUnpickler collapses "Map.Map" to "Map" because
    //       qualFqn.endsWith("." + nm) caused the qualifier to be dropped
    // Pins: F-A-008
    "F-A-008 (Phase 02): TYPEREFin preserves legitimate same-name qualifications (Map.Map)" in pending

end FqnFidelityTest
