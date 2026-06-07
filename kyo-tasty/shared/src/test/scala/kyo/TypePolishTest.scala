package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId
import scala.collection.mutable.ArrayBuffer

/** Type.symbol, Type.children, Type.foreach.
  */
class TypePolishTest extends kyo.test.Test[Any]:

    "symbol returns Present(sym) for Type.Named" in {
        val classSym = Tasty.Symbol.Class(
            SymbolId(5),
            Tasty.Name("Foo"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(classSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                val t: Tasty.Type = Tasty.Type.Named(SymbolId(0))
                Tasty.typeSymbol(t).map: result =>
                    assert(result.isDefined, "symbol must return Present for Named")
                    result match
                        case Maybe.Present(sym: Tasty.Symbol.Class) =>
                            assert(sym.name.asString == "Foo", s"Expected name Foo but got ${sym.name.asString}")
                        case Maybe.Present(other) =>
                            fail(s"Expected Symbol.Class but got ${other.getClass.getSimpleName}")
                        case Maybe.Absent =>
                            fail("should not be Absent")
                    end match
                    succeed
    }

    "symbol returns Absent for non-Named types" in {
        Tasty.withPickles(Chunk.empty):
            val t = Tasty.Type.Function(Chunk.empty, Tasty.Type.Named(SymbolId(5)))
            Tasty.typeSymbol(t).map: result =>
                assert(!result.isDefined, "symbol must return Absent for non-Named type")
            succeed
    }

    "children of Function includes params and result" in {
        val p1 = Tasty.Type.Named(SymbolId(1))
        val p2 = Tasty.Type.Named(SymbolId(2))
        val r  = Tasty.Type.Named(SymbolId(3))
        val t  = Tasty.Type.Function(Chunk(p1, p2), r)
        val ch = t.children
        assert(ch.length == 3, s"Expected 3 children, got ${ch.length}")
        assert(ch(0) == p1)
        assert(ch(1) == p2)
        assert(ch(2) == r)
        succeed
    }

    "children of Applied includes base and args" in {
        val base = Tasty.Type.Named(SymbolId(1))
        val arg  = Tasty.Type.Named(SymbolId(2))
        val t    = Tasty.Type.Applied(base, Chunk(arg))
        val ch   = t.children
        assert(ch.length == 2, s"Expected 2 children, got ${ch.length}")
        assert(ch(0) == base)
        assert(ch(1) == arg)
        succeed
    }

    "foreach visits nodes in pre-order" in {
        val n1      = Tasty.Type.Named(SymbolId(1))
        val n2      = Tasty.Type.Named(SymbolId(2))
        val applied = Tasty.Type.Applied(n1, Chunk(n2))
        val buf     = ArrayBuffer.empty[Tasty.Type]
        applied.foreach(t => buf += t)
        assert(buf.length == 3, s"Expected 3 nodes in pre-order, got ${buf.length}")
        assert(buf(0) == applied, "First node must be self (Applied)")
        assert(buf(1) == n1, "Second node must be base (Named(1))")
        assert(buf(2) == n2, "Third node must be arg (Named(2))")
        succeed
    }

    "show returns non-empty String for every Type case" in {
        val n = Tasty.Type.Named(SymbolId(0))
        val classSym = Tasty.Symbol.Class(
            SymbolId(0),
            Tasty.Name("Foo"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(classSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                val cases: Chunk[Tasty.Type] = Chunk(
                    Tasty.Type.Named(SymbolId(0)),
                    Tasty.Type.TermRef(n, Tasty.Name("x")),
                    Tasty.Type.Applied(n, Chunk(n)),
                    Tasty.Type.TypeLambda(Chunk.empty, n),
                    Tasty.Type.Function(Chunk(n), n),
                    Tasty.Type.Tuple(Chunk(n, n)),
                    Tasty.Type.ByName(n),
                    Tasty.Type.Repeated(n),
                    Tasty.Type.Array(n),
                    Tasty.Type.Refinement(n, Tasty.Name("m"), n),
                    Tasty.Type.Rec(n),
                    Tasty.Type.RecThis(n),
                    Tasty.Type.AndType(n, n),
                    Tasty.Type.OrType(n, n),
                    Tasty.Type.Annotated(n, Tasty.Annotation(n, Chunk.empty)),
                    Tasty.Type.ConstantType(Tasty.Constant.IntConst(0)),
                    Tasty.Type.ThisType(SymbolId(0)),
                    Tasty.Type.SuperType(n, n),
                    Tasty.Type.ParamRef(SymbolId(0), 0),
                    Tasty.Type.Wildcard(n, n),
                    Tasty.Type.Skolem(n),
                    Tasty.Type.MatchType(n, n, Chunk.empty),
                    Tasty.Type.FlexibleType(n),
                    Tasty.Type.ContextFunction(Chunk(n), n)
                )
                Kyo.foreachDiscard(cases): t =>
                    Tasty.typeShow(t).map: s =>
                        assert(s.nonEmpty, s"show returned empty string for ${t.getClass.getSimpleName}")
                .andThen(succeed)
    }

end TypePolishTest
