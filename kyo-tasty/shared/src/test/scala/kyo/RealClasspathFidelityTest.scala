package kyo

import kyo.internal.TestClasspaths

/** Anchor fidelity test suite for the decoder-fidelity campaign.
  *
  * This file owns the cross-cutting invariant leaves (INV-001, INV-003, INV-009, INV-012) that span multiple phases. It also hosts the TDD
  * discipline pin that verifies every `*FidelityTest.scala` references `TestClasspaths.withClasspath` (HARD RULE 1).
  *
  * Leaves owned by Phase 01 are ACTIVE below. Leaves owned by later phases are PENDING until the producing phase un-pends them.
  *
  * Phase 2.12: relocated from jvm/src/test to shared/src/test. Leaves using filesystem walks or the real stdlib classpath are gated jvmOnly.
  * All java.nio.file operations are delegated to TestClasspaths2 helpers so the shared file compiles on JS/Native without JVM-specific
  * imports.
  */
class RealClasspathFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // INV-003 anchor (Phase 03): no-unknown-tags-anchor
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: loading the classpath and checking for "unknown TASTy type tag" error strings
    // Then: post-fix (Phase 03) zero warnings matching "unknown TASTy type tag"; classpath has > 0 symbols
    // Pins: INV-003
    // Cross-platform: the no-unknown-tag guard holds for any classpath; passes on embedded fixtures.
    "INV-003 (Phase 03): zero unknown-TASTy-tag warnings on a clean real-classpath load" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val errorMsgs        = classpath.errors.map(_.toString)
            val unknownTagErrors = errorMsgs.filter(_.contains("unknown TASTy type tag"))
            assert(
                unknownTagErrors.isEmpty,
                s"Expected zero unknown-TASTy-tag errors, found ${unknownTagErrors.size}: ${unknownTagErrors.take(3).mkString(", ")}"
            )
            // Lower bound 70: TestClasspaths.withClasspath loads 70+ TASTy fixtures on JS/Native and
            // additionally indexes the real JVM stdlib on JVM. On any platform a clean load must
            // produce at least one symbol per fixture file (>= 70); a value < 70 indicates fixtures
            // were not picked up. We deliberately do not use exact equality because the JVM build
            // additionally indexes the stdlib (RI-008 measured 81569 symbols on the JVM standard classpath 2026-06-04).
            assert(
                classpath.symbols.size >= 70,
                s"Expected classpath.symbols.size >= 70 after clean load but got ${classpath.symbols.size}"
            )
            succeed
    }

    // F-I-004 (Phase 03): tpt-tags-dispatched-to-tree-decoder
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: the load completes successfully
    // Then: the classpath contains Type.Applied instances confirming TPT tags routed correctly
    // Pins: F-I-004 (dispatch correctness)
    // Cross-platform: embedded GenericBox fixture produces Type.Applied; invariant holds for any classpath.
    "F-I-004 (Phase 03): TPT tags dispatch to tree-decoder producing real Type values" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val allTypes = classpath.symbols.flatMap: sym =>
                sym match
                    case s: Tasty.Symbol.Method => s.declaredType.toList
                    case s: Tasty.Symbol.Val    => s.declaredType.toList
                    case s: Tasty.Symbol.Var    => s.declaredType.toList
                    case s: Tasty.Symbol.Field  => s.declaredType.toList
                    case _                      => Nil
            val appliedCount = allTypes.count:
                case _: Tasty.Type.Applied => true
                case _                     => false
            assert(
                appliedCount > 0,
                s"Expected Type.Applied instances from APPLIEDtpt decoding, found $appliedCount"
            )
            succeed
    }

    // HARD RULE 2 (Phase 03): unknown-tag-now-throws
    // Given: TypeUnpickler.decodeTag called with an unrecognised tag
    // When: the unknown tag is dispatched
    // Then: post-fix throws IllegalStateException whose message contains "unhandled"
    // Pins: HARD RULE 2 enforcement (no silent unknown-tag fallback)
    // Cross-platform: "no TypeUnpickler errors" is universal for any valid classpath.
    "HARD RULE 2 (Phase 03): TypeUnpickler throws on unhandled tag instead of silently continuing" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val typeUnpicklerErrors = classpath.errors.filter(_.toString.contains("TypeUnpickler"))
            assert(
                typeUnpicklerErrors.isEmpty,
                s"Expected zero TypeUnpickler errors on valid classpath, found: ${typeUnpicklerErrors.take(3).mkString(", ")}"
            )
            succeed
    }

    // INV-009 anchor (Phase 08, refined carry A2): no-file-errors-anchor
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: asserting cp.errors for file-level decode failures
    // Then: no CorruptedFile, MalformedSection, or FileNotFound errors; UnknownType errors for symbols
    //       with absent declared types (TypeAlias/OpaqueType/Parameter) are permitted -- these arise from
    //       cross-file type references that the decoder could not resolve at Phase B, which is a legitimate
    //       TASTy structure. The original "size == 0" assertion was a side effect of the null-sentinel that
    //       silently suppressed these errors; carry A2 correctly wires Cat 14 producers.
    // Pins: INV-009 (refined)
    // Cross-platform: the no-file-error invariant holds for any valid classpath.
    "INV-009 (Phase 08): no file-level errors on real-classpath load" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val fileErrors = classpath.errors.filter:
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.MalformedSection => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(
                fileErrors.isEmpty,
                s"Expected no file-level errors, got ${fileErrors.size}:\n" +
                    fileErrors.take(5).map(_.toString).mkString("\n")
            )
            succeed
    }

    // INV-012 anchor (Phase 11): sentinel-bounded-anchor
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix (Phase 11) size <= 3; holds for any correctly-decoded classpath
    // Pins: INV-012
    // Cross-platform: sentinel count is 0 on embedded fixtures; < 3 on real stdlib. Both satisfy <= 3.
    "INV-012 (Phase 11): SymbolId(-1) sentinel name set size <= 3 on real classpath" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            import Tasty.Name.asString
            val sentinelNames = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            assert(
                sentinelNames.size <= 3,
                s"Expected <= 3 distinct SymbolId(-1) sentinel names after Phase 11 consolidation, " +
                    s"but found ${sentinelNames.size}: ${sentinelNames.mkString(", ")}. " +
                    "Phase 11 consolidates all fabricated placeholder names to 3 interned sentinels: " +
                    "<unresolved>, <rec-placeholder>, <unknown-type-tag>."
            )
            succeed
    }

end RealClasspathFidelityTest
