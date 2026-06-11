package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.symbol.SymbolDescriptor
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.symbol.TypedSymbolFactory

/** Verifies that TypedSymbolFactory.from dispatches on SymbolKind and produces the correct typed Symbol subtype. */
class TypedSymbolFactoryTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def makeDesc(
        id: Int,
        kind: SymbolKind,
        flags: Tasty.Flags = Tasty.Flags.empty,
        name: String = "T",
        ownerId: Int = -1
    ): SymbolDescriptor =
        new SymbolDescriptor(
            id = id,
            kind = kind,
            flags = flags,
            name = Tasty.Name(name),
            ownerId = ownerId,
            declaredType = Maybe.Absent,
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            body = Maybe.Absent
        )

    "from(d) with kind=Class returns Symbol.Class with id=1" in {
        val d      = makeDesc(id = 1, kind = SymbolKind.Class, name = "Foo")
        val symbol = TypedSymbolFactory.from(d)
        symbol match
            case c: Tasty.Symbol.Class =>
                assert(c.name.asString == "Foo", s"Expected Class name 'Foo' but got '${c.name.asString}'")
            case other => fail(s"Expected Symbol.Class but got ${other.getClass.getSimpleName}")
        end match
        assert(symbol.id == SymbolId(1), s"Expected id=SymbolId(1) but got ${symbol.id}")
        assert(symbol.kind == SymbolKind.Class, s"Expected kind=Class but got ${symbol.kind}")
    }

    "method paramListIds propagated correctly" in {
        val d = makeDesc(id = 5, kind = SymbolKind.Method, name = "foo")
        d.paramListIds = Chunk(Chunk(10, 11))
        val symbol = TypedSymbolFactory.from(d)
        symbol match
            case m: Tasty.Symbol.Method =>
                assert(m.paramListIds.size == 1, s"Expected 1 param list but got ${m.paramListIds.size}")
                assert(m.paramListIds(0).size == 2, s"Expected 2 params but got ${m.paramListIds(0).size}")
                assert(m.paramListIds(0)(0) == SymbolId(10), s"Expected SymbolId(10) but got ${m.paramListIds(0)(0)}")
                assert(m.paramListIds(0)(1) == SymbolId(11), s"Expected SymbolId(11) but got ${m.paramListIds(0)(1)}")
            case other => fail(s"Expected Symbol.Method but got ${other.getClass.getSimpleName}")
        end match
    }

    "typeparam Covariant flag produces Variance.Covariant" in {
        val coFlags = Tasty.Flags(Tasty.Flag.Covariant)
        val d       = makeDesc(id = 7, kind = SymbolKind.TypeParam, flags = coFlags, name = "A")
        val symbol  = TypedSymbolFactory.from(d)
        symbol match
            case tp: Tasty.Symbol.TypeParam =>
                assert(tp.variance == Tasty.Variance.Covariant, s"Expected Covariant but got ${tp.variance}")
            case other => fail(s"Expected Symbol.TypeParam but got ${other.getClass.getSimpleName}")
        end match
    }

    "package declarationIds become memberIds" in {
        val d = makeDesc(id = 0, kind = SymbolKind.Package, name = "p")
        d.declarationIds = Chunk(1, 2, 3)
        val symbol = TypedSymbolFactory.from(d)
        symbol match
            case pkg: Tasty.Symbol.Package =>
                assert(pkg.memberIds == Chunk(SymbolId(1), SymbolId(2), SymbolId(3)), s"Expected Chunk(1,2,3) but got ${pkg.memberIds}")
            case other => fail(s"Expected Symbol.Package but got ${other.getClass.getSimpleName}")
        end match
    }

    "Symbol.Unresolved id=SymbolId(-1)" in {
        val d      = makeDesc(id = -1, kind = SymbolKind.Package, name = "<unresolved>")
        val symbol = TypedSymbolFactory.from(d)
        symbol match
            case u: Tasty.Symbol.Package =>
                assert(u.id == SymbolId(-1), s"Expected id=SymbolId(-1) but got ${u.id}")
            case other => fail(s"Expected Symbol.Unresolved but got ${other.getClass.getSimpleName}")
        end match
    }

end TypedSymbolFactoryTest
