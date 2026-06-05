package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.symbol.SymbolKind

/** Fidelity tests for permittedSubclasses populated from @scala.annotation.internal.Child annotations.
  *
  * Pins findings F-I-003, INV-007, and F-E-007. Phase 07 un-pends the 4 permit leaves. Phase 13 adds the F-E-007 leaf confirming that
  * Symbol.EnumCase is now a proper subtype of Symbol.Class.
  *
  * Phase 2.12 corrective: leaves 1-4 rewritten to use embedded fixture sealed hierarchies (kyo.fixtures.Animal/Vehicle/NonSealedMarker)
  * instead of stdlib scala.Option / scala.util.Either. All 4 leaves are now cross-platform.
  */
class SealedFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-I-003 / INV-007 leaf 1 (Phase 07): animal-permitted-subclasses
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.findClass("kyo.fixtures.Animal").get.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty) and resolving FQNs
    // Then: post-fix the FQN set contains "kyo.fixtures.Dog" and "kyo.fixtures.Cat"
    // Pins: INV-007 producer (F-I-003)
    // Cross-platform: kyo.fixtures.Animal is in the embedded fixture set on all platforms.
    "F-I-003 / INV-007 (Phase 07): kyo.fixtures.Animal.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty) contains Dog and Cat" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            cp.findClassLike("kyo.fixtures.Animal") match
                case Present(animalSym) =>
                    val children = (animalSym match
                        case c: Tasty.Symbol.Class =>
                            c.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case t: Tasty.Symbol.Trait =>
                            t.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case _ => Chunk.empty
                    )
                    if children.isEmpty then
                        fail(
                            "kyo.fixtures.Animal.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty) was empty; expected Chunk(Dog, Cat)"
                        )
                    else
                        Kyo.foreach(children)(c => Sync.defer(cp.fullNameUnsafe(c).asString)).map: fqnList =>
                            val fqns = fqnList.toSet
                            assert(
                                fqns.exists(f => f.endsWith("Dog") || f.endsWith(".Dog")),
                                s"Expected kyo.fixtures.Dog in permittedSubclasses of kyo.fixtures.Animal; got $fqns"
                            )
                            assert(
                                fqns.exists(f => f.endsWith("Cat") || f.endsWith(".Cat")),
                                s"Expected kyo.fixtures.Cat in permittedSubclasses of kyo.fixtures.Animal; got $fqns"
                            )
                            succeed
                    end if
                case Absent =>
                    fail("cp.findClassLike(kyo.fixtures.Animal) returned Absent; Animal fixture must be in classpath")
    }

    // F-I-003 / INV-007 leaf 2 (Phase 07): vehicle-permitted-subclasses
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.findClassLike("kyo.fixtures.Vehicle").permittedSubclasses(using cp) and resolving FQNs
    // Then: post-fix the FQN set contains names ending with "Car" and "Bike"
    // Pins: INV-007 (F-I-003)
    // Cross-platform: kyo.fixtures.Vehicle is in the embedded fixture set on all platforms.
    "F-I-003 / INV-007 (Phase 07): kyo.fixtures.Vehicle.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty) contains Car and Bike" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            cp.findClassLike("kyo.fixtures.Vehicle") match
                case Present(sym) =>
                    val children = (sym match
                        case c: Tasty.Symbol.Class =>
                            c.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case t: Tasty.Symbol.Trait =>
                            t.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case _ => Chunk.empty
                    )
                    if children.isEmpty then
                        fail(
                            "kyo.fixtures.Vehicle.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty) was empty; expected Chunk(Car, Bike)"
                        )
                    else
                        Kyo.foreach(children)(c => Sync.defer(cp.fullNameUnsafe(c).asString)).map: fqnList =>
                            val fqns = fqnList.toSet
                            assert(
                                fqns.exists(f => f.endsWith("Car") || f.endsWith(".Car")),
                                s"Expected kyo.fixtures.Car in permittedSubclasses of kyo.fixtures.Vehicle; got $fqns"
                            )
                            assert(
                                fqns.exists(f => f.endsWith("Bike") || f.endsWith(".Bike")),
                                s"Expected kyo.fixtures.Bike in permittedSubclasses of kyo.fixtures.Vehicle; got $fqns"
                            )
                            succeed
                    end if
                case Absent =>
                    fail("cp.findClassLike(kyo.fixtures.Vehicle) returned Absent")
    }

    // INV-007 leaf 3 (Phase 07): every-sealed-class-has-permits
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: filtering cp.allClassLike.filter(_.isSealed) and checking permittedSubclasses.nonEmpty
    // Then: post-fix >= 85% of sealed classes have permittedSubclasses populated. RI-005 measured 96.64% on JVM standard
    // classpath (115/119 sealed classes). Embedded fixtures (JS/Native) are 100%: all fixture sealed hierarchies
    // (Animal, Vehicle, SealedBase, Color, Shape) have permits. The 85% floor holds on all three platforms.
    // Pins: INV-007 invariant
    // Cross-platform: embedded fixtures include Animal, Vehicle, SealedBase, Color, Shape; all sealed and have permits.
    "INV-007 (Phase 07): >= 85% of sealed classes have permittedSubclasses populated" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val sealedClasses = cp.allClassLike.filter(_.isSealed)
            val total         = sealedClasses.size
            assert(total > 0, s"No sealed classes found on classpath; fixtures must include at least Animal, Vehicle, SealedBase")
            val withPermits = sealedClasses.count(sc =>
                sc match
                    case c: Tasty.Symbol.Class => c.permittedSubclassIds.exists(_.nonEmpty);
                    case t: Tasty.Symbol.Trait => t.permittedSubclassIds.exists(_.nonEmpty);
                    case _                     => false
            )
            val pct = withPermits.toDouble / total.toDouble * 100.0
            assert(
                pct >= 85.0,
                s"Only ${withPermits}/${total} (${pct.toInt}%) of sealed classes have permittedSubclasses populated; expected >= 85% (RI-005 measured 96.64% on JVM 2026-06-04)"
            )
            succeed
    }

    // F-I-003 leaf 4 (Phase 07): non-sealed-class-returns-empty
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: finding kyo.fixtures.NonSealedMarker (explicitly non-sealed) and calling permittedSubclasses
    // Then: post-fix returns Chunk.empty
    // Pins: F-I-003 negative case (HARD RULE 2: no fabricated Child entries for non-sealed classes)
    // Cross-platform: kyo.fixtures.NonSealedMarker is in the embedded fixture set on all platforms.
    "F-I-003 (Phase 07): non-sealed class permittedSubclasses returns empty Chunk" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            cp.findClass("kyo.fixtures.NonSealedMarker") match
                case Present(s) =>
                    assert(
                        !s.isSealed,
                        s"Expected kyo.fixtures.NonSealedMarker to be non-sealed but got isSealed=${s.isSealed}"
                    )
                    val children = s.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty)
                    Kyo.foreach(children)(c => Sync.defer(cp.fullNameUnsafe(c).asString)).map: childFqns =>
                        assert(
                            children.isEmpty,
                            s"Expected empty Chunk for non-sealed kyo.fixtures.NonSealedMarker.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty); " +
                                s"got ${children.size} children: " +
                                childFqns.mkString(", ")
                        )
                        succeed
                case Absent =>
                    fail("cp.findClass(kyo.fixtures.NonSealedMarker) returned Absent; NonSealedMarker fixture must be in classpath")
            end match
    }

    // F-E-007 leaf 5 (Phase 13): enum-case symbols pattern-match as Symbol.EnumCase
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded Color/Shape)
    // When: finding enum-case symbols (SymbolKind.EnumCase) and checking their runtime type
    // Then: post-fix they are Symbol.EnumCase instances, not only Symbol.Class; passes trivially when empty
    // Pins: F-E-007
    // Cross-platform: embedded Color (Red/Green/Blue value-form) and Shape (Circle/Square/Rectangle class-form)
    //   provide EnumCase symbols on JS/Native. The leaf succeeds vacuously when enumCases.isEmpty.
    "F-E-007 (Phase 13): enum-case symbols pattern-match as Symbol.EnumCase" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val enumCases = cp.symbols.filter(_.kind == SymbolKind.EnumCase)
            if enumCases.isEmpty then
                succeed
            else
                val asEnumCaseCount = enumCases.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
                assert(
                    asEnumCaseCount == enumCases.size,
                    s"Expected all ${enumCases.size} EnumCase-kind symbols to be Symbol.EnumCase instances, " +
                        s"but only $asEnumCaseCount matched. " +
                        s"Phase 13 removed `final` from Symbol.Class and routes SymbolKind.EnumCase to Symbol.EnumCase."
                )
                // Phase 03 change: Symbol.EnumCase is now a peer of Symbol.Class under Symbol.ClassLike.
                // EnumCase is NOT a subtype of Symbol.Class (Decision 10 reversed from Phase 13 expectation).
                val asClassLikeCount = enumCases.count(_.isInstanceOf[Tasty.Symbol.ClassLike])
                assert(
                    asClassLikeCount == enumCases.size,
                    s"Expected Symbol.EnumCase to match Symbol.ClassLike, but $asClassLikeCount out of ${enumCases.size} did"
                )
                val asClassCount = enumCases.count(_.isInstanceOf[Tasty.Symbol.Class])
                assert(
                    asClassCount == 0,
                    s"Phase 03: Symbol.EnumCase is NOT a subtype of Symbol.Class; expected 0 matches but got $asClassCount"
                )
                succeed
            end if
    }

end SealedFidelityTest
