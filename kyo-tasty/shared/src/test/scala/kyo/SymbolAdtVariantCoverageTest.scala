package kyo

import kyo.Tasty.SymbolId

/** Tests for Leaf 4: every Symbol subtype carries sourcePosition as a Maybe[Position].
  *
  * everySymbolSubtypeCarriesSourcePosition.
  * Given: one instance of each Symbol subtype.
  * When: the test reads sym.sourcePosition for each.
  * Then: every value is a Maybe[Position]; pattern matches against Maybe.Present(p) and Maybe.Absent are exhaustive.
  */
class SymbolAdtVariantCoverageTest extends kyo.test.Test[Any]:

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
    "every Symbol subtype carries sourcePosition as Maybe[Position]" in {
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
            javaAnnotations = Chunk.empty
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
            javaAnnotations = Chunk.empty
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
            javaAnnotations = Chunk.empty
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
            javaAnnotations = Chunk.empty
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
            annotations = Chunk.empty
        )
        val varSym = Tasty.Symbol.Var(
            id = id0,
            name = name0,
            flags = flags,
            ownerId = id1,
            scaladoc = Maybe.Absent,
            sourcePosition = present,
            declaredType = Maybe.Absent,
            annotations = Chunk.empty
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
            body = Maybe.Present(anyTy),
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
            body = Maybe.Present(anyTy),
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
            declaredType = Maybe.Present(anyTy),
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
        val unresolved = Tasty.Symbol.Package(
            id = id0,
            name = name0,
            Tasty.Flags.empty,
            ownerId = id1,
            Chunk.empty
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

    // ── body field removed from all Symbol subtypes ────────────────

    // Confirms that ClassLike subtypes no longer carry a body field (Cat 17 Option A).
    "Symbol.Class has no body field (ClassLike.body accessor removed)" in {
        val __tcErrors1 = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Class).body").length

        assert(__tcErrors1 > 0, "Symbol.Class must not have a body field")
        val __tcErrors2 = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.ClassLike).body").length

        assert(__tcErrors2 > 0, "Symbol.ClassLike must not have a body accessor")
        succeed
    }

    // Confirms that Method, Val, Var no longer carry a body field (Cat 17 Option A).
    "Symbol.Method, Val, Var have no body field" in {
        val __tcErrors3 = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Method).body").length

        assert(__tcErrors3 > 0, "Symbol.Method must not have a body field")
        val __tcErrors4 = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Val).body").length

        assert(__tcErrors4 > 0, "Symbol.Val must not have a body field")
        val __tcErrors5 = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Var).body").length

        assert(__tcErrors5 > 0, "Symbol.Var must not have a body field")
        succeed
    }

    // ── unresolvedCaseDeleted ───────────────────────────────

    // Confirms that Symbol.Unresolved is not a valid symbol subtype (Cat 19 deletion).
    "unresolvedCaseDeleted: Symbol.Unresolved is not part of the ADT" in {
        // Symbol.Unresolved was deleted in. A compileErrors probe must return non-empty.
        val __tcErrors6 = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Unresolved)").length

        assert(__tcErrors6 > 0, "Symbol.Unresolved must not exist after ")
        succeed
    }

    // ── makePlaceholderDeleted ───────────────────────────────

    // Confirms that Symbol.makePlaceholder is not a valid method (Cat 19 deletion).
    "makePlaceholderDeleted: Symbol.makePlaceholder is not available" in {
        // Symbol.makePlaceholder was deleted in. A compileErrors probe must return non-empty.
        val __tcErrors7 = compiletime.testing.typeCheckErrors("kyo.Tasty.Symbol.makePlaceholder(???, ???, ???)").length

        assert(__tcErrors7 > 0, "Symbol.makePlaceholder must not exist after ")
        succeed
    }

end SymbolAdtVariantCoverageTest
