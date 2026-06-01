package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for opaque type detection and dual FQN indexing.
  *
  * Pins findings F-E-001, F-E-002, and F-I-002. All leaves were PENDING until Phase 06 un-pended them by adding dual-index
  * registration for OpaqueType symbols in `ClasspathOrchestrator.mergeOneInto`, so that both the binary FQN
  * (`kyo.Maybe$package$.Maybe`) and the source FQN (`kyo.Maybe`) point to the same `Symbol.OpaqueType` instance.
  */
class OpaqueTypeFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-E-001 / INV-006 leaf 1 (Phase 06): kyo-maybe
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: calling cp.findSymbol("kyo.Maybe")
    // Then: post-fix Present(s: Symbol.OpaqueType) with s.name.asString == "Maybe";
    //       before fix Absent because the opaque type body was indexed under
    //       "kyo.Maybe$package$.Maybe" with kind=OpaqueType but cp.findSymbol("kyo.Maybe") returned Absent
    // Pins: INV-006 producer (F-E-001, F-I-002)
    "F-E-001 / INV-006 (Phase 06): cp.findSymbol(kyo.Maybe) returns Present(Symbol.OpaqueType)" in run {
        import Tasty.Name.asString
        TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary).map: cp =>
            cp.findSymbol("kyo.Maybe") match
                case Present(sym: Tasty.Symbol.OpaqueType) =>
                    assert(
                        sym.name.asString == "Maybe",
                        s"Expected symbol name 'Maybe', got '${sym.name.asString}'"
                    )
                    succeed
                case Present(other) =>
                    val relatedKeys = cp.fqnIndex.keys.filter(k => k.contains("Maybe") && k.startsWith("kyo")).toSeq.sorted.take(5)
                    fail(
                        s"cp.findSymbol(kyo.Maybe) returned Present but wrong kind: ${other.getClass.getSimpleName}. " +
                            s"Related fqnIndex keys: ${relatedKeys.mkString(", ")}"
                    )
                case Absent =>
                    val relatedKeys = cp.fqnIndex.keys.filter(k => k.contains("Maybe") && k.startsWith("kyo")).toSeq.sorted.take(5)
                    fail(
                        s"cp.findSymbol(kyo.Maybe) returned Absent. " +
                            s"Related fqnIndex keys: ${relatedKeys.mkString(", ")}"
                    )
    }

    // F-E-002 leaf 2 (Phase 06): kyo-result-kyo-duration
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: calling cp.findSymbol("kyo.Result") and cp.findSymbol("kyo.Duration")
    // Then: post-fix both return Present(_: Symbol.OpaqueType);
    //       before fix both returned Absent (same root cause as F-E-001: no dual-index for $package$ opaque types)
    // Pins: F-E-002
    "F-E-002 (Phase 06): cp.findSymbol(kyo.Result) and kyo.Duration return Present(Symbol.OpaqueType)" in run {
        import Tasty.Name.asString
        TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary).map: cp =>
            val resultSym   = cp.findSymbol("kyo.Result")
            val durationSym = cp.findSymbol("kyo.Duration")
            val resultKeys =
                cp.fqnIndex.keys.filter(k => k.contains("Result") && k.startsWith("kyo")).toSeq.sorted.take(5)
            val durationKeys =
                cp.fqnIndex.keys.filter(k => k.contains("Duration") && k.startsWith("kyo")).toSeq.sorted.take(5)
            assert(
                resultSym.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"cp.findSymbol(kyo.Result) expected Present(OpaqueType), got $resultSym. " +
                    s"Related fqnIndex keys: ${resultKeys.mkString(", ")}"
            )
            assert(
                durationSym.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"cp.findSymbol(kyo.Duration) expected Present(OpaqueType), got $durationSym. " +
                    s"Related fqnIndex keys: ${durationKeys.mkString(", ")}"
            )
            succeed
    }

    // Q-003 / F-I-002 leaf 3 (Phase 06): binary-fqn-still-findable
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: calling cp.findSymbol("kyo.Maybe$package$.Maybe")
    // Then: post-fix Present(_) (the binary FQN remains resolvable per HARD RULE 4 layer-don't-restrict;
    //       the binary key maps to the Val accessor -- the val forwarder that was always there -- while
    //       the source FQN "kyo.Maybe" maps to the OpaqueType via dual-index);
    //       also verify cp.findSymbol("kyo.Maybe") returns the OpaqueType (the primary user-facing entry)
    // Pins: Q-003 dual-index contract (HARD RULE 4: layer-don't-restrict)
    "Q-003 (Phase 06): opaque type is findable via its binary FQN kyo.Maybe$package$.Maybe" in run {
        TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary).map: cp =>
            // The binary FQN must be resolvable (HARD RULE 4: keep what was already findable)
            val binaryResult = cp.findSymbol("kyo.Maybe$package$.Maybe")
            assert(
                binaryResult.isDefined,
                s"cp.findSymbol(kyo.Maybe$$package$$.Maybe) returned Absent. " +
                    s"Related fqnIndex keys: ${cp.fqnIndex.keys.filter(_.contains("Maybe")).toSeq.sorted.take(5).mkString(", ")}"
            )
            // The source FQN must return the OpaqueType (per HARD RULE 8 and dual-index)
            cp.findSymbol("kyo.Maybe") match
                case Present(_: Tasty.Symbol.OpaqueType) => succeed
                case Present(other) =>
                    fail(
                        s"cp.findSymbol(kyo.Maybe) returned Present but wrong kind: ${other.getClass.getSimpleName}. " +
                            s"Expected OpaqueType for the source FQN."
                    )
                case Absent =>
                    fail("Source FQN kyo.Maybe returned Absent -- dual-index incomplete")
            end match
    }

    // INV-006 leaf 4 (Phase 06): no-opaque-as-val
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: calling cp.findSymbol("kyo.Maybe") (the SOURCE FQN, the canonical user-visible name)
    //       AND examining cp.allOpaqueTypes for the presence of a "Maybe" opaque type
    // Then: post-fix cp.findSymbol("kyo.Maybe") returns a Symbol.OpaqueType (not Symbol.Val);
    //       cp.allOpaqueTypes includes a symbol named "Maybe";
    //       before fix cp.findSymbol("kyo.Maybe") returned Absent and the opaque type was NOT
    //       findable at its source FQN (only the val forwarder was visible from user-level APIs)
    // Pins: INV-006 (opaque types appear as Symbol.OpaqueType, findable at source FQN)
    "INV-006 (Phase 06): kyo.Maybe symbol is Symbol.OpaqueType, not Symbol.Val" in run {
        import Tasty.Name.asString
        TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary).map: cp =>
            // The canonical kyo.Maybe must exist as OpaqueType at the source FQN
            val maybeSym = cp.findSymbol("kyo.Maybe")
            assert(
                maybeSym.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"cp.findSymbol(kyo.Maybe) must return OpaqueType, got: $maybeSym"
            )
            // allOpaqueTypes must include kyo.Maybe (regression guard: confirms it's not classified as Val)
            val allOpaqueNames = cp.allOpaqueTypes.map(_.name.asString).toSet
            assert(
                allOpaqueNames.contains("Maybe"),
                s"cp.allOpaqueTypes must contain a symbol named 'Maybe'. " +
                    s"Found opaque type names: ${allOpaqueNames.toSeq.sorted.take(10).mkString(", ")}"
            )
            succeed
    }

end OpaqueTypeFidelityTest
