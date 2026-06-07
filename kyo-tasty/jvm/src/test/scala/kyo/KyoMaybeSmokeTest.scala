package kyo

import kyo.internal.TestClasspaths

/** JVM-only smoke tests that pin the G-1 and G-2 chains on the real kyo.Maybe type.
  *
  * JVM-only rationale: real-classpath cold-load via TestClasspaths.standard uses java.class.path
  * discovery which is JVM mechanics. No JS/Native equivalent (AGENTS.md exception bar #2: a
  * JVM-only primitive that no Kyo module wraps cross-platform). The embedded fixture path
  * (kyo.fixtures.Meters) covers cross-platform parity in ParamListIdsPopulationTest.
  *
  * Leaf summary:
  *   12. maybe_opaque_found: NOT pending. cp.findSymbol("kyo.Maybe") returns an OpaqueType today.
  *       Regression baseline (find already works pre-fix).
  *   13. maybe_companion_symmetric: PENDING until Phase 02. Tasty.companion(maybeSym) returns Absent
  *       today because buildCompanionIndex omits the OpaqueType arm (G-2 gap).
  *   14. maybe_extension_present: PENDING until Phase 02. Depends on companion being reachable
  *       (leaf 13) so that members of the companion include extension methods.
  *   15. maybe_paramListIds_populated: PENDING until Phase 02. Extension methods on the Maybe
  *       companion have empty paramListIds today (G-1 gap).
  *   16. maybe_receiver_chain: PENDING until Phase 04. Depends on Tasty.paramLists (Phase 04).
  *
  * TestClasspaths.standard includes: kyo-tasty + kyo-data + scala-library + kyo-tasty-fixtures.
  * kyo.Maybe lives in kyo-data, which is included in standard.
  */
