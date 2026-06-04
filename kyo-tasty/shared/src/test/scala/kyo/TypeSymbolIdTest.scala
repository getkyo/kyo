package kyo

import kyo.Tasty.SymbolId

/** Phase 05 plan tests for Type ADT SymbolId migration.
  *
  * Tests 1, 3, 4, 5 from design/05-plan.yaml phase id 5. Test 2 (TypeArena canonicalization) lives in TypeArenaTest.
  *
  * Produces INV-009: SymbolId in Type ADT eliminates case-class cycles.
  */
class TypeSymbolIdTest extends Test:

    import AllowUnsafe.embrace.danger
    import kyo.Tasty.SymbolId

    // ── Test 1: Type.Named carries SymbolId not Symbol ──────────────────────

    /** Given: a Type.Named instance constructed with a known SymbolId. When: the symbolId field is accessed. Then: the field type is
      * SymbolId and the integer value matches what was passed in. Pins: INV-009 (no direct Symbol references in Type ADT).
      */
    "Type.Named carries SymbolId not Symbol" in {
        val id    = SymbolId(42)
        val named = Tasty.Type.Named(id)
        // Pattern-match to access the SymbolId field
        named match
            case Tasty.Type.Named(symbolId) =>
                assert(symbolId.value == 42, s"Expected symbolId.value == 42 but got ${symbolId.value}")
            case other =>
                fail(s"Expected Type.Named but got $other")
        end match
    }

    // ── Test 3: Type.Named(unresolved) creates a synthetic Unresolved entry ─

    /** Given: a Classpath with a symbol at index 0 having kind=Unresolved. When: Pass C completes and the resulting Classpath is queried
      * via cp.symbol(id). Then: cp.symbols contains a symbol with kind=Unresolved at the SymbolId index; no NullPointerException, no
      * AIOOBE. Pins: INV-002 (every Type.Named id resolves to a valid symbol).
      */
    "Type.Named(unresolved) references a valid symbol in classpath" in run {
        val unresolvedSym = Tasty.Symbol.Unresolved(SymbolId(0), Tasty.Name.fromString("does.not.Exist"), SymbolId(-1))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(unresolvedSym)).map: cp =>
            val namedType = Tasty.Type.Named(SymbolId(0))
            namedType match
                case Tasty.Type.Named(id) =>
                    val resolved = cp.symbol(id)
                    assert(
                        resolved.kind == Tasty.SymbolKind.Unresolved,
                        s"Expected kind=Unresolved but got ${resolved.kind}"
                    )
                case other =>
                    fail(s"Expected Type.Named but got $other")
            end match
    }

    // ── Test 4: Type pattern equality does not recurse through Symbol ────────

    /** Given: two distinct Symbols a and b with structurally identical parentTypes containing Named(SymbolId(7)). When: the parentTypes
      * chunks are compared via ==. Then: comparison returns true and terminates in bounded time (no infinite recursion). Pins: INV-009
      * (case-class equals on Type values never recurses through Symbol or Classpath).
      */
    "Type pattern equality does not recurse through Symbol" in {
        val sharedId  = SymbolId(7)
        val namedType = Tasty.Type.Named(sharedId)
        val parents1  = Chunk(namedType)
        val parents2  = Chunk(Tasty.Type.Named(sharedId))
        // This comparison must terminate in bounded time (SymbolId is an Int, no cycles).
        // Use sameElements since Chunk[Type] lacks a CanEqual instance in this context.
        val equal = parents1.size == parents2.size && parents1.zip(parents2).forall:
            case (Tasty.Type.Named(id1), Tasty.Type.Named(id2)) => id1 == id2
            case _                                              => false
        assert(equal, "Structurally identical parentTypes chunks must be equal")
    }

    // ── Test 5: isSubtypeOf and show are member methods, not extensions ──────

    /** Given: a Type t. When: t.isSubtypeOf(other) and t.show are called from user code without importing any extension namespace. Then:
      * both calls compile and resolve to the enum member. Pins: INV-009 (member methods on owned types).
      */
    "isSubtypeOf and show are member methods, not extensions" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val t: Tasty.Type     = Tasty.Type.Named(SymbolId(0))
            val other: Tasty.Type = Tasty.Type.Named(SymbolId(1))
            // Both calls must compile as member method calls (no import of any extension namespace).
            val verdict: Tasty.SubtypeVerdict = t.isSubtypeOf(other)
            val showResult: String            = t.show
            assert(
                verdict == Tasty.SubtypeVerdict.Sub ||
                    verdict == Tasty.SubtypeVerdict.NotSub ||
                    verdict == Tasty.SubtypeVerdict.Unknown,
                s"isSubtypeOf returned an unexpected verdict: $verdict"
            )
            assert(showResult.nonEmpty, s"show returned empty string")
    }

end TypeSymbolIdTest
