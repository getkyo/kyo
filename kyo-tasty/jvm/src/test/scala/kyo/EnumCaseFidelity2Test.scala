package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Fidelity tests for Symbol.EnumCase reclassification (F-A2-010).
  *
  * Before Phase 2.06, only class-form enum cases (TYPEDEF with Enum+Case flags, no Module) were reclassified to Symbol.EnumCase in
  * finalizeMerge. Simple value-form enum cases (VALDEF with Enum+Case flags, e.g. `case Red, Green, Blue`) and singleton object enum
  * cases (Module+Enum+Case) were classified as Symbol.Val or Symbol.Object and never promoted. Java enum constants (Field+Enum+Static)
  * were classified as Symbol.Field.
  *
  * Phase 2.06 extends the finalizeMerge post-process to cover all four forms:
  *   - Class + Enum + Case (no Module): class-form (Phase 15 reclassification, preserved)
  *   - Val + Enum + Case: simple value-form (this phase)
  *   - Object + Enum + Case: singleton-object form (this phase)
  *   - Field + Enum + JavaDefined + Static: Java enum constant (this phase)
  *
  * Invariants produced: INV-105-DF2 (value-form EnumCase half), F-A2-010 closed.
  *
  * Test file is JVM-only because Fidelity2TestBase (which provides coldWarmEquiv) depends on TestClasspaths2.standardWithSnapshot
  * (JVM filesystem for snapshot write/read).
  */
class EnumCaseFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString

    // Leaf 1 (Phase 2.06): scala-compiletime-ops-enums-have-cases
    // Given: standard classpath (includes kyo-tasty which defines kyo.Tasty.SymbolKind, a value-form enum)
    // When: collecting EnumCase symbols owned by or reachable from kyo.Tasty.SymbolKind
    // Then: post-fix count > 0; before fix count is 0 (value-form cases were Symbol.Val)
    // Pins: F-A2-010
    "F-A2-010 (Phase 2.06): kyo.Tasty.SymbolKind has EnumCase children" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp

            val enumCasesOwnedBySymbolKind = cp.symbols.collect:
                case e: Tasty.Symbol.EnumCase
                    if cp.symbol(e.ownerId).name.asString.startsWith("SymbolKind") =>
                    e

            assert(
                enumCasesOwnedBySymbolKind.nonEmpty,
                s"Expected EnumCase children for kyo.Tasty.SymbolKind but found 0. " +
                    s"All EnumCase count: ${cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])}"
            )
            succeed
    }

    // Leaf 2 (Phase 2.06): every-enum-has-non-empty-cases
    // Given: standard classpath with 17 enums
    // When: for each enum class, collecting Symbol.EnumCase reachable via permittedSubclasses
    //   (class-form cases) or via the companion object's declarations (value-form cases)
    // Then: post-fix every enum has at least 1 EnumCase; before fix 15 of 17 had 0
    // Note: class-form enum cases appear as permittedSubclasses of the enum parent class;
    //   value-form enum cases appear in the companion object's declarations (the companion
    //   is the single declaration of the sealed enum parent class).
    // Pins: INV-105-DF2 producer; F-A2-010
    "INV-105-DF2 (Phase 2.06): every enum class has at least one EnumCase child" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp

            // Filter to enum PARENT classes only; exclude EnumCase instances (which also have the Enum
            // flag set and extend Symbol.Class, so they appear in allClasses). EnumCase symbols are the
            // cases themselves; the parent class is the sealed abstract class that owns them.
            val enums = cp.allClasses.filter(e => e.isEnum && !e.isInstanceOf[Tasty.Symbol.EnumCase]).toList
            assert(enums.nonEmpty, "Expected at least one enum class on the standard classpath")

            def hasEnumCase(e: Tasty.Symbol.ClassLike): Boolean =
                // Check class-form cases: in permittedSubclasses
                val classFormCases = e.permittedSubclasses.map(_.exists(_.isInstanceOf[Tasty.Symbol.EnumCase]))
                if classFormCases.getOrElse(false) then true
                else
                    // Check value-form cases: in companion object's declarations
                    e.declarations.exists: decl =>
                        decl match
                            case companion: Tasty.Symbol.Object =>
                                companion.declarations.exists(_.isInstanceOf[Tasty.Symbol.EnumCase])
                            case _ => false
                end if
            end hasEnumCase

            val enumsWithNoCases = enums.filterNot(hasEnumCase)

            assert(
                enumsWithNoCases.isEmpty,
                s"${enumsWithNoCases.size} enum(s) have no EnumCase children after Phase 2.06 fix: " +
                    enumsWithNoCases.take(5).map(e => cp.fullName(e).asString).mkString(", ")
            )
            succeed
    }

    // Leaf 3 (Phase 2.06): total-enumcase-count-grows
    // Given: standard classpath
    // When: counting cp.symbols that are Symbol.EnumCase
    // Then: post-fix count > 10; before fix exactly 2 (probe-001.log line 39911)
    // Pins: F-A2-010
    "F-A2-010 (Phase 2.06): total EnumCase count > 10 after reclassification" in run {
        TestClasspaths.withClasspath().map: cp =>
            val enumCaseCount = cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                enumCaseCount > 10,
                s"Expected > 10 EnumCase symbols after Phase 2.06 fix; found $enumCaseCount. " +
                    s"Before fix was 2."
            )
            succeed
    }

    // Leaf 4 (Phase 2.06): value-form-enumcase-owner-is-companion
    // Given: kyo.Tasty.SymbolKind enum value-form cases (Package, Class, Trait, etc.)
    // When: inspecting each SymbolKind EnumCase's ownerId
    // Then: post-fix every SymbolKind EnumCase owner is the companion Object (SymbolKind$)
    // Note: we use SymbolKind specifically because it is a pure value-form enum with no parametric
    //   cases, ensuring all its cases were originally VALDEFs. Generic "declarations.isEmpty" checks
    //   also match class-form cases whose owner resolution resolved to the self-referential sentinel
    //   (a pre-existing issue unrelated to this phase). Scoping to SymbolKind avoids that ambiguity.
    // Pins: F-A2-010
    "F-A2-010 (Phase 2.06): SymbolKind value-form EnumCase owner is the companion Object" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp

            val symbolKindCases = cp.symbols.collect:
                case e: Tasty.Symbol.EnumCase
                    if cp.symbol(e.ownerId).name.asString.startsWith("SymbolKind") =>
                    e

            assert(
                symbolKindCases.nonEmpty,
                "No SymbolKind EnumCase symbols found after Phase 2.06 fix"
            )

            val wrongOwner = symbolKindCases.filter: e =>
                cp.symbol(e.ownerId) match
                    case _: Tasty.Symbol.Object => false
                    case _                      => true

            assert(
                wrongOwner.isEmpty,
                s"${wrongOwner.size} SymbolKind EnumCase symbols have non-Object owner: " +
                    wrongOwner.take(3).map: e =>
                        val ownerKind = cp.symbol(e.ownerId).getClass.getSimpleName
                        s"${e.name.asString} (owner: $ownerKind)"
                    .mkString(", ")
            )
            succeed
    }

    // Leaf 5 (Phase 2.06): class-form-enumcase-still-works
    // Given: kyo.TastyError enum (class-form, all cases have constructor parameters)
    // When: inspecting its class-form enum cases via permittedSubclasses after Phase 2.06
    // Then: permittedSubclasses contains EnumCase symbols (regression guard for Phase 15 fix)
    // Note: class-form enum cases are permittedSubclasses of the enum sealed class,
    //   not direct declarations. Direct declarations contain only the companion object.
    // Pins: F-A2-010 (HARD RULE 4 layered preservation)
    "F-A2-010 (Phase 2.06): class-form EnumCase (TastyError) still correctly classified" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp

            cp.findClass("kyo.TastyError") match
                case Maybe.Present(tastyError) =>
                    val permSubEnumCases = tastyError.permittedSubclasses.map:
                        _.collect { case e: Tasty.Symbol.EnumCase => e }
                    .getOrElse(Chunk.empty)
                    assert(
                        permSubEnumCases.nonEmpty,
                        s"kyo.TastyError has no EnumCase permitted subclasses after Phase 2.06 " +
                            s"(regression: permittedSubclasses = ${tastyError.permittedSubclasses})"
                    )
                    succeed
                case Maybe.Absent =>
                    val tastyErrors = cp.symbols.collect:
                        case c: Tasty.Symbol.Class if c.name.asString == "TastyError" => c
                    assert(
                        tastyErrors.nonEmpty,
                        "kyo.TastyError not found on the standard classpath (kyo-tasty should be on it)"
                    )
                    val hasEnumCases = tastyErrors.exists: te =>
                        te.permittedSubclasses.exists(_.exists(_.isInstanceOf[Tasty.Symbol.EnumCase]))
                    assert(
                        hasEnumCases,
                        "kyo.TastyError has no EnumCase permitted subclasses (class-form regression)"
                    )
                    succeed
            end match
    }

    // Leaf 6 (Phase 2.06): snapshot-roundtrip-preserves-enumcase
    // Given: (cold, warm) Classpath pair from the standard classpath
    // When: comparing EnumCase counts across cold and warm
    // Then: equal across cold and warm (snapshot round-trip preserves EnumCase classification)
    // Pins: INV-101-DF2 + INV-105-DF2
    coldWarmEquiv("INV-101-DF2 (Phase 2.06): EnumCase count is equal across cold and warm") { cp =>
        cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
    }

    // Leaf 7 (Phase 2.06): java-enum-constants-are-enumcase
    // Given: standard classpath + java.base JDK classfiles (java.lang.annotation.RetentionPolicy)
    // When: inspecting RetentionPolicy's declared EnumCase members
    // Then: post-fix RetentionPolicy's enum constants (RUNTIME, CLASS, SOURCE) are Symbol.EnumCase
    //       before fix they were Symbol.Field (Field + Enum + JavaDefined + Static)
    // Pins: F-A2-010 (Java enum interop); requires java.base on the classpath via Phase 2.03 infra
    "F-A2-010 (Phase 2.06): Java enum constants (RetentionPolicy) are Symbol.EnumCase" in run {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            given Tasty.Classpath = cp

            cp.findClass("java.lang.annotation.RetentionPolicy") match
                case Maybe.Present(rp) =>
                    val enumCaseDecls = rp.declarations.collect:
                        case e: Tasty.Symbol.EnumCase => e
                    assert(
                        enumCaseDecls.nonEmpty,
                        s"java.lang.annotation.RetentionPolicy has no EnumCase declarations. " +
                            s"All declarations: ${rp.declarations.toList.take(5).map(d =>
                                    d.name.asString + ":" + d.getClass.getSimpleName
                                ).mkString(", ")}"
                    )
                    val expectedNames = Set("RUNTIME", "CLASS", "SOURCE")
                    val foundNames    = enumCaseDecls.map(_.name.asString).toSet
                    val missing       = expectedNames -- foundNames
                    assert(
                        missing.isEmpty,
                        s"RetentionPolicy EnumCase declarations missing: ${missing.mkString(", ")}; found: ${foundNames.mkString(", ")}"
                    )
                    succeed
                case Maybe.Absent =>
                    fail(
                        "java.lang.annotation.RetentionPolicy not found on platform-modules classpath. " +
                            "Verify Phase 2.03 (JPMS) and TestClasspaths2.standardWithPlatformModules are working."
                    )
            end match
    }

end EnumCaseFidelity2Test
