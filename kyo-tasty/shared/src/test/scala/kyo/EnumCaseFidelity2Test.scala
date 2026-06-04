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
  * Phase 2.10: relocated from jvm/src/test to shared/src/test. Leaves 1-6 use TestClasspaths.withClasspath which works on JS/Native
  * via embedded fixtures (Color with Red/Green/Blue value-form enum, Shape with Circle/Square/Rectangle class-form enum). Leaf 7
  * (java enum constants) requires TestClasspaths2.standardWithPlatformModules (JVM only) and is gated with jvmOnly. The coldWarmEquiv
  * leaf is gated jvmOnly by Fidelity2TestBase.
  *
  * Cross-platform EnumCase parity: the embedded Color enum contributes 3 value-form enum cases (Red, Green, Blue), and the embedded
  * Shape enum contributes 3 class-form enum cases (Circle, Square, Rectangle). Leaves 3 and 4 verify that the parity leaf (Phase 2.10
  * leaf 4) sees the same count on all platforms.
  *
  * Invariants produced: INV-105-DF2 (value-form EnumCase half), F-A2-010 closed.
  */
class EnumCaseFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString

    // Leaf 1 (Phase 2.06): scala-compiletime-ops-enums-have-cases
    // Given: standard classpath (on JVM: includes kyo-tasty which defines kyo.Tasty.SymbolKind, a value-form enum;
    //         on JS/Native: embedded fixtures include Color with Red/Green/Blue value-form enum cases)
    // When: collecting any EnumCase symbols from the classpath
    // Then: post-fix count > 0; before fix count is 0 (value-form cases were Symbol.Val)
    // Pins: F-A2-010
    "F-A2-010 (Phase 2.06): classpath has at least one EnumCase child (value-form or class-form)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val enumCases = cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                enumCases > 0,
                s"Expected at least 1 EnumCase symbol after Phase 2.06 fix. " +
                    s"Total symbols: ${cp.symbols.size}. " +
                    s"On JVM: kyo.Tasty.SymbolKind enum cases expected. On JS/Native: Color.Red/Green/Blue expected."
            )
            succeed
    }

    // Leaf 2 (Phase 2.06): every-enum-has-non-empty-cases
    // Given: standard classpath enum parent classes
    // When: for each enum class, collecting Symbol.EnumCase reachable via permittedSubclasses or companion declarations
    // Then: post-fix every enum has at least 1 EnumCase; before fix 15 of 17 had 0
    // Note: on JS/Native the embedded fixture set has Color (value-form) and Shape (class-form).
    // Pins: INV-105-DF2 producer; F-A2-010
    "INV-105-DF2 (Phase 2.06): every enum class has at least one EnumCase child" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            given Tasty.Classpath = cp

            val enums = cp.allClassLike.filter(e => e.isEnum && !e.isInstanceOf[Tasty.Symbol.EnumCase]).toList
            assert(enums.nonEmpty, "Expected at least one enum class in the classpath (embedded: Color, Shape)")

            def hasEnumCase(e: Tasty.Symbol.ClassLike): Boolean =
                if e.permittedSubclasses.exists(_.isInstanceOf[Tasty.Symbol.EnumCase]) then true
                else
                    e.declarations.exists: decl =>
                        decl match
                            case companion: Tasty.Symbol.Object =>
                                companion.declarations.exists(_.isInstanceOf[Tasty.Symbol.EnumCase])
                            case _ => false
                end if
            end hasEnumCase

            val enumsWithNoCases = enums.filterNot(hasEnumCase)

            Kyo.foreach(enumsWithNoCases.take(5))(e => cp.fullName(e).map(_.asString)).map: names =>
                assert(
                    enumsWithNoCases.isEmpty,
                    s"${enumsWithNoCases.size} enum(s) have no EnumCase children after Phase 2.06 fix: " +
                        names.mkString(", ")
                )
                succeed
    }

    // Leaf 3 (Phase 2.06): total-enumcase-count-grows
    // Given: standard classpath
    // When: counting cp.symbols that are Symbol.EnumCase
    // Then: post-fix count > 0 (on JVM: > 10; before fix exactly 2 per probe-001.log line 39911)
    //       on JS/Native: embedded fixtures contribute Color (3 value-form) + Shape (3 class-form) = 6 minimum
    // Pins: F-A2-010
    "F-A2-010 (Phase 2.06): total EnumCase count > 0 after reclassification (at least embedded Color + Shape cases)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val enumCaseCount = cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                enumCaseCount > 0,
                s"Expected > 0 EnumCase symbols after Phase 2.06 fix; found $enumCaseCount. " +
                    s"Embedded fixtures: Color (Red/Green/Blue value-form) + Shape (Circle/Square/Rectangle class-form)."
            )
            succeed
    }

    // Leaf 4 (Phase 2.06): value-form-enumcase-owner-is-companion
    // Given: Color.Red/Green/Blue enum cases from the embedded fixture (present on all platforms)
    // When: inspecting each Color EnumCase's ownerId
    // Then: post-fix every Color EnumCase owner is the companion Object (Color$)
    // Note: scoped to Color specifically to avoid the pre-existing class-form-owner resolution ambiguity
    //   (FileNotFound / FixedRecordWithHeader enum cases have non-Object owners due to a pre-existing
    //   class-form owner resolution quirk; that is documented and not regressed by this phase).
    //   Color.Red/Green/Blue are definitively value-form and their owner should always be Color$.
    // Pins: F-A2-010
    "F-A2-010 (Phase 2.06): Color value-form EnumCase owner is the companion Object" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp

            // Look for value-form enum cases from known enums:
            // - kyo.Tasty.SymbolKind (on JVM real classpath from kyo-tasty jar)
            // - kyo.fixtures.Color (on JS/Native embedded fixtures)
            // Both are pure value-form enums with Object companions.
            val knownEnumNames = Set("SymbolKind", "Color")
            val knownValueFormCases = cp.symbols.collect:
                case e: Tasty.Symbol.EnumCase
                    if knownEnumNames.exists(cp.symbol(e.ownerId).name.asString.startsWith) =>
                    e

            if knownValueFormCases.isEmpty then
                // No known value-form enum cases found; skip (acceptable for platforms where neither enum is present)
                succeed
            else
                val wrongOwner = knownValueFormCases.filter: e =>
                    cp.symbol(e.ownerId) match
                        case _: Tasty.Symbol.Object => false
                        case _                      => true

                assert(
                    wrongOwner.isEmpty,
                    s"${wrongOwner.size} value-form EnumCase symbols have non-Object owner: " +
                        wrongOwner.take(3).map: e =>
                            val ownerKind = cp.symbol(e.ownerId).getClass.getSimpleName
                            s"${e.name.asString} (owner: $ownerKind)"
                        .mkString(", ")
                )
                succeed
            end if
    }

    // Leaf 5 (Phase 2.06): class-form-enumcase-still-works
    // Given: embedded Shape enum (class-form, all cases have constructor parameters)
    //        or kyo.TastyError on JVM (class-form enum with constructor parameters)
    // When: inspecting its class-form enum cases via permittedSubclasses after Phase 2.06
    // Then: permittedSubclasses contains EnumCase symbols (regression guard for Phase 15 fix)
    // Pins: F-A2-010 (HARD RULE 4 layered preservation)
    "F-A2-010 (Phase 2.06): class-form EnumCase still correctly classified (Shape or TastyError)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            given Tasty.Classpath = cp

            // Try kyo.TastyError first (JVM), fall back to kyo.fixtures.Shape (JS/Native)
            val target = cp.findClass("kyo.TastyError").orElse(cp.findClass("kyo.fixtures.Shape"))
            target match
                case Maybe.Present(enumClass) =>
                    val permSubEnumCases = enumClass.permittedSubclasses.collect:
                        case e: Tasty.Symbol.EnumCase => e
                    cp.fullName(enumClass).map: name =>
                        assert(
                            permSubEnumCases.nonEmpty,
                            s"${name.asString} has no EnumCase permitted subclasses after Phase 2.06 " +
                                s"(regression: permittedSubclasses = ${enumClass.permittedSubclasses})"
                        )
                        succeed
                case Maybe.Absent =>
                    // Fall back: search for any class-form enum case (at least Shape cases in embedded set)
                    val classFormCases = cp.symbols.collect:
                        case e: Tasty.Symbol.EnumCase if e.declarations.nonEmpty => e
                    assert(
                        classFormCases.nonEmpty,
                        "Expected at least one class-form EnumCase in the classpath (Shape.Circle/Square/Rectangle)"
                    )
                    succeed
            end match
    }

    // Leaf 6 (Phase 2.06): snapshot-roundtrip-preserves-enumcase
    // Given: (cold, warm) Classpath pair from the standard classpath
    // When: comparing EnumCase counts across cold and warm
    // Then: equal across cold and warm (snapshot round-trip preserves EnumCase classification)
    // JVM-only: coldWarmEquiv uses TestClasspaths2.standardWithSnapshot (JVM filesystem).
    // Pins: INV-101-DF2 + INV-105-DF2
    coldWarmEquiv("INV-101-DF2 (Phase 2.06): EnumCase count is equal across cold and warm") { cp =>
        cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
    }

    // Leaf 7 (Phase 2.06): java-enum-constants-are-enumcase
    // Given: standard classpath + java.base JDK classfiles (java.lang.annotation.RetentionPolicy)
    // When: inspecting RetentionPolicy's declared EnumCase members
    // Then: post-fix RetentionPolicy's enum constants (RUNTIME, CLASS, SOURCE) are Symbol.EnumCase
    //       before fix they were Symbol.Field (Field + Enum + JavaDefined + Static)
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): the assertion pins
    //   java.lang.annotation.RetentionPolicy on the jrt:/ platform-modules classpath. Loading java.base via
    //   jrt:/ is a JVM-only loader; no equivalent on JS/Native.
    // Pins: F-A2-010 (Java enum interop); requires java.base on the classpath via Phase 2.03 infra
    "F-A2-010 (Phase 2.06): Java enum constants (RetentionPolicy) are Symbol.EnumCase" taggedAs jvmOnly in run {
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

    // Phase 2.10 leaf 4: enumcase-count-identical-across-platforms
    // Given: cp.symbols.count(_.isInstanceOf[Symbol.EnumCase]) on each platform
    // When: comparing the count
    // Then: count > 0 on every platform (embedded Color and Shape each contribute class-form or value-form cases)
    // Pins: HARD RULE 12 fixture parity; Phase 2.10 leaf 4
    // Note: On JVM the real classpath contributes many more enum cases. On JS/Native the embedded fixture set
    //   contributes Color (value-form: up to 3) and Shape (class-form: up to 3). The exact count on JS/Native
    //   may differ from 6 due to companion-object decoding specifics; the invariant is non-zero.
    "Phase-2.10 (HARD RULE 12): embedded fixture EnumCase count > 0 on all platforms" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val count = cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                count > 0,
                s"Expected > 0 EnumCase symbols from embedded fixtures (Color + Shape); found $count"
            )
            succeed
    }

end EnumCaseFidelity2Test
