package kyo

import kyo.Tasty.SymbolId

/** Tests for Phase 06 Leaf 4: every Symbol subtype carries sourcePosition as a Maybe[Position].
  *
  * Pins: INV-012; PRESERVE-M.
  *
  * Leaf 4: everySymbolSubtypeCarriesSourcePosition.
  * Given: one instance of each Symbol subtype.
  * When: the test reads sym.sourcePosition for each.
  * Then: every value is a Maybe[Position]; pattern matches against Maybe.Present(p) and Maybe.Absent are exhaustive.
  */
class SymbolAdtVariantCoverageTest extends Test:

    // ── Fixture helpers ──────────────────────────────────────────────────────

    private val id0   = SymbolId(0)
    private val id1   = SymbolId(1)
    private val name0 = Tasty.Name("T")
    private val flags = Tasty.Flags.empty
    private val pos   = Tasty.Position("T.scala", 1, 1)
    private val anyTy = Tasty.Type.Any

    private val classLikeChunkFields =
        (Chunk.empty[Tasty.Type], Chunk.empty[SymbolId], Chunk.empty[SymbolId], Maybe.Absent: Maybe[Chunk[SymbolId]])

    // ── Leaf 4: everySymbolSubtypeCarriesSourcePosition ──────────────────────

    // Given: one instance of each of the 15 Symbol subtypes.
    // When: the test reads sym.sourcePosition.
    // Then: all values are Maybe[Position]; Present and Absent arms exhaustively cover the type.
    // Pins: INV-012; PRESERVE-M.
    "Leaf 4: every Symbol subtype carries sourcePosition as Maybe[Position]" in {
        val present: Maybe[Tasty.Position] = Maybe.Present(pos)
        val absent: Maybe[Tasty.Position]  = Maybe.Absent

        val cls = Tasty.Symbol.Class(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty,
            body = Maybe.Absent
        )
        val enumCase = Tasty.Symbol.EnumCase(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty,
            body = Maybe.Absent
        )
        val trt = Tasty.Symbol.Trait(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty,
            body = Maybe.Absent
        )
        val obj = Tasty.Symbol.Object(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty,
            body = Maybe.Absent
        )
        val method = Tasty.Symbol.Method(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            declaredType = Maybe.Absent,
            paramListIds = Chunk.empty,
            typeParamIds = Chunk.empty,
            annotations = Chunk.empty,
            body = Maybe.Absent,
            javaMetadata = Maybe.Absent
        )
        val valSym = Tasty.Symbol.Val(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            declaredType = Maybe.Absent,
            annotations = Chunk.empty,
            body = Maybe.Absent
        )
        val varSym = Tasty.Symbol.Var(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            declaredType = Maybe.Absent,
            annotations = Chunk.empty,
            body = Maybe.Absent
        )
        val field = Tasty.Symbol.Field(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            declaredType = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            javaAnnotations = Chunk.empty
        )
        val typeAlias = Tasty.Symbol.TypeAlias(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            body = anyTy,
            typeParamIds = Chunk.empty,
            annotations = Chunk.empty
        )
        val opaqueType = Tasty.Symbol.OpaqueType(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            body = anyTy,
            bounds = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            typeParamIds = Chunk.empty,
            annotations = Chunk.empty
        )
        val abstractType = Tasty.Symbol.AbstractType(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            bounds = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            annotations = Chunk.empty
        )
        val typeParam = Tasty.Symbol.TypeParam(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            sourcePosition = present,
            bounds = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            variance = Tasty.Variance.Invariant
        )
        val parameter = Tasty.Symbol.Parameter(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            sourcePosition = present,
            declaredType = anyTy,
            defaultArgId = Maybe.Absent,
            annotations = Chunk.empty
        )
        val pkg = Tasty.Symbol.Package(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            memberIds = Chunk.empty
        )
        val unresolved = Tasty.Symbol.Unresolved(
            id = id0,
            name = name0,
            ownerId = id1
        )

        // All non-Package/Unresolved subtypes were constructed with sourcePosition = Present(pos).
        val withPresent: List[Tasty.Symbol] =
            List(cls, enumCase, trt, obj, method, valSym, varSym, field, typeAlias, opaqueType, abstractType, typeParam, parameter)

        // Package and Unresolved always return Absent.
        val withAbsent: List[Tasty.Symbol] = List(pkg, unresolved)

        for sym <- withPresent do
            sym.sourcePosition match
                case Maybe.Present(p) =>
                    assert(
                        p.sourceFile == "T.scala",
                        s"${sym.getClass.getSimpleName}: expected sourceFile='T.scala' but got '${p.sourceFile}'"
                    )
                case Maybe.Absent =>
                    fail(s"${sym.getClass.getSimpleName}: expected Present sourcePosition but got Absent")
            end match
        end for

        for sym <- withAbsent do
            sym.sourcePosition match
                case Maybe.Absent => ()
                case Maybe.Present(p) =>
                    fail(s"${sym.getClass.getSimpleName}: expected Absent sourcePosition but got Present($p)")
            end match
        end for

        succeed
    }

end SymbolAdtVariantCoverageTest
