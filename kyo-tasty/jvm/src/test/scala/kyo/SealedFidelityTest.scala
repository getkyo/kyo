package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for permittedSubclasses from SEALEDPERMITS and the EnumCase discriminator.
  *
  * Pins findings F-I-003 and F-E-007. All leaves are PENDING until Phase 07 un-pends them by adding `Symbol.EnumCase` to `Tasty.scala`,
  * implementing a `readSealedPermits` helper in `AstUnpickler`, and routing the EnumCase flag in the CLASSDEF dispatch.
  */
class SealedFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-I-003 / INV-007 leaf 1 (Phase 07): option-permitted-subclasses
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.findClass("scala.Option").get.permittedSubclasses.toChunk.flatten.map(_.canonicalFqn).toSet
    // Then: post-fix Set contains "scala.Some" and "scala.None";
    //       before fix permittedSubclasses was Absent (hardcoded in Tasty.scala line 1793;
    //       AstUnpickler never read the SEALEDPERMITS section)
    // Pins: INV-007 producer (F-I-003)
    "F-I-003 / INV-007 (Phase 07): scala.Option.permittedSubclasses == Set(scala.Some, scala.None)" in pending

    // F-E-007 / F-I-003 leaf 2 (Phase 07): tastyerror-permitted-subclasses-are-enumcase
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.findClass("kyo.TastyError").get.permittedSubclasses.toChunk.flatten
    // Then: post-fix the result is non-empty and every element is Symbol.EnumCase;
    //       before fix the result was empty (same root cause) and Symbol.EnumCase did not exist
    // Pins: F-E-007 plus F-I-003
    "F-E-007 (Phase 07): kyo.TastyError permitted subclasses are non-empty and Symbol.EnumCase" in pending

    // INV-007 leaf 3 (Phase 07): every-sealed-class-has-permits
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: filtering cp.allClasses.filter(_.isSealed) and asserting permittedSubclasses.isDefined
    // Then: post-fix holds for >= 95% of sealed classes;
    //       before fix 0% of 105 sealed class-like symbols had permittedSubclasses populated
    // Pins: INV-007 invariant strength
    "INV-007 (Phase 07): >= 95% of sealed classes have permittedSubclasses populated" in pending

    // F-E-007 leaf 4 (Phase 07): enumcase-discriminator-present
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: finding kyo.TastyError$.SymbolNotFound via cp.findClass(...)
    // Then: post-fix matches case _: Symbol.EnumCase (the new discriminator sub-trait);
    //       before fix matched Symbol.Class only (no EnumCase discriminator existed)
    // Pins: F-E-007
    "F-E-007 (Phase 07): kyo.TastyError$.SymbolNotFound is classified as Symbol.EnumCase" in pending

end SealedFidelityTest
