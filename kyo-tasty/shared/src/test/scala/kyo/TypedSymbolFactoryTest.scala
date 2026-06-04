package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.symbol.SymbolDescriptor
import kyo.internal.tasty.symbol.TypedSymbolFactory

/** Plan-mandated tests for TypedSymbolFactory (leaves 47-51).
  *
  * Verifies that TypedSymbolFactory.from dispatches on SymbolKind and produces the correct typed Symbol subtype.
  */
class TypedSymbolFactoryTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makeDesc(
        id: Int,
        kind: Tasty.SymbolKind,
        flags: Tasty.Flags = Tasty.Flags.empty,
        name: String = "T",
        ownerId: Int = -1
    ): SymbolDescriptor =
        new SymbolDescriptor(
            id = id,
            kind = kind,
            flags = flags,
            name = Tasty.Name.fromString(name),
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

    // Leaf 47: dispatch-on-kind-class
    // Given: SymbolDescriptor d.kind=Class
    // When: TypedSymbolFactory.from(d)
    // Then: Symbol.Class id=1
    // Pins: INV-004
    "dispatch-on-kind-class: from(d) with kind=Class returns Symbol.Class with id=1" in {
        val d   = makeDesc(id = 1, kind = Tasty.SymbolKind.Class, name = "Foo")
        val sym = TypedSymbolFactory.from(d)
        sym match
            case c: Tasty.Symbol.Class =>
                assert(c.name.asString == "Foo", s"Expected Class name 'Foo' but got '${c.name.asString}'")
            case other => fail(s"Expected Symbol.Class but got ${other.getClass.getSimpleName}")
        end match
        assert(sym.id == SymbolId(1), s"Expected id=SymbolId(1) but got ${sym.id}")
        assert(sym.kind == Tasty.SymbolKind.Class, s"Expected kind=Class but got ${sym.kind}")
    }

    // Leaf 48: dispatch-on-kind-method-paramlists
    // Given: SymbolDescriptor d.kind=Method d.paramListIds Chunk x1x2
    // When: from(d) read paramListIds
    // Then: Symbol.Method; paramListIds==Chunk(Chunk(SymbolId(10), SymbolId(11)))
    // Pins: INV-004, INV-002
    "dispatch-on-kind-method-paramlists: paramListIds propagated correctly" in {
        val d = makeDesc(id = 5, kind = Tasty.SymbolKind.Method, name = "foo")
        d.paramListIds = Chunk(Chunk(10, 11))
        val sym = TypedSymbolFactory.from(d)
        sym match
            case m: Tasty.Symbol.Method =>
                assert(m.paramListIds.size == 1, s"Expected 1 param list but got ${m.paramListIds.size}")
                assert(m.paramListIds(0).size == 2, s"Expected 2 params but got ${m.paramListIds(0).size}")
                assert(m.paramListIds(0)(0) == SymbolId(10), s"Expected SymbolId(10) but got ${m.paramListIds(0)(0)}")
                assert(m.paramListIds(0)(1) == SymbolId(11), s"Expected SymbolId(11) but got ${m.paramListIds(0)(1)}")
            case other => fail(s"Expected Symbol.Method but got ${other.getClass.getSimpleName}")
        end match
    }

    // Leaf 49: dispatch-on-kind-typeparam-variance
    // Given: SymbolDescriptor d.kind=TypeParam d.flags=CoVariant
    // When: from(d) read variance
    // Then: Symbol.TypeParam; variance==Covariant
    // Pins: INV-009
    "dispatch-on-kind-typeparam-variance: Covariant flag produces Variance.Covariant" in {
        val coFlags = Tasty.Flags(Tasty.Flag.CoVariant)
        val d       = makeDesc(id = 7, kind = Tasty.SymbolKind.TypeParam, flags = coFlags, name = "A")
        val sym     = TypedSymbolFactory.from(d)
        sym match
            case tp: Tasty.Symbol.TypeParam =>
                assert(tp.variance == Tasty.Variance.Covariant, s"Expected Covariant but got ${tp.variance}")
            case other => fail(s"Expected Symbol.TypeParam but got ${other.getClass.getSimpleName}")
        end match
    }

    // Leaf 50: dispatch-on-kind-package
    // Given: SymbolDescriptor d.kind=Package d.declarationIds Seq(1,2,3)
    // When: from(d) read memberIds
    // Then: Symbol.Package; memberIds==Chunk(SymbolId(1), SymbolId(2), SymbolId(3))
    // Pins: INV-002
    "dispatch-on-kind-package: declarationIds become memberIds" in {
        val d = makeDesc(id = 0, kind = Tasty.SymbolKind.Package, name = "p")
        d.declarationIds = Chunk(1, 2, 3)
        val sym = TypedSymbolFactory.from(d)
        sym match
            case pkg: Tasty.Symbol.Package =>
                assert(pkg.memberIds == Chunk(SymbolId(1), SymbolId(2), SymbolId(3)), s"Expected Chunk(1,2,3) but got ${pkg.memberIds}")
            case other => fail(s"Expected Symbol.Package but got ${other.getClass.getSimpleName}")
        end match
    }

    // Leaf 51: dispatch-on-kind-unresolved-id-minus-one
    // Given: SymbolDescriptor d.kind=Unresolved d.id=-1
    // When: from(d)
    // Then: Symbol.Unresolved id=SymbolId(-1)
    // Pins: INV-004
    "dispatch-on-kind-unresolved-id-minus-one: Symbol.Unresolved id=SymbolId(-1)" in {
        val d   = makeDesc(id = -1, kind = Tasty.SymbolKind.Unresolved, name = "<unresolved>")
        val sym = TypedSymbolFactory.from(d)
        sym match
            case u: Tasty.Symbol.Unresolved =>
                assert(u.id == SymbolId(-1), s"Expected id=SymbolId(-1) but got ${u.id}")
            case other => fail(s"Expected Symbol.Unresolved but got ${other.getClass.getSimpleName}")
        end match
    }

end TypedSymbolFactoryTest
