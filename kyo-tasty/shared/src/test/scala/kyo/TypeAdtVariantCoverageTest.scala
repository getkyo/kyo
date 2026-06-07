package kyo

import kyo.Json
import kyo.Tasty.SymbolId

/** Tests for Cat 10 (isContext drop), Cat 14 (Unknown removal), Cat 16 (derives CanEqual), and Schema round-trips for all Type cases.
  *
  *  from the phase-05 plan. Leaf 4 (unknownDeleted) from phase-10 plan.
  */
class TypeAdtVariantCoverageTest extends kyo.test.Test[Any]:

    // unknownDeleted
    // Given: a probe compiletime.testing.typeCheckErrors("kyo.Tasty.Type.Unknown")
    // When: the test asserts
    // Then: the returned list is non-empty (Type.Unknown was removed in Cat 14)
    "Type.Unknown no longer exists after Cat 14 elimination" in {
        val errCount = compiletime.testing.typeCheckErrors("kyo.Tasty.Type.Unknown").length
        assert(errCount > 0, "Expected compile error for Type.Unknown, but got none")
        succeed
    }

    // functionDropsIsContext
    // Given: a probe compileErrors('Type.Function(Chunk.empty, Type.Any, true)')
    // When: the test asserts
    // Then: the returned list is non-empty
    "Type.Function no longer accepts a third isContext argument" in {
        val errCount = compiletime.testing.typeCheckErrors(
            "kyo.Tasty.Type.Function(kyo.Chunk.empty, kyo.Tasty.Type.Any, true)"
        ).length
        assert(errCount > 0, "Expected compile error for three-argument Type.Function, but got none")
        succeed
    }

    // contextFunctionIsTheDedicatedArm
    // Given: a fixture Type.ContextFunction(Chunk(Type.Any), Type.Nothing)
    // When: the test pattern-matches against Type.ContextFunction(_, _)
    // Then: the match arm fires; value reconstructable from its fields
    "Type.ContextFunction is the dedicated arm for context functions" in {
        val cf = Tasty.Type.ContextFunction(Chunk(Tasty.Type.Any), Tasty.Type.Nothing)
        cf match
            case Tasty.Type.ContextFunction(ps, r) =>
                assert(ps.size == 1)
                assert(r == Tasty.Type.Nothing)
            case _ =>
                fail("Expected ContextFunction arm to fire")
        end match
        succeed
    }

    // schemaRoundTripsEveryTypeCase
    // Given: a fixture list with one instance of every post-Cat-10 Type case
    // When: the test encodes via Json.encode (Schema-driven) and reads back via Json.decode
    // Then: every decoded value equals its original (CanEqual)
    "Schema round-trips every Type case" in {
        val n   = Tasty.Type.Named(SymbolId(0))
        val id0 = SymbolId(0)
        val ann = Tasty.Annotation(n, Chunk.empty)
        val cases = List[Tasty.Type](
            Tasty.Type.Named(id0),
            Tasty.Type.TermRef(n, Tasty.Name("x")),
            Tasty.Type.TypeRef(n, Tasty.Name("T")),
            Tasty.Type.Applied(n, Chunk(n)),
            Tasty.Type.TypeLambda(Chunk.empty, n),
            Tasty.Type.Function(Chunk(n), n),
            Tasty.Type.ContextFunction(Chunk(n), n),
            Tasty.Type.Tuple(Chunk(n, n)),
            Tasty.Type.ByName(n),
            Tasty.Type.Repeated(n),
            Tasty.Type.Array(n),
            Tasty.Type.Refinement(n, Tasty.Name("m"), n),
            Tasty.Type.Rec(n),
            Tasty.Type.RecThis(n),
            Tasty.Type.AndType(n, n),
            Tasty.Type.OrType(n, n),
            Tasty.Type.Annotated(n, ann),
            Tasty.Type.ConstantType(Tasty.Constant.IntConst(42)),
            Tasty.Type.ThisType(id0),
            Tasty.Type.SuperType(n, n),
            Tasty.Type.ParamRef(id0, 0),
            Tasty.Type.Wildcard(n, n),
            Tasty.Type.Skolem(n),
            Tasty.Type.MatchType(n, n, Chunk.empty),
            Tasty.Type.MatchCase(n, n),
            Tasty.Type.Bounds(n, n),
            Tasty.Type.FlexibleType(n),
            Tasty.Type.Nothing,
            Tasty.Type.Any
        )
        val encoded = Json.encode[List[Tasty.Type]](cases)
        Json.decode[List[Tasty.Type]](encoded) match
            case Result.Success(decoded) =>
                assert(decoded == cases, s"Schema round-trip failed: decoded != original")
                succeed
            case Result.Failure(e) =>
                fail(s"decode failed: $e")
            case Result.Panic(t) =>
                throw t
        end match
    }

end TypeAdtVariantCoverageTest
