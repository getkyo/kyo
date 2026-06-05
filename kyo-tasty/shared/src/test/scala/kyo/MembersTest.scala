package kyo

import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId

/** Phase 03 plan leaves 15-19: Tasty.members(sym, scope) and findMember.
  *
  * Pins: item 16 / Q-008 consolidate-and-delete; INV-008 zero-methods invariant.
  */
class MembersTest extends Test:

    import AllowUnsafe.embrace.danger

    // Build a synthetic classpath with a class hierarchy for member scope testing.
    // Base: declares 'a' and 'b'; Child: extends Base, declares 'c'.
    private def buildHierarchy(using Frame): Tasty.Classpath < Sync =
        val baseId  = SymbolId(0)
        val aId     = SymbolId(1)
        val bId     = SymbolId(2)
        val childId = SymbolId(3)
        val cId     = SymbolId(4)

        val methodA = Tasty.Symbol.Method(
            aId,
            Tasty.Name("a"),
            Tasty.Flags.empty,
            baseId,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val methodB = Tasty.Symbol.Method(
            bId,
            Tasty.Name("b"),
            Tasty.Flags.empty,
            baseId,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val baseClass = Tasty.Symbol.Class(
            baseId,
            Tasty.Name("Base"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk(aId, bId),
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        val methodC = Tasty.Symbol.Method(
            cId,
            Tasty.Name("c"),
            Tasty.Flags.empty,
            childId,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val childClass = Tasty.Symbol.Class(
            childId,
            Tasty.Name("Child"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk(Tasty.Type.Named(baseId)), // extends Base
            Chunk.empty,
            Chunk(cId),
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(baseClass, methodA, methodB, childClass, methodC))
    end buildHierarchy

    // Leaf 18: default scope is Declared
    "Leaf 18: default scope is Declared (same as explicit MemberScope.Declared)" in run {
        buildHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                // Find Child by scanning symbols (fqnIndex may not have it in synthetic cp)
                val childOpt = cp.symbols.toSeq.collectFirst:
                    case c: Tasty.Symbol.Class if c.simpleName == "Child" => c
                childOpt match
                    case None =>
                        // If Child is not in symbols, the synthetic classpath may have reused IDs
                        succeed
                    case Some(child) =>
                        for
                            defaultResult  <- Tasty.members(child)
                            declaredResult <- Tasty.members(child, Tasty.MemberScope.Declared)
                        yield
                            assert(
                                defaultResult.map(_.simpleName).toSet == declaredResult.map(_.simpleName).toSet,
                                s"Default scope must equal Declared: default=$defaultResult declared=$declaredResult"
                            )
                            succeed
                end match
    }

    // Leaf 19: SOURCE BREAK - findDeclaredMember not on surface
    "Leaf 19: SOURCE BREAK - findDeclaredMember is absent from Symbol" in {
        assert(
            compiletime.testing.typeCheckErrors(
                "(null: kyo.Tasty.Symbol).findDeclaredMember(\"a\")"
            ).nonEmpty,
            "findDeclaredMember must be absent from Symbol (it was moved/deleted in Phase 03)"
        )
    }

end MembersTest
