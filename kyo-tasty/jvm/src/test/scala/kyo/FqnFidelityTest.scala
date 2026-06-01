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
    "F-I-001 (Phase 02): fqnIndex contains no doubled-package-segment keys" in run {
        TestClasspaths.withClasspath().map: cp =>
            val doubled = cp.fqnIndex.keys.filter: k =>
                k.contains("scala.scala") || k.contains("kyo.kyo") || k.startsWith("<empty>.")
            assert(
                doubled.isEmpty,
                s"fqnIndex contained ${doubled.size} doubled-segment key(s): ${doubled.take(5).mkString(", ")}"
            )
            succeed
    }

    // F-I-001 leaf 2 (Phase 02): list-resolves-at-canonical-fqn
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.findClassLike("scala.collection.immutable.List")
    // Then: post-fix returns Present(_: Symbol.ClassLike) whose simple name is "List";
    //       before fix returns Absent because the symbol is indexed at the doubled key
    //       "scala.scala.collection.scala.collection.immutable.List"
    // Pins: F-I-001
    "F-I-001 (Phase 02): cp.findClassLike(scala.collection.immutable.List) returns Present" in run {
        import Tasty.Name.asString
        TestClasspaths.withClasspath().map: cp =>
            cp.findClassLike("scala.collection.immutable.List") match
                case Present(sym) =>
                    assert(
                        sym.name.asString == "List",
                        s"Expected simple name 'List', got '${sym.name.asString}'"
                    )
                    succeed
                case Absent =>
                    val matching = cp.fqnIndex.keys
                        .filter(k => k.contains("List") && k.contains("immutable"))
                        .toSeq
                        .sorted
                        .take(3)
                    fail(
                        s"cp.findClassLike(scala.collection.immutable.List) returned Absent. " +
                            s"Matching keys in fqnIndex: ${matching.mkString(", ")}"
                    )
    }

    // F-I-001 / F-A-008 leaf 3 (Phase 02): option-resolves-at-canonical-fqn
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.findClassLike("scala.Option") and cp.findClassLike("scala.Symbol")
    // Then: post-fix both return Present;
    //       before fix both return Absent (FQN doubling: "scala" Package name prepended twice)
    // Pins: F-I-001, F-A-008
    "F-I-001 / F-A-008 (Phase 02): cp.findClassLike(scala.Option) and cp.findClassLike(scala.Symbol) return Present" in run {
        TestClasspaths.withClasspath().map: cp =>
            val optionResult = cp.findClassLike("scala.Option")
            val symbolResult = cp.findClassLike("scala.Symbol")
            val optionKeys   = cp.fqnIndex.keys.filter(_.contains("Option")).toSeq.sorted.take(3)
            val symbolKeys   = cp.fqnIndex.keys.filter(k => k.contains("Symbol") && k.startsWith("scala")).toSeq.sorted.take(3)
            assert(
                optionResult.isDefined,
                s"cp.findClassLike(scala.Option) returned Absent. Related fqnIndex keys: ${optionKeys.mkString(", ")}"
            )
            assert(
                symbolResult.isDefined,
                s"cp.findClassLike(scala.Symbol) returned Absent. Related fqnIndex keys: ${symbolKeys.mkString(", ")}"
            )
            succeed
    }

    // F-A-008 leaf 4 (Phase 02): typerefin-preserves-same-name-duplication
    // Given: a TYPEREFin decode session where the qual's FQN (from unresolvedIdToFqn) equals
    //        the selected simpleName -- the "Map.Map" scenario where both outer and inner names are "Map"
    // When: decoding the TYPEREFin via TypeUnpickler.readTypeIntoSession
    // Then: post-fix the FQN registered in unresolvedIdToFqn is "Map.Map" (outer + "." + inner),
    //       not collapsed to just "Map";
    //       before fix the guard `qualFqn != simpleName` caused the fullFqn to fall back to
    //       simpleName alone, silently dropping the qualifier
    // Pins: F-A-008 (TypeUnpickler.scala:653 qualFqn != simpleName guard removed)
    "F-A-008 (Phase 02): TYPEREFin preserves legitimate same-name qualifications (Map.Map)" in run {
        // Part A: real-classpath regression guard (HARD RULE 1).
        // Verify the real classpath loads and scala.Option is still findable after the
        // TYPEREFin fix. A broken TYPEREFin path would corrupt cross-file type resolution,
        // which would surface here (scala.Option's parent types use TYPEREFin cross-file refs).
        TestClasspaths.withClasspath().flatMap: cp =>
            assert(cp.findClassLike("scala.Option").isDefined, "scala.Option not found in real classpath after TYPEREFin fix")
            // Part B: direct unit verification of the TYPEREFin same-name-collapse fix.
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
                            import kyo.internal.tasty.symbol.SymbolId.value
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
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: cp.findSymbol("scala.Predef") -- source FQN without trailing `$`
    // Then: post-fix returns Present(_: Symbol.Object);
    //       before fix returns Absent because only the binary key "scala.Predef$" was indexed
    // Pins: F-I-006 (HARD RULE 8: source FQN must resolve)
    "F-I-006 (Phase 09): cp.findSymbol(scala.Predef) returns Present(Symbol.Object)" in run {
        TestClasspaths.withClasspath().map: cp =>
            cp.findSymbol("scala.Predef") match
                case Present(sym: Tasty.Symbol.Object) =>
                    succeed
                case Present(sym) =>
                    fail(
                        s"cp.findSymbol(scala.Predef) returned Present but wrong kind: ${sym.kind}. " +
                            s"Expected Symbol.Object."
                    )
                case Absent =>
                    val related = cp.fqnIndex.keys
                        .filter(k => k.startsWith("scala.Predef"))
                        .toSeq
                        .sorted
                        .take(5)
                    fail(
                        s"cp.findSymbol(scala.Predef) returned Absent. " +
                            s"Related fqnIndex keys: ${related.mkString(", ")}"
                    )
    }

    // F-I-006 leaf 2 (Phase 09): binary-fqn-still-resolves
    // Given: the real classpath
    // When: cp.findSymbol("scala.Predef$") -- binary FQN with trailing `$`
    // Then: Present(_) both before and after fix (primary binary key preserved; layered compat)
    // Pins: F-I-006 layer-don't-restrict (HARD RULE 4)
    "F-I-006 (Phase 09): cp.findSymbol(scala.Predef$) still returns Present (binary key preserved)" in run {
        TestClasspaths.withClasspath().map: cp =>
            cp.findSymbol("scala.Predef$") match
                case Present(_) =>
                    succeed
                case Absent =>
                    fail(
                        s"cp.findSymbol(scala.Predef$$) returned Absent; the primary binary key " +
                            s"must remain indexed (HARD RULE 4 layered compat)."
                    )
    }

    // F-I-006 leaf 3 (Phase 09): both-fqns-same-symbol
    // Given: the real classpath
    // When: cp.findSymbol("kyo.Tasty.Symbol") and cp.findSymbol("kyo.Tasty$.Symbol")
    // Then: post-fix both Present(s) with same s.id;
    //       before fix the source FQN "kyo.Tasty.Symbol" was Absent (only "kyo.Tasty$.Symbol" indexed)
    // Pins: F-I-006 identity contract (HARD RULE 8 + HARD RULE 7: same Symbol instance, no mutation)
    "F-I-006 (Phase 09): kyo.Tasty.Symbol and kyo.Tasty$.Symbol resolve to the same Symbol id" in run {
        TestClasspaths.withClasspath().map: cp =>
            val sourceLookup = cp.findSymbol("kyo.Tasty.Symbol")
            val binaryLookup = cp.findSymbol("kyo.Tasty$.Symbol")
            (sourceLookup, binaryLookup) match
                case (Present(s1), Present(s2)) =>
                    assert(
                        s1.id == s2.id,
                        s"Both keys resolve but to DIFFERENT symbols: source id=${s1.id}, binary id=${s2.id}. " +
                            s"Dual-index must point both keys to the same Symbol instance."
                    )
                    succeed
                case (Absent, _) =>
                    val related = cp.fqnIndex.keys
                        .filter(k => k.contains("Tasty") && k.contains("Symbol"))
                        .toSeq
                        .sorted
                        .take(5)
                    fail(
                        s"cp.findSymbol(kyo.Tasty.Symbol) returned Absent (source FQN not indexed). " +
                            s"Related keys: ${related.mkString(", ")}"
                    )
                case (_, Absent) =>
                    fail(
                        s"cp.findSymbol(kyo.Tasty$$.Symbol) returned Absent; the primary binary key " +
                            s"must remain indexed (HARD RULE 4)."
                    )
                case (_, _) =>
                    fail(s"Unexpected match: sourceLookup=$sourceLookup binaryLookup=$binaryLookup")
            end match
    }

    // F-I-006 leaf 4 (Phase 09): non-existent-still-absent
    // Given: the real classpath
    // When: cp.findSymbol("nonexistent.Type") and cp.findSymbol("nonexistent.Type$")
    // Then: both Absent; the dual-index does not fabricate entries for non-existent keys
    // Pins: F-I-006 negative case (no false positives from dual-index writes)
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
