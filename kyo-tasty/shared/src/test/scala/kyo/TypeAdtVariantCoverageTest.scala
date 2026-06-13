package kyo

import kyo.Json
import kyo.Tasty.SymbolId

/** Tests that removed Type ADT features (Type.Unknown, isContext argument, Unresolved kind)
  * are absent from the surface, and that all Type cases derive CanEqual and round-trip via Schema.
  */
class TypeAdtVariantCoverageTest extends kyo.test.Test[Any]:

    "Type.Unknown does not exist" in {
        val errCount = compiletime.testing.typeCheckErrors("kyo.Tasty.Type.Unknown").length
        assert(errCount > 0, "Expected compile error for Type.Unknown, but got none")
        succeed
    }

    "Type.Function takes two arguments; a third isContext argument does not compile" in {
        val errCount = compiletime.testing.typeCheckErrors(
            "kyo.Tasty.Type.Function(kyo.Chunk.empty, kyo.Tasty.Type.Any, true)"
        ).length
        assert(errCount > 0, "Expected compile error for three-argument Type.Function, but got none")
        succeed
    }

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

    "Schema round-trips every Type case" in {
        val n          = Tasty.Type.Named(SymbolId(0))
        val id0        = SymbolId(0)
        val annotation = Tasty.Annotation(n, Chunk.empty, Tasty.Name(""))
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
            Tasty.Type.Annotated(n, annotation),
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