class KyoMaybeSmokeTest extends kyo.test.Test[Any]:

    // ── Leaf 12: maybe_opaque_found (NOT pending, regression baseline) ────────
    // Given: JVM-only classpath via TestClasspaths.withClasspath(TestClasspaths.standard).
    // When: cp.findSymbol("kyo.Maybe") is invoked.
    // Then: result is Present(maybeSym) where maybeSym.kind == SymbolKind.OpaqueType.
    // Pins: JVM leg of INV-H7 entry. Not pending (the find already succeeds pre-fix).
    // JVM-only: real-classpath cold-load via java.class.path is JVM mechanics.
    "kyo.Maybe is Symbol.OpaqueType in the standard classpath (INV-H7 JVM entry)".onlyJvm in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath(TestClasspaths.standard)(Tasty.classpath).map: cp =>
            cp.findSymbol("kyo.Maybe") match
                case Maybe.Present(sym: Tasty.Symbol.OpaqueType) =>
                    assert(
                        sym.name.asString == "Maybe",
                        s"Expected name 'Maybe', got '${sym.name.asString}'"
                    )
                    succeed
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.Maybe but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    val keys = cp.indices.byFqn.toMap.keys.filter(_.contains("Maybe")).toSeq.sorted.take(5)
                    fail(s"cp.findSymbol('kyo.Maybe') returned Absent. FQN keys: ${keys.mkString(", ")}")
    }

    // ── Leaf 13: maybe_companion_symmetric (PENDING until Phase 02) ───────────
    // Given: same JVM fixture; maybeSym resolved from leaf 12.
    // When: Tasty.companion(maybeSym) and Tasty.companion(companion) are invoked.
    // Then: Tasty.companion(maybeSym) == Present(companion)
    //       AND companion.kind == SymbolKind.Object
    //       AND Tasty.companion(companion) == Present(maybeSym).
    // Pins: INV-H7 on JVM real classpath (G-2 fix).
    // JVM-only: real-classpath cold-load.
    "kyo.Maybe companion is symmetric (INV-H7 JVM)".onlyJvm.pendingUntilFixed(
        "G-2: buildCompanionIndex omits OpaqueType arm; flipped in Phase 02"
    ) in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath(TestClasspaths.standard)(Tasty.classpath).flatMap: cp =>
            cp.findSymbol("kyo.Maybe") match
                case Maybe.Present(maybeSym: Tasty.Symbol.OpaqueType) =>
                    Tasty.companion(maybeSym).map: maybeCompanion =>
                        maybeCompanion match
                            case Maybe.Present(companion: Tasty.Symbol.Object) =>
                                assert(
                                    companion.name.asString == "Maybe",
                                    s"Companion object name must be 'Maybe', got '${companion.name.asString}'"
                                )
                                val reverseCompanion = cp.companion(companion)
                                assert(
                                    reverseCompanion == Maybe.Present(maybeSym),
                                    s"Reverse companion(companion) must equal maybeSym; got $reverseCompanion"
                                )
                                succeed
                            case Maybe.Present(other) =>
                                fail(s"Expected Object companion but got: ${other.getClass.getSimpleName}")
                            case Maybe.Absent =>
                                fail("Tasty.companion(maybeSym) returned Absent; G-2 not yet fixed")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.Maybe but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.Maybe not found")
    }

    // ── Leaf 14: maybe_extension_present (PENDING until Phase 02) ────────────
    // Given: same JVM fixture; companion (Maybe object) resolved from leaf 13.
    // When: Tasty.members(companion, MemberScope.Declared) is called and filtered for
    //       extension methods named "get".
    // Then: result is non-empty AND the first element's scaladoc.isPresent == true.
    // Pins: end-to-end chain (companion -> members -> extension) on real kyo.Maybe.
    //       Fails today because the companion is not reachable via Tasty.companion (G-2 gap).
    // JVM-only: real-classpath cold-load.
    "kyo.Maybe companion has extension method 'get' with scaladoc (chain test)".onlyJvm.pendingUntilFixed(
        "G-2: companion not reachable for OpaqueType; flipped in Phase 02"
    ) in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath(TestClasspaths.standard)(Tasty.classpath).flatMap: cp =>
            cp.findSymbol("kyo.Maybe") match
                case Maybe.Present(maybeSym: Tasty.Symbol.OpaqueType) =>
                    Tasty.companion(maybeSym).flatMap: maybeCompanion =>
                        maybeCompanion match
                            case Maybe.Present(companion) =>
                                Tasty.members(companion, Tasty.MemberScope.Declared).map: members =>
                                    val getExtensions = members.filter: sym =>
                                        sym match
                                            case m: Tasty.Symbol.Method =>
                                                m.name.asString == "get" && m.isExtension
                                            case _ => false
                                    assert(
                                        getExtensions.nonEmpty,
                                        s"Expected extension method 'get' in Maybe companion members; got: ${members.map(_.name.asString).mkString(", ")}"
                                    )
                                    getExtensions.headOption match
                                        case Some(extMethod) =>
                                            assert(
                                                extMethod.scaladoc.isDefined,
                                                s"Expected scaladoc on 'get' extension method but it was Absent"
                                            )
                                            succeed
                                        case None =>
                                            fail("get extension method not found (already checked nonEmpty above)")
                                    end match
                            case Maybe.Absent =>
                                fail("Tasty.companion(maybeSym) returned Absent; G-2 not yet fixed")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.Maybe but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.Maybe not found")
    }

    // ── Leaf 15: maybe_paramListIds_populated (PENDING until Phase 02) ────────
    // Given: same JVM fixture; extMethod (a "get" extension) resolved from leaf 14.
    // When: extMethod.paramListIds is read.
    // Then: extMethod.paramListIds.size >= 1 AND extMethod.paramListIds.head.size == 1.
    // Pins: INV-H1 on real classpath (G-1 fix: paramListIds populated by Pass C).
    //       Fails today because Pass C never writes paramListIds.
    // JVM-only: real-classpath cold-load.
    "kyo.Maybe 'get' extension method has non-empty paramListIds (INV-H1 JVM)".onlyJvm.pendingUntilFixed(
        "G-1: Pass C never writes paramListIds; flipped in Phase 02"
    ) in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath(TestClasspaths.standard)(Tasty.classpath).flatMap: cp =>
            cp.findSymbol("kyo.Maybe") match
                case Maybe.Present(maybeSym: Tasty.Symbol.OpaqueType) =>
                    Tasty.companion(maybeSym).flatMap: maybeCompanion =>
                        maybeCompanion match
                            case Maybe.Present(companion) =>
                                Tasty.members(companion, Tasty.MemberScope.Declared).map: members =>
                                    val getExtensions = members.filter: sym =>
                                        sym match
                                            case m: Tasty.Symbol.Method =>
                                                m.name.asString == "get" && m.isExtension
                                            case _ => false
                                    getExtensions.headOption match
                                        case Some(extMethod: Tasty.Symbol.Method) =>
                                            assert(
                                                extMethod.paramListIds.size >= 1,
                                                s"Expected paramListIds.size >= 1 but got ${extMethod.paramListIds.size}; G-1 not yet fixed"
                                            )
                                            assert(
                                                extMethod.paramListIds.head.size == 1,
                                                s"Expected paramListIds.head.size == 1 but got ${extMethod.paramListIds.head.size}"
                                            )
                                            succeed
                                        case Some(other) =>
                                            fail(s"Expected Method for get extension but got: ${other.getClass.getSimpleName}")
                                        case None =>
                                            fail("get extension method not found in Maybe companion")
                                    end match
                            case Maybe.Absent =>
                                fail("Tasty.companion(maybeSym) returned Absent; G-2 not yet fixed")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.Maybe but got: ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.Maybe not found")
    }

    // ── Leaf 16: maybe_receiver_chain (ignored until Phase 04) ────────────────
    // Given: same JVM fixture; extMethod from leaf 14.
    // When: Tasty.paramLists(extMethod).head.head.declaredType.flatMap(Tasty.typeSymbol) is computed.
    // Then: result is Present(maybeSym) (same OpaqueType as leaf 12).
    // Pins: INV-H6 on real classpath (positional receiver rule via the paramLists helper).
    //       Depends on both G-1 (Phase 02) and Tasty.paramLists (Phase 04).
    // JVM-only: real-classpath cold-load plus helper signature dependency.
    "kyo.Maybe 'get' receiver chain via paramLists resolves to Maybe OpaqueType (INV-H6 JVM)".onlyJvm.ignore(
        "Tasty.paramLists helper not yet added (Phase 04); depends on G-1 + G-2 (Phase 02)"
    ) in {
        fail("Tasty.paramLists not yet implemented; body populated in Phase 04")
    }

end KyoMaybeSmokeTest
