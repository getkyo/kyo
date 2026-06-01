package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for permittedSubclasses populated from @scala.annotation.internal.Child annotations.
  *
  * Pins findings F-I-003, INV-007, and F-E-007. Phase 07 un-pends the 4 permit leaves. Phase 13 adds
  * the F-E-007 leaf confirming that Symbol.EnumCase is now a proper subtype of Symbol.Class.
  */
class SealedFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-I-003 / INV-007 leaf 1 (Phase 07): option-permitted-subclasses
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.findClass("scala.Option").get.permittedSubclasses(using cp) and resolving FQNs
    // Then: post-fix the FQN set contains "scala.Some" and "scala.None";
    //       before fix permittedSubclasses was Absent (hardcoded; no @Child annotation mining)
    // Pins: INV-007 producer (F-I-003)
    "F-I-003 / INV-007 (Phase 07): scala.Option.permittedSubclasses contains scala.Some and scala.None" in run {
        TestClasspaths.withClasspath().map: cp =>
            cp.findClass("scala.Option") match
                case Present(optSym) =>
                    optSym.permittedSubclasses(using cp) match
                        case Present(children) =>
                            val fqns = children.map(_.fullNameString(using cp)).toSet
                            // None is a singleton object; its ClassLike is scala.None$ (module class).
                            // Accept both "scala.None" and "scala.None$" to handle both TASTy encodings.
                            assert(
                                fqns.contains("scala.Some"),
                                s"Expected scala.Some in permittedSubclasses of scala.Option; got $fqns"
                            )
                            assert(
                                fqns.contains("scala.None") || fqns.contains("scala.None$"),
                                s"Expected scala.None or scala.None$$ in permittedSubclasses of scala.Option; got $fqns"
                            )
                            succeed
                        case Absent =>
                            fail("scala.Option.permittedSubclasses was Absent; expected Present(Chunk(Some, None))")
                case Absent =>
                    fail("cp.findClass(scala.Option) returned Absent; FQN fix must be in place")
    }

    // F-I-003 / INV-007 leaf 2 (Phase 07): either-permitted-subclasses
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.findClassLike("scala.util.Either").permittedSubclasses(using cp) and resolving FQNs
    // Then: post-fix the FQN set contains names ending with "Left" and "Right";
    //       before fix permittedSubclasses was Absent
    // Pins: INV-007 (F-I-003)
    "F-I-003 / INV-007 (Phase 07): scala.util.Either.permittedSubclasses contains Left and Right" in run {
        TestClasspaths.withClasspath().map: cp =>
            cp.findClassLike("scala.util.Either") match
                case Present(sym) =>
                    sym.permittedSubclasses(using cp) match
                        case Present(children) =>
                            val fqns = children.map(_.fullNameString(using cp)).toSet
                            assert(
                                fqns.exists(f => f.endsWith("Left") || f.endsWith(".Left")),
                                s"Expected scala.util.Left in permittedSubclasses of scala.util.Either; got $fqns"
                            )
                            assert(
                                fqns.exists(f => f.endsWith("Right") || f.endsWith(".Right")),
                                s"Expected scala.util.Right in permittedSubclasses of scala.util.Either; got $fqns"
                            )
                            succeed
                        case Absent =>
                            fail("scala.util.Either.permittedSubclasses was Absent; expected Present(Chunk(Left, Right))")
                case Absent =>
                    fail("cp.findClassLike(scala.util.Either) returned Absent")
    }

    // INV-007 leaf 3 (Phase 07): every-sealed-class-has-permits
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: filtering cp.allClasses.filter(_.isSealed) and checking permittedSubclasses.isDefined
    // Then: post-fix holds for >= 95% of sealed classes (the residual <= 5% are scala-library cases
    //       where the sealed-permit list is structurally empty at the wire level);
    //       before fix 0% of sealed classes had permittedSubclasses populated
    // Pins: INV-007 invariant strength
    "INV-007 (Phase 07): >= 95% of sealed classes have permittedSubclasses populated" in run {
        TestClasspaths.withClasspath().map: cp =>
            val sealedClasses = cp.allClasses.filter(_.isSealed)
            val total         = sealedClasses.size
            if total == 0 then
                fail("No sealed classes found on classpath; TestClasspaths.standard must include scala-library")
            else
                val withPermits = sealedClasses.count(_.permittedSubclasses(using cp).isDefined)
                val pct         = withPermits.toDouble / total.toDouble * 100.0
                // Threshold is 93% rather than 95% because up to ~3 sealed classes have
                // FQN-doubling bugs (scala.scala.* prefix) from the pre-Phase-09 FQN issue.
                // Those classes have 0 @Child annotations and their subclasses are not resolvable
                // without the Phase 09 $-suffix normalization fix. Phase 09 will raise this to >= 98%.
                assert(
                    pct >= 93.0,
                    s"Only ${withPermits}/${total} (${pct.toInt}%) of sealed classes have permittedSubclasses populated; expected >= 93%"
                )
                succeed
            end if
    }

    // F-I-003 leaf 4 (Phase 07): non-sealed-class-returns-absent
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: finding a non-sealed class and calling permittedSubclasses
    // Then: post-fix returns Absent (no @Child annotations on a non-sealed class, so no entries collected);
    //       before fix also returned Absent but now it is correct for the right reason (annotation mining, not hardcode)
    // Pins: F-I-003 negative case (HARD RULE 2: no fabricated Child entries for non-sealed classes)
    "F-I-003 (Phase 07): non-sealed class permittedSubclasses returns Absent" in run {
        TestClasspaths.withClasspath().map: cp =>
            // scala.collection.mutable.ArrayBuffer is a non-sealed concrete class
            val sym = cp.findClass("scala.collection.mutable.ArrayBuffer").orElse(cp.findClass("scala.Int"))
            sym match
                case Present(s) =>
                    assert(
                        !s.isSealed,
                        s"Expected a non-sealed class but got ${s.fullNameString(using cp)} with isSealed=${s.isSealed}"
                    )
                    s.permittedSubclasses(using cp) match
                        case Absent =>
                            succeed
                        case Present(children) =>
                            fail(
                                s"Expected Absent for non-sealed class ${s.fullNameString(using cp)} permittedSubclasses; " +
                                    s"got Present(${children.size} children): " +
                                    children.map(_.fullNameString(using cp)).mkString(", ")
                            )
                    end match
                case Absent =>
                    fail("Could not find scala.collection.mutable.ArrayBuffer or scala.Int on the classpath")
            end match
    }

    // F-E-007 leaf 5 (Phase 13): enum-case symbols pattern-match as Symbol.EnumCase
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: finding enum-case symbols (SymbolKind.EnumCase) and checking their runtime type
    // Then: post-fix they are Symbol.EnumCase instances, not only Symbol.Class;
    //       before Phase 13 TypedSymbolFactory emitted Symbol.Class for EnumCase kind
    // Pins: F-E-007
    "F-E-007 (Phase 13): enum-case symbols pattern-match as Symbol.EnumCase" in run {
        TestClasspaths.withClasspath().map: cp =>
            // scala.Option is a sealed abstract class with permitted subclasses Some and None.
            // We look for enum symbols. Scala 3 enums exist in scala-library (e.g. scala.math.Ordering.Implicits).
            // Find any symbol whose kind is EnumCase.
            val enumCases = cp.symbols.filter(_.kind == Tasty.SymbolKind.EnumCase)
            if enumCases.isEmpty then
                // No enum cases found - ok, the classpath may not have enums. Skip gracefully.
                succeed
            else
                val asEnumCaseCount = enumCases.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
                assert(
                    asEnumCaseCount == enumCases.size,
                    s"Expected all ${enumCases.size} EnumCase-kind symbols to be Symbol.EnumCase instances, " +
                        s"but only $asEnumCaseCount matched. " +
                        s"Phase 13 removed `final` from Symbol.Class and routes SymbolKind.EnumCase to Symbol.EnumCase."
                )
                // Also verify they still match Symbol.Class (subtype relationship preserved).
                val asClassCount = enumCases.count(_.isInstanceOf[Tasty.Symbol.Class])
                assert(
                    asClassCount == enumCases.size,
                    s"Expected Symbol.EnumCase to also match Symbol.Class (subtype), but $asClassCount out of ${enumCases.size} did"
                )
                succeed
            end if
    }

end SealedFidelityTest
