package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Fidelity tests for FQN computation correctness.
  *
  * Pins findings F-I-001 and F-A-008. All leaves were PENDING until Phase 02 un-pended them by fixing `ClasspathOrchestrator.computeFqn`
  * (halt walk at Package owners) and `TypeUnpickler` (TYPEREFin always concatenates when qual is non-empty).
  *
  * Phase 2.12: relocated from jvm/src/test to shared/src/test. Leaves 2, 3, 5, 6, 7 rewritten to use embedded fixture FQNs
  * (kyo.fixtures.PlainClass, kyo.fixtures.SomeCaseClass, kyo.fixtures.SomeObject) instead of stdlib classes.
  * Leaf 4 (TYPEREFin unit test) was already cross-platform. All leaves are now cross-platform.
  */
class FqnFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-I-001 / INV-002 leaf 1 (Phase 02): no-doubled-segments
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: scanning every key of cp.indices.byFqn
    // Then: post-fix zero keys contain the substring "scala.scala" or "kyo.kyo";
    //       zero keys begin with "<empty>.";
    //       before fix keys like "scala.scala.collection.scala.collection.immutable.List" appear
    // Pins: INV-002 producer (F-I-001)
    // Cross-platform: invariant "no doubled segments" holds for any classpath after the fix; passes on embedded fixtures.
    "F-I-001 (Phase 02): fqnIndex contains no doubled-package-segment keys" in run {
        TestClasspaths.withClasspath().map: cp =>
            val doubled = cp.indices.byFqn.keys.filter: k =>
                k.contains("scala.scala") || k.contains("kyo.kyo") || k.startsWith("<empty>.")
            assert(
                doubled.isEmpty,
                s"fqnIndex contained ${doubled.size} doubled-segment key(s): ${doubled.take(5).mkString(", ")}"
            )
            succeed
    }

    // F-I-001 leaf 2 (Phase 02): plainclass-resolves-at-canonical-fqn
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.findClassLike("kyo.fixtures.PlainClass")
    // Then: post-fix returns Present(_: Symbol.ClassLike) whose simple name is "PlainClass"
    // Pins: F-I-001
    // Cross-platform: kyo.fixtures.PlainClass is in the embedded fixture set on all platforms.
    "F-I-001 (Phase 02): cp.findClassLike(kyo.fixtures.PlainClass) returns Present" in run {
        import Tasty.Name.asString
        TestClasspaths.withClasspath().map: cp =>
            cp.findClassLike("kyo.fixtures.PlainClass") match
                case Present(sym) =>
                    assert(
                        sym.name.asString == "PlainClass",
                        s"Expected simple name 'PlainClass', got '${sym.name.asString}'"
                    )
                    succeed
                case Absent =>
                    val matching = cp.indices.byFqn.keys
                        .filter(k => k.contains("PlainClass") && k.contains("fixtures"))
                        .toSeq
                        .sorted
                        .take(3)
                    fail(
                        s"cp.findClassLike(kyo.fixtures.PlainClass) returned Absent. " +
                            s"Matching keys in fqnIndex: ${matching.mkString(", ")}"
                    )
    }

    // F-I-001 / F-A-008 leaf 3 (Phase 02): fixture-classes-resolve-at-canonical-fqn
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.findClassLike("kyo.fixtures.SomeCaseClass") and cp.findClassLike("kyo.fixtures.SomeTrait")
    // Then: post-fix both return Present
    // Pins: F-I-001, F-A-008
    // Cross-platform: both fixture classes are in the embedded fixture set on all platforms.
    "F-I-001 / F-A-008 (Phase 02): cp.findClassLike(kyo.fixtures.SomeCaseClass) and cp.findClassLike(kyo.fixtures.SomeTrait) return Present" in run {
        TestClasspaths.withClasspath().map: cp =>
            val caseClassResult = cp.findClassLike("kyo.fixtures.SomeCaseClass")
            val traitResult     = cp.findClassLike("kyo.fixtures.SomeTrait")
            val caseClassKeys   = cp.indices.byFqn.keys.filter(_.contains("SomeCaseClass")).toSeq.sorted.take(3)
            val traitKeys       = cp.indices.byFqn.keys.filter(k => k.contains("SomeTrait") && k.startsWith("kyo")).toSeq.sorted.take(3)
            assert(
                caseClassResult.isDefined,
                s"cp.findClassLike(kyo.fixtures.SomeCaseClass) returned Absent. Related fqnIndex keys: ${caseClassKeys.mkString(", ")}"
            )
            assert(
                traitResult.isDefined,
                s"cp.findClassLike(kyo.fixtures.SomeTrait) returned Absent. Related fqnIndex keys: ${traitKeys.mkString(", ")}"
            )
            succeed
    }

    // F-A-008 leaf 4 (Phase 02): typerefin-preserves-same-name-duplication
    // Given: a TYPEREFin decode session where the qual's FQN equals the selected simpleName ("Map.Map" scenario)
    // When: decoding the TYPEREFin via TypeUnpickler.readTypeIntoSession
    // Then: post-fix the FQN registered in unresolvedIdToFqn is "Map.Map" (outer + "." + inner)
    // Pins: F-A-008 (TypeUnpickler.scala:653 qualFqn != simpleName guard removed)
    // Cross-platform: purely algorithmic, no filesystem or classpath access.
    "F-A-008 (Phase 02): TYPEREFin preserves legitimate same-name qualifications (Map.Map)" in run {
        // Part A: real-classpath regression guard (JVM-only; gated via the full leaf above).
        // Leaf 4 tests only Part B: the direct unit verification of the TYPEREFin same-name-collapse fix.
        // Build a synthetic TYPEREFin where the qual FQN and simpleName both equal "Map".
        // Pre-fix: fullFqn = "Map"  (guard `qualFqn != simpleName` drops the qualifier)
        // Post-fix: fullFqn = "Map.Map" (only the nonEmpty guard remains)
        //
        // Nat encoding: value n < 128 -> (n | 0x80).toByte (last byte with stop-bit set).
        def encNat(n: Int): Array[Byte] =
            if n < 128 then Array((n | 0x80).toByte)
            else Array((n >> 7).toByte, ((n & 0x7f) | 0x80).toByte)
        val names = Array(Tasty.Name("scala"), Tasty.Name("Map"))
        // qual  = TYPEREFpkg nameRef=1 -> "Map"; tracked by the session as qualFqn="Map"
        // ns    = TYPEREFpkg nameRef=1  (namespace, ignored in FQN reconstruction)
        val qualBytes    = TastyFormat.TYPEREFpkg.toByte +: encNat(1)
        val nsBytes      = TastyFormat.TYPEREFpkg.toByte +: encNat(1)
        val innerPayload = encNat(1) ++ qualBytes ++ nsBytes // nameRef=1 -> simpleName="Map"
        val fullBytes    = TastyFormat.TYPEREFin.toByte +: (encNat(innerPayload.length) ++ innerPayload)
        val arena        = TypeArena.canonical()
        val session      = new TypeUnpickler.DecodeSession(names, new mutable.HashMap(), arena)
        val view         = ByteView(fullBytes)
        Abort.run[TastyError]:
            Sync.defer:
                val decoded = TypeUnpickler.readTypeIntoSession(view, session)
                decoded match
                    case Tasty.Type.Named(sid) =>
                        import kyo.Tasty.SymbolId.value
                        val fqn = session.unresolvedIdToFqn.getOrElse(
                            sid.value,
                            s"<not-found; id=${sid.value}>"
                        )
                        assert(
                            fqn == "Map.Map",
                            s"TYPEREFin collapse bug: expected FQN 'Map.Map' but got '$fqn'. " +
                                s"Before fix the guard 'qualFqn != simpleName' dropped the qualifier " +
                                s"when both were 'Map', producing FQN 'Map' instead of 'Map.Map'."
                        )
                    case other =>
                        fail(s"Expected Named type from TYPEREFin decode but got: $other")
                end match
        .map:
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"TYPEREFin decode failed: $e")
            case Result.Panic(t)   => throw t
    }

    // F-I-006 leaf 1 (Phase 09): source-fqn-resolves
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: cp.findSymbol("kyo.fixtures.SomeObject") -- source FQN without trailing `$`
    // Then: post-fix returns Present(_: Symbol.Object)
    // Pins: F-I-006 (HARD RULE 8: source FQN must resolve)
    // Cross-platform: kyo.fixtures.SomeObject is in the embedded fixture set on all platforms.
    "F-I-006 (Phase 09): cp.findSymbol(kyo.fixtures.SomeObject) returns Present(Symbol.Object)" in run {
        TestClasspaths.withClasspath().map: cp =>
            cp.findSymbol("kyo.fixtures.SomeObject") match
                case Present(sym: Tasty.Symbol.Object) =>
                    succeed
                case Present(sym) =>
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject) returned Present but wrong kind: ${sym.kind}. " +
                            s"Expected Symbol.Object."
                    )
                case Absent =>
                    val related = cp.indices.byFqn.keys
                        .filter(k => k.startsWith("kyo.fixtures.SomeObject"))
                        .toSeq
                        .sorted
                        .take(5)
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject) returned Absent. " +
                            s"Related fqnIndex keys: ${related.mkString(", ")}"
                    )
    }

    // F-I-006 leaf 2 (Phase 09): binary-fqn-still-resolves
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: cp.findSymbol("kyo.fixtures.SomeObject$") -- binary FQN with trailing `$`
    // Then: Present(_) both before and after fix (primary binary key preserved; layered compat)
    // Pins: F-I-006 layer-don't-restrict (HARD RULE 4)
    // Cross-platform: kyo.fixtures.SomeObject is in the embedded fixture set on all platforms.
    "F-I-006 (Phase 09): cp.findSymbol(kyo.fixtures.SomeObject$) still returns Present (binary key preserved)" in run {
        TestClasspaths.withClasspath().map: cp =>
            cp.findSymbol("kyo.fixtures.SomeObject$") match
                case Present(_) =>
                    succeed
                case Absent =>
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject$$) returned Absent; the primary binary key " +
                            s"must remain indexed (HARD RULE 4 layered compat)."
                    )
    }

    // F-I-006 leaf 3 (Phase 09): both-fqns-same-symbol
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: cp.findSymbol("kyo.fixtures.SomeObject") and cp.findSymbol("kyo.fixtures.SomeObject$")
    // Then: post-fix both Present(s) with same s.id
    // Pins: F-I-006 identity contract (HARD RULE 8 + HARD RULE 7)
    // Cross-platform: kyo.fixtures.SomeObject is in the embedded fixture set on all platforms.
    "F-I-006 (Phase 09): kyo.fixtures.SomeObject and kyo.fixtures.SomeObject$ resolve to the same Symbol id" in run {
        TestClasspaths.withClasspath().map: cp =>
            val sourceLookup = cp.findSymbol("kyo.fixtures.SomeObject")
            val binaryLookup = cp.findSymbol("kyo.fixtures.SomeObject$")
            (sourceLookup, binaryLookup) match
                case (Present(s1), Present(s2)) =>
                    assert(
                        s1.id == s2.id,
                        s"Both keys resolve but to DIFFERENT symbols: source id=${s1.id}, binary id=${s2.id}. " +
                            s"Dual-index must point both keys to the same Symbol instance."
                    )
                    succeed
                case (Absent, _) =>
                    val related = cp.indices.byFqn.keys
                        .filter(k => k.contains("SomeObject") && k.startsWith("kyo"))
                        .toSeq
                        .sorted
                        .take(5)
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject) returned Absent (source FQN not indexed). " +
                            s"Related keys: ${related.mkString(", ")}"
                    )
                case (_, Absent) =>
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject$$) returned Absent; the primary binary key " +
                            s"must remain indexed (HARD RULE 4)."
                    )
                case (_, _) =>
                    fail(s"Unexpected match: sourceLookup=$sourceLookup binaryLookup=$binaryLookup")
            end match
    }

    // F-I-006 leaf 4 (Phase 09): non-existent-still-absent
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: cp.findSymbol("nonexistent.Type") and cp.findSymbol("nonexistent.Type$")
    // Then: both Absent; the dual-index does not fabricate entries for non-existent keys
    // Pins: F-I-006 negative case (no false positives from dual-index writes)
    // Cross-platform: "nonexistent.Type Absent" holds for any classpath; negative assertion is universal.
    "F-I-006 (Phase 09): cp.findSymbol(nonexistent.Type) and cp.findSymbol(nonexistent.Type$) return Absent" in run {
        TestClasspaths.withClasspath().map: cp =>
            val r1 = cp.findSymbol("nonexistent.Type")
            val r2 = cp.findSymbol("nonexistent.Type$")
            assert(
                r1.isEmpty,
                s"cp.findSymbol(nonexistent.Type) returned Present; dual-index fabricated a spurious entry."
            )
            assert(
                r2.isEmpty,
                s"cp.findSymbol(nonexistent.Type$$) returned Present; dual-index fabricated a spurious entry."
            )
            succeed
    }

end FqnFidelityTest
