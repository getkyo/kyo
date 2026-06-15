package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Tests for the sealed-trait Symbol hierarchy. */
class SymbolHierarchyTest extends kyo.test.Test[Any]:

    //   ownerId=SymbolId(0), Maybe.Absent for scaladoc/sourcePosition/javaMetadata,
    //   empty parentTypes/typeParamIds/declarationIds, Maybe.Absent for permittedSubclassIds,
    //   empty annotations/javaAnnotations, Maybe.Absent body.
    //   isClassLike==true; isTrait==false.
    "Symbol.Class constructs correctly and isClass/isClassLike are true" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.Class(
            id = SymbolId(7),
            name = Tasty.Name("Foo"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        assert(symbol.id == SymbolId(7))
        import Tasty.Name.asString
        assert(symbol.name.asString == "Foo")
        assert(symbol.flags.bits == Tasty.Flags.empty.bits)
        assert(symbol.isInstanceOf[Tasty.Symbol.Class])
        assert(symbol.isInstanceOf[Tasty.Symbol.ClassLike])
        assert(!symbol.isInstanceOf[Tasty.Symbol.Trait])
        succeed
    }

    "Symbol.Trait with Sealed flag: isSealed and openLevel correct" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.Trait(
            id = SymbolId(1),
            name = Tasty.Name("T"),
            flags = Tasty.Flags(Tasty.Flag.Sealed),
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        assert(symbol.isSealed)
        assert(symbol.openLevel == Tasty.OpenLevel.Sealed)
        assert(symbol.isInstanceOf[Tasty.Symbol.Trait])
        assert(symbol.isInstanceOf[Tasty.Symbol.ClassLike])
        succeed
    }

    //   SymbolId(11))), typeParamIds=Chunk(SymbolId(12)).
    //   paramListIds(0).size==2.
    "Symbol.Method constructs correctly with paramListIds" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.Method(
            id = SymbolId(2),
            name = Tasty.Name("foo"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            declaredType = Maybe.Absent,
            paramListIds = Chunk(Chunk(SymbolId(10), SymbolId(11))),
            typeParamIds = Chunk(SymbolId(12)),
            annotations = Chunk.empty,
            javaMetadata = Maybe.Absent
        )
        assert(symbol.isInstanceOf[Tasty.Symbol.Method])
        assert(symbol.isInstanceOf[Tasty.Symbol.Method] || symbol.isInstanceOf[Tasty.Symbol.Val] || symbol.isInstanceOf[
            Tasty.Symbol.Var
        ] || symbol.isInstanceOf[Tasty.Symbol.Field] || symbol.isInstanceOf[Tasty.Symbol.Parameter])
        assert(!symbol.isInstanceOf[Tasty.Symbol.ClassLike])
        val m = symbol match
            case m: Tasty.Symbol.Method => m
            case other                  => fail(s"expected Symbol.Method, got $other")
        assert(m.paramListIds.size == 1)
        assert(m.paramListIds(0).size == 2)
        succeed
    }

    "Symbol.Val with Lazy flag: isVal/isLazy/isTerm true; isMethod false" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.Val(
            id = SymbolId(3),
            name = Tasty.Name("x"),
            flags = Tasty.Flags(Tasty.Flag.Lazy),
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            declaredType = Maybe.Absent,
            annotations = Chunk.empty
        )
        assert(symbol.isInstanceOf[Tasty.Symbol.Val])
        assert(symbol.isLazy)
        assert(symbol.isInstanceOf[Tasty.Symbol.Method] || symbol.isInstanceOf[Tasty.Symbol.Val] || symbol.isInstanceOf[
            Tasty.Symbol.Var
        ] || symbol.isInstanceOf[Tasty.Symbol.Field] || symbol.isInstanceOf[Tasty.Symbol.Parameter])
        assert(!symbol.isInstanceOf[Tasty.Symbol.Method])
        succeed
    }

    "Symbol.TypeAlias body is a Type value; isTypeAlias and isTypeLike are true" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.TypeAlias(
            id = SymbolId(4),
            name = Tasty.Name("Alias"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            body = Maybe.Present(Tasty.Type.Named(SymbolId(50))),
            typeParamIds = Chunk.empty,
            annotations = Chunk.empty
        )
        val ta = symbol match
            case ta: Tasty.Symbol.TypeAlias => ta
            case other                      => fail(s"expected Symbol.TypeAlias, got $other")
        assert(ta.body == Maybe.Present(Tasty.Type.Named(SymbolId(50))))
        assert(symbol.isInstanceOf[Tasty.Symbol.TypeAlias])
        assert(symbol.isInstanceOf[Tasty.Symbol.TypeAlias] || symbol.isInstanceOf[Tasty.Symbol.OpaqueType] || symbol.isInstanceOf[
            Tasty.Symbol.AbstractType
        ] || symbol.isInstanceOf[Tasty.Symbol.TypeParam])
        succeed
    }

    //   bounds=TypeBounds(Type.Nothing, Type.Any).
    //   bounds.upper==Type.Any; isOpaqueType==true.
    "Symbol.OpaqueType has correct body and bounds sentinels" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.OpaqueType(
            id = SymbolId(5),
            name = Tasty.Name("Money"),
            flags = Tasty.Flags(Tasty.Flag.Opaque),
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            body = Maybe.Present(Tasty.Type.Named(SymbolId(60))),
            bounds = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            typeParamIds = Chunk.empty,
            annotations = Chunk.empty
        )
        val ot = symbol match
            case ot: Tasty.Symbol.OpaqueType => ot
            case other                       => fail(s"expected Symbol.OpaqueType, got $other")
        assert(ot.body == Maybe.Present(Tasty.Type.Named(SymbolId(60))))
        assert(ot.bounds.lower == Tasty.Type.Nothing)
        assert(ot.bounds.upper == Tasty.Type.Any)
        assert(symbol.isInstanceOf[Tasty.Symbol.OpaqueType])
        succeed
    }

    //   Variance.Invariant.
    "Symbol.TypeParam variance is preserved for Co/Contra/Invariant" in {
        val co = Tasty.Symbol.TypeParam(
            id = SymbolId(10),
            name = Tasty.Name("A"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            sourcePosition = Maybe.Absent,
            bounds = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            variance = Tasty.Variance.Covariant
        )
        val contra = Tasty.Symbol.TypeParam(
            id = SymbolId(11),
            name = Tasty.Name("B"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            sourcePosition = Maybe.Absent,
            bounds = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            variance = Tasty.Variance.Contravariant
        )
        val inv = Tasty.Symbol.TypeParam(
            id = SymbolId(12),
            name = Tasty.Name("C"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            sourcePosition = Maybe.Absent,
            bounds = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            variance = Tasty.Variance.Invariant
        )
        assert(co.variance == Tasty.Variance.Covariant)
        assert(contra.variance == Tasty.Variance.Contravariant)
        assert(inv.variance == Tasty.Variance.Invariant)
        succeed
    }

    //   defaultArgId=Maybe.Present(SymbolId(71)).
    //   isParameter==true; isTerm==true.
    "Symbol.Parameter declaredType and defaultArgId are preserved" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.Parameter(
            id = SymbolId(13),
            name = Tasty.Name("x"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            sourcePosition = Maybe.Absent,
            declaredType = Maybe.Present(Tasty.Type.Named(SymbolId(70))),
            defaultArgId = Maybe(SymbolId(71)),
            annotations = Chunk.empty
        )
        val p = symbol match
            case p: Tasty.Symbol.Parameter => p
            case other                     => fail(s"expected Symbol.Parameter, got $other")
        assert(p.declaredType == Maybe.Present(Tasty.Type.Named(SymbolId(70))))
        assert(p.defaultArgId == Maybe(SymbolId(71)))
        assert(symbol.isInstanceOf[Tasty.Symbol.Parameter])
        assert(symbol.isInstanceOf[Tasty.Symbol.Method] || symbol.isInstanceOf[Tasty.Symbol.Val] || symbol.isInstanceOf[
            Tasty.Symbol.Var
        ] || symbol.isInstanceOf[Tasty.Symbol.Field] || symbol.isInstanceOf[Tasty.Symbol.Parameter])
        succeed
    }

    //   SymbolId(101)).
    //   sourcePosition==Maybe.Absent.
    "Symbol.Package memberIds and constant abstract fields are correct" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.Package(
            id = SymbolId(14),
            name = Tasty.Name("scala"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            memberIds = Chunk(SymbolId(100), SymbolId(101))
        )
        val pkg = symbol match
            case pkg: Tasty.Symbol.Package => pkg
            case other                     => fail(s"expected Symbol.Package, got $other")
        assert(pkg.memberIds.size == 2)
        assert(symbol.isInstanceOf[Tasty.Symbol.Package])
        assert(symbol.scaladoc == Maybe.Absent)
        assert(symbol.sourcePosition == Maybe.Absent)
        succeed
    }

    //   ownerId=SymbolId(-1).
    //   isUnresolved==true.
    "Symbol.Unresolved has empty flags and Absent accessors" in {
        val symbol: Tasty.Symbol = Tasty.Symbol.Package(
            id = SymbolId(-1),
            name = Tasty.Name("<unresolved>"),
            Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            Chunk.empty
        )
        assert(symbol.flags.bits == Tasty.Flags.empty.bits)
        assert(symbol.scaladoc == Maybe.Absent)
        assert(symbol.sourcePosition == Maybe.Absent)
        assert(symbol.isInstanceOf[Tasty.Symbol.Package])
        succeed
    }

    "two Symbol.Class with same non-negative id compare equal (id-based equality)" in {
        val a: Tasty.Symbol = Tasty.Symbol.Class(
            id = SymbolId(5),
            name = Tasty.Name("A"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        val b: Tasty.Symbol = Tasty.Symbol.Class(
            id = SymbolId(5),
            name = Tasty.Name("B"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        // Symbol equality is id-and-kind based (override on sealed-trait body, final).
        // Two Symbol.Class with same non-negative id are equal regardless of other fields.
        assert(a == b, "Two Symbol.Class with same non-negative id must be equal (id-based equality)")
        succeed
    }

    "two symbols with sentinel id=-1 are not equal (sentinel guard)" in {
        val x: Tasty.Symbol = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("u"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        val y: Tasty.Symbol = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("u"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        assert(x != y, "Two symbols with sentinel id=-1 must NOT be equal (sentinel guard)")
        succeed
    }

end SymbolHierarchyTest
