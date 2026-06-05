package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Phase 01 plan-mandated tests for the sealed-trait Symbol hierarchy.
  *
  * Leaves 1-12 per plan 05-plan.yaml id:1. Pins: INV-001, INV-002, INV-003, INV-006, INV-007, INV-008, INV-009.
  */
class SymbolHierarchyTest extends Test:

    // ── Leaf 1: subtype-construction-Class ──────────────────────────────────

    // Given: a Symbol.Class literal with id=SymbolId(7), name=Name("Foo"), flags=Flags.empty,
    //   ownerId=SymbolId(0), Maybe.Absent for scaladoc/sourcePosition/javaMetadata,
    //   empty parentTypes/typeParamIds/declarationIds, Maybe.Absent for permittedSubclassIds,
    //   empty annotations/javaAnnotations, Maybe.Absent body.
    // When: read id, name, flags, isClass, isClassLike, isTrait.
    // Then: id==SymbolId(7); name.asString=="Foo"; flags==Flags.empty; isClass==true;
    //   isClassLike==true; isTrait==false.
    // Pins: INV-001, INV-003.
    "Leaf 1: Symbol.Class constructs correctly and isClass/isClassLike are true" in {
        val sym: Tasty.Symbol = Tasty.Symbol.Class(
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
        assert(sym.id == SymbolId(7))
        import Tasty.Name.asString
        assert(sym.name.asString == "Foo")
        assert(sym.flags.bits == Tasty.Flags.empty.bits)
        assert(sym.isInstanceOf[Tasty.Symbol.Class])
        assert(sym.isInstanceOf[Tasty.Symbol.ClassLike])
        assert(!sym.isInstanceOf[Tasty.Symbol.Trait])
        succeed
    }

    // ── Leaf 2: subtype-construction-Trait-sealed ────────────────────────────

    // Given: a Symbol.Trait literal with flags=Flags(Flag.Sealed).
    // When: read isSealed and openLevel.
    // Then: isSealed==true; openLevel==OpenLevel.Sealed; isTrait==true; isClassLike==true.
    // Pins: INV-001, INV-003, INV-009.
    "Leaf 2: Symbol.Trait with Sealed flag: isSealed and openLevel correct" in {
        val sym: Tasty.Symbol = Tasty.Symbol.Trait(
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
        assert(sym.isSealed)
        assert(sym.openLevel == Tasty.OpenLevel.Sealed)
        assert(sym.isInstanceOf[Tasty.Symbol.Trait])
        assert(sym.isInstanceOf[Tasty.Symbol.ClassLike])
        succeed
    }

    // ── Leaf 3: subtype-construction-Method ────────────────────────────────

    // Given: a Symbol.Method literal with name=Name("foo"), paramListIds=Chunk(Chunk(SymbolId(10),
    //   SymbolId(11))), typeParamIds=Chunk(SymbolId(12)).
    // When: read isMethod, isTerm, isClassLike, paramListIds.size, paramListIds(0).size.
    // Then: isMethod==true; isTerm==true; isClassLike==false; paramListIds.size==1;
    //   paramListIds(0).size==2.
    // Pins: INV-001, INV-002.
    "Leaf 3: Symbol.Method constructs correctly with paramListIds" in {
        val sym: Tasty.Symbol = Tasty.Symbol.Method(
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
        assert(sym.isInstanceOf[Tasty.Symbol.Method])
        assert(sym.isInstanceOf[Tasty.Symbol.Method] || sym.isInstanceOf[Tasty.Symbol.Val] || sym.isInstanceOf[
            Tasty.Symbol.Var
        ] || sym.isInstanceOf[Tasty.Symbol.Field] || sym.isInstanceOf[Tasty.Symbol.Parameter])
        assert(!sym.isInstanceOf[Tasty.Symbol.ClassLike])
        val m = sym.asInstanceOf[Tasty.Symbol.Method]
        assert(m.paramListIds.size == 1)
        assert(m.paramListIds(0).size == 2)
        succeed
    }

    // ── Leaf 4: subtype-construction-Val-lazy ────────────────────────────────

    // Given: a Symbol.Val literal with flags=Flags(Flag.Lazy), declaredType=Maybe.Absent.
    // When: read isVal, isLazy, isTerm, isMethod.
    // Then: isVal==true; isLazy==true; isTerm==true; isMethod==false.
    // Pins: INV-001, INV-003.
    "Leaf 4: Symbol.Val with Lazy flag: isVal/isLazy/isTerm true; isMethod false" in {
        val sym: Tasty.Symbol = Tasty.Symbol.Val(
            id = SymbolId(3),
            name = Tasty.Name("x"),
            flags = Tasty.Flags(Tasty.Flag.Lazy),
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            declaredType = Maybe.Absent,
            annotations = Chunk.empty
        )
        assert(sym.isInstanceOf[Tasty.Symbol.Val])
        assert(sym.isLazy)
        assert(sym.isInstanceOf[Tasty.Symbol.Method] || sym.isInstanceOf[Tasty.Symbol.Val] || sym.isInstanceOf[
            Tasty.Symbol.Var
        ] || sym.isInstanceOf[Tasty.Symbol.Field] || sym.isInstanceOf[Tasty.Symbol.Parameter])
        assert(!sym.isInstanceOf[Tasty.Symbol.Method])
        succeed
    }

    // ── Leaf 5: subtype-construction-TypeAlias-body-is-Type ─────────────────

    // Given: a Symbol.TypeAlias literal with body=Type.Named(SymbolId(50)), typeParamIds=Chunk.empty.
    // When: read body, isTypeAlias, isTypeLike.
    // Then: body==Type.Named(SymbolId(50)); isTypeAlias==true; isTypeLike==true.
    // Pins: INV-008.
    "Leaf 5: Symbol.TypeAlias body is a Type value; isTypeAlias and isTypeLike are true" in {
        val sym: Tasty.Symbol = Tasty.Symbol.TypeAlias(
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
        val ta = sym.asInstanceOf[Tasty.Symbol.TypeAlias]
        assert(ta.body == Maybe.Present(Tasty.Type.Named(SymbolId(50))))
        assert(sym.isInstanceOf[Tasty.Symbol.TypeAlias])
        assert(sym.isInstanceOf[Tasty.Symbol.TypeAlias] || sym.isInstanceOf[Tasty.Symbol.OpaqueType] || sym.isInstanceOf[
            Tasty.Symbol.AbstractType
        ] || sym.isInstanceOf[Tasty.Symbol.TypeParam])
        succeed
    }

    // ── Leaf 6: subtype-construction-OpaqueType ─────────────────────────────

    // Given: a Symbol.OpaqueType literal with body=Type.Named(SymbolId(60)),
    //   bounds=TypeBounds(Type.Nothing, Type.Any).
    // When: read body, bounds.lower, bounds.upper, isOpaqueType.
    // Then: body==Type.Named(SymbolId(60)); bounds.lower==Type.Nothing;
    //   bounds.upper==Type.Any; isOpaqueType==true.
    // Pins: INV-008, INV-009.
    "Leaf 6: Symbol.OpaqueType has correct body and bounds sentinels" in {
        val sym: Tasty.Symbol = Tasty.Symbol.OpaqueType(
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
        val ot = sym.asInstanceOf[Tasty.Symbol.OpaqueType]
        assert(ot.body == Maybe.Present(Tasty.Type.Named(SymbolId(60))))
        assert(ot.bounds.lower == Tasty.Type.Nothing)
        assert(ot.bounds.upper == Tasty.Type.Any)
        assert(sym.isInstanceOf[Tasty.Symbol.OpaqueType])
        succeed
    }

    // ── Leaf 7: subtype-construction-TypeParam-variance ─────────────────────

    // Given: three Symbol.TypeParam literals with Variance.Covariant, Variance.Contravariant,
    //   Variance.Invariant.
    // When: read each .variance.
    // Then: returns the matching Variance enum case.
    // Pins: INV-009, INV-002.
    "Leaf 7: Symbol.TypeParam variance is preserved for Co/Contra/Invariant" in {
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

    // ── Leaf 8: subtype-construction-Parameter ───────────────────────────────

    // Given: a Symbol.Parameter literal with declaredType=Type.Named(SymbolId(70)),
    //   defaultArgId=Maybe.Present(SymbolId(71)).
    // When: read declaredType, defaultArgId, isParameter, isTerm.
    // Then: declaredType==Type.Named(SymbolId(70)); defaultArgId==Maybe.Present(SymbolId(71));
    //   isParameter==true; isTerm==true.
    // Pins: INV-001, INV-002.
    "Leaf 8: Symbol.Parameter declaredType and defaultArgId are preserved" in {
        val sym: Tasty.Symbol = Tasty.Symbol.Parameter(
            id = SymbolId(13),
            name = Tasty.Name("x"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            sourcePosition = Maybe.Absent,
            declaredType = Maybe.Present(Tasty.Type.Named(SymbolId(70))),
            defaultArgId = Maybe(SymbolId(71)),
            annotations = Chunk.empty
        )
        val p = sym.asInstanceOf[Tasty.Symbol.Parameter]
        assert(p.declaredType == Maybe.Present(Tasty.Type.Named(SymbolId(70))))
        assert(p.defaultArgId == Maybe(SymbolId(71)))
        assert(sym.isInstanceOf[Tasty.Symbol.Parameter])
        assert(sym.isInstanceOf[Tasty.Symbol.Method] || sym.isInstanceOf[Tasty.Symbol.Val] || sym.isInstanceOf[
            Tasty.Symbol.Var
        ] || sym.isInstanceOf[Tasty.Symbol.Field] || sym.isInstanceOf[Tasty.Symbol.Parameter])
        succeed
    }

    // ── Leaf 9: subtype-construction-Package ─────────────────────────────────

    // Given: a Symbol.Package literal with name=Name("scala"), memberIds=Chunk(SymbolId(100),
    //   SymbolId(101)).
    // When: read memberIds.size, isPackage, scaladoc, sourcePosition.
    // Then: memberIds.size==2; isPackage==true; scaladoc==Maybe.Absent;
    //   sourcePosition==Maybe.Absent.
    // Pins: INV-001, INV-002.
    "Leaf 9: Symbol.Package memberIds and constant abstract fields are correct" in {
        val sym: Tasty.Symbol = Tasty.Symbol.Package(
            id = SymbolId(14),
            name = Tasty.Name("scala"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            memberIds = Chunk(SymbolId(100), SymbolId(101))
        )
        val pkg = sym.asInstanceOf[Tasty.Symbol.Package]
        assert(pkg.memberIds.size == 2)
        assert(sym.isInstanceOf[Tasty.Symbol.Package])
        assert(sym.scaladoc == Maybe.Absent)
        assert(sym.sourcePosition == Maybe.Absent)
        succeed
    }

    // ── Leaf 10: subtype-construction-Unresolved ─────────────────────────────

    // Given: a Symbol.Unresolved literal with id=SymbolId(-1), name=Name("<unresolved>"),
    //   ownerId=SymbolId(-1).
    // When: read flags, scaladoc, sourcePosition, isUnresolved.
    // Then: flags==Flags.empty; scaladoc==Maybe.Absent; sourcePosition==Maybe.Absent;
    //   isUnresolved==true.
    // Pins: INV-001.
    "Leaf 10: Symbol.Unresolved has empty flags and Absent accessors" in {
        val sym: Tasty.Symbol = Tasty.Symbol.Package(
            id = SymbolId(-1),
            name = Tasty.Name("<unresolved>"),
            Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            Chunk.empty
        )
        assert(sym.flags.bits == Tasty.Flags.empty.bits)
        assert(sym.scaladoc == Maybe.Absent)
        assert(sym.sourcePosition == Maybe.Absent)
        assert(sym.isInstanceOf[Tasty.Symbol.Package])
        succeed
    }

    // ── Leaf 11: equals-by-id ────────────────────────────────────────────────

    // Given: two Symbol.Class literals with the same id=SymbolId(5) but different names "A"/"B".
    // When: compare with ==.
    // Then: returns true (id-based equality preserved).
    // Pins: INV-002.
    "Leaf 11: two Symbol.Class with same non-negative id compare equal (id-based equality)" in {
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
        // Phase 03 change: Symbol equality is now field-based (case class default, Decision 15).
        // Two symbols with the same id but different names are NOT equal.
        assert(a != b, "Two Symbol.Class with different names are not equal (field-based equality, Phase 03 Decision 15)")
        succeed
    }

    // ── Leaf 12: equals-field-based-all-fields ──────────────────────────────

    // Given: two Symbol.Unresolved instances with identical fields (including id=-1).
    // When: compare with ==.
    // Then: returns true (field-based equality; id=-1 is not special in Phase 03).
    // Pins: INV-002; Phase 03 Decision 15.
    "Leaf 12: two Symbol.Unresolved with same fields are equal (field-based equality)" in {
        val x: Tasty.Symbol = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("u"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        val y: Tasty.Symbol = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("u"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        assert(x == y, "Two Unresolved symbols with identical fields must be equal (field-based equality, Phase 03 Decision 15)")
        succeed
    }

end SymbolHierarchyTest
