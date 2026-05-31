package kyo

import kyo.internal.tasty.symbol.SymbolId

/** Phase 04 plan-mandated tests for the pure-data Symbol flag and kind predicates.
  *
  * Leaves:
  *   1. Every flag predicate reflects flags.contains for the corresponding Flag value.
  *   2. Single-kind discriminator equality: isTrait / isClass / isClassLike composites.
  *   3. Composite kind predicates compose correctly (isCaseClass = isClass && isCase).
  *
  * Pins: INV-005 (Symbol predicates are pure boolean reads with no effect row).
  */
class SymbolPredicateTest extends Test:

    import AllowUnsafe.embrace.danger

    // ── Fixture ─────────────────────────────────────────────────────────────

    private def makeSymbol(
        kind: Tasty.SymbolKind = Tasty.SymbolKind.Class,
        flags: Tasty.Flags = Tasty.Flags.empty
    ): Tasty.Symbol =
        Tasty.Symbol.makePlaceholder(kind, flags, Tasty.Name("TestSym")) match
            case u: Tasty.Symbol.Unresolved    => u.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case c: Tasty.Symbol.Class         => c.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case t: Tasty.Symbol.Trait         => t.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case o: Tasty.Symbol.Object        => o.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case m: Tasty.Symbol.Method        => m.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case v: Tasty.Symbol.Val           => v.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case w: Tasty.Symbol.Var           => w.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case f: Tasty.Symbol.Field         => f.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case ta: Tasty.Symbol.TypeAlias    => ta.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case ot: Tasty.Symbol.OpaqueType   => ot.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case at: Tasty.Symbol.AbstractType => at.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case tp: Tasty.Symbol.TypeParam    => tp.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case p: Tasty.Symbol.Parameter     => p.copy(id = SymbolId(1), ownerId = SymbolId(0))
            case pk: Tasty.Symbol.Package      => pk.copy(id = SymbolId(1), ownerId = SymbolId(0))

    private def flagsOf(flags: Tasty.Flag*): Tasty.Flags =
        flags.foldLeft(Tasty.Flags.empty)((acc, f) => new Tasty.Flags(acc.bits | f.bit))

    // ── Leaf 1: every flag predicate reflects flags.contains ─────────────────

    // Given: a Symbol with Flag.Final set.
    // When: isFinal is called.
    // Then: returns true; isAbstract returns false; every predicate matches the underlying bit.
    // Pins: INV-005 (flag predicates are pure boolean reads).
    "Leaf 1: every flag predicate reflects flags.contains" in {
        val finalSym = makeSymbol(flags = flagsOf(Tasty.Flag.Final))
        assert(finalSym.isFinal, "isFinal must be true when Flag.Final is set")
        assert(!finalSym.isAbstract, "isAbstract must be false when Flag.Abstract is not set")

        // Build a symbol with all 41 plan-defined flags and verify each predicate.
        val allFlags = flagsOf(
            Tasty.Flag.Final,
            Tasty.Flag.Abstract,
            Tasty.Flag.Sealed,
            Tasty.Flag.Case,
            Tasty.Flag.Lazy,
            Tasty.Flag.Override,
            Tasty.Flag.Private,
            Tasty.Flag.Protected,
            Tasty.Flag.Public,
            Tasty.Flag.Static,
            Tasty.Flag.Mutable,
            Tasty.Flag.Erased,
            Tasty.Flag.Infix,
            Tasty.Flag.Open,
            Tasty.Flag.Transparent,
            Tasty.Flag.Macro,
            Tasty.Flag.Synthetic,
            Tasty.Flag.Artifact,
            Tasty.Flag.CoVariant,
            Tasty.Flag.ContraVariant,
            Tasty.Flag.Extension,
            Tasty.Flag.Tracked,
            Tasty.Flag.Stable,
            Tasty.Flag.ParamAccessor,
            Tasty.Flag.CaseAccessor,
            Tasty.Flag.FieldAccessor,
            Tasty.Flag.Exported,
            Tasty.Flag.Local,
            Tasty.Flag.HasDefault,
            Tasty.Flag.Invisible,
            Tasty.Flag.Into,
            Tasty.Flag.InlineProxy,
            Tasty.Flag.Tailrec,
            Tasty.Flag.Scala2,
            Tasty.Flag.JavaRecord,
            Tasty.Flag.Enum,
            Tasty.Flag.Module,
            Tasty.Flag.JavaDefined,
            Tasty.Flag.Inline,
            Tasty.Flag.Given,
            Tasty.Flag.Opaque
        )
        val sym = makeSymbol(flags = allFlags)

        assert(sym.isFinal, "isFinal")
        assert(sym.isAbstract, "isAbstract")
        assert(sym.isSealed, "isSealed")
        assert(sym.isCase, "isCase")
        assert(sym.isLazy, "isLazy")
        assert(sym.isOverride, "isOverride")
        assert(sym.isPrivate, "isPrivate")
        assert(sym.isProtected, "isProtected")
        assert(sym.isPublic, "isPublic")
        assert(sym.isStatic, "isStatic")
        assert(sym.isMutable, "isMutable")
        assert(sym.isErased, "isErased")
        assert(sym.isInfix, "isInfix")
        assert(sym.isOpen, "isOpen")
        assert(sym.isTransparent, "isTransparent")
        assert(sym.isMacro, "isMacro")
        assert(sym.isSynthetic, "isSynthetic")
        assert(sym.isArtifact, "isArtifact")
        assert(sym.isCovariant, "isCovariant")
        assert(sym.isContravariant, "isContravariant")
        assert(sym.isExtension, "isExtension")
        assert(sym.isTracked, "isTracked")
        assert(sym.isStable, "isStable")
        assert(sym.isParamAccessor, "isParamAccessor")
        assert(sym.isCaseAccessor, "isCaseAccessor")
        assert(sym.isFieldAccessor, "isFieldAccessor")
        assert(sym.isExported, "isExported")
        assert(sym.isLocal, "isLocal")
        assert(sym.isHasDefault, "isHasDefault")
        assert(sym.isInvisible, "isInvisible")
        assert(sym.isInto, "isInto")
        assert(sym.isInlineProxy, "isInlineProxy")
        assert(sym.isTailrec, "isTailrec")
        assert(sym.isScala2, "isScala2")
        assert(sym.isJavaRecord, "isJavaRecord")
        assert(sym.isEnum, "isEnum")
        assert(sym.isModule, "isModule")
        assert(sym.isJava, "isJava")
        assert(sym.isInline, "isInline")
        assert(sym.isContextual, "isContextual")
        assert(sym.isOpaque, "isOpaque")

        // Verify the empty case: a symbol with no flags set has all predicates false.
        val noFlagsSym = makeSymbol(flags = Tasty.Flags.empty)
        assert(!noFlagsSym.isFinal, "isFinal should be false with no flags")
        assert(!noFlagsSym.isAbstract, "isAbstract should be false with no flags")
        assert(!noFlagsSym.isSealed, "isSealed should be false with no flags")
        assert(!noFlagsSym.isCase, "isCase should be false with no flags")
        assert(!noFlagsSym.isContextual, "isContextual should be false with no flags")
        assert(!noFlagsSym.isOpaque, "isOpaque should be false with no flags")

        succeed
    }

    // ── Leaf 2: single-kind discriminator equality ───────────────────────────

    // Given: a Symbol with kind = SymbolKind.Trait.
    // When: isTrait and isClass are called.
    // Then: isTrait = true, isClass = false, isClassLike = true (composite includes trait).
    // Pins: INV-005.
    "Leaf 2: single-kind discriminator equality" in {
        val traitSym = makeSymbol(kind = Tasty.SymbolKind.Trait)
        assert(traitSym.isTrait, "isTrait must be true for SymbolKind.Trait")
        assert(!traitSym.isClass, "isClass must be false for SymbolKind.Trait")
        assert(traitSym.isClassLike, "isClassLike must be true for SymbolKind.Trait")

        // Verify every single-kind discriminator for its matching kind.
        val cases: Seq[(Tasty.SymbolKind, Tasty.Symbol => Boolean, String)] = Seq(
            (Tasty.SymbolKind.Package, _.isPackage, "isPackage"),
            (Tasty.SymbolKind.Class, _.isClass, "isClass"),
            (Tasty.SymbolKind.Trait, _.isTrait, "isTrait"),
            (Tasty.SymbolKind.Object, _.isObject, "isObject"),
            (Tasty.SymbolKind.Method, _.isMethod, "isMethod"),
            (Tasty.SymbolKind.Field, _.isField, "isField"),
            (Tasty.SymbolKind.Val, _.isVal, "isVal"),
            (Tasty.SymbolKind.Var, _.isVar, "isVar"),
            (Tasty.SymbolKind.TypeAlias, _.isTypeAlias, "isTypeAlias"),
            (Tasty.SymbolKind.OpaqueType, _.isOpaqueTypeKind, "isOpaqueTypeKind"),
            (Tasty.SymbolKind.AbstractType, _.isAbstractType, "isAbstractType"),
            (Tasty.SymbolKind.TypeParam, _.isTypeParam, "isTypeParam"),
            (Tasty.SymbolKind.Parameter, _.isParameter, "isParameter"),
            (Tasty.SymbolKind.Unresolved, _.isUnresolved, "isUnresolved")
        )
        for (expectedKind, predicate, name) <- cases do
            val s = makeSymbol(kind = expectedKind)
            assert(predicate(s), s"$name must be true for kind $expectedKind")
            // Every OTHER kind discriminator must be false.
            val others = cases.filterNot(_._1 == expectedKind)
            for (_, otherPred, otherName) <- others do
                assert(!otherPred(s), s"$otherName must be false when kind is $expectedKind")
        end for

        succeed
    }

    // ── Leaf 3: composite kind predicates compose correctly ──────────────────

    // Given: a Symbol with kind = SymbolKind.Class and Flag.Case set.
    // When: isCaseClass is called.
    // Then: returns true.
    // Pins: INV-005 (composite predicate semantics).
    "Leaf 3: composite kind predicates compose correctly" in {
        val caseSym = makeSymbol(kind = Tasty.SymbolKind.Class, flags = flagsOf(Tasty.Flag.Case))
        assert(caseSym.isCaseClass, "isCaseClass must be true for Class + Case")
        assert(!caseSym.isCaseObject, "isCaseObject must be false for Class + Case (not Object)")

        val caseObjSym = makeSymbol(kind = Tasty.SymbolKind.Object, flags = flagsOf(Tasty.Flag.Case))
        assert(caseObjSym.isCaseObject, "isCaseObject must be true for Object + Case")
        assert(!caseObjSym.isCaseClass, "isCaseClass must be false for Object + Case (not Class)")

        // isClassLike: Class, Trait, Object all qualify.
        val classSym  = makeSymbol(kind = Tasty.SymbolKind.Class)
        val traitSym  = makeSymbol(kind = Tasty.SymbolKind.Trait)
        val objectSym = makeSymbol(kind = Tasty.SymbolKind.Object)
        val methodSym = makeSymbol(kind = Tasty.SymbolKind.Method)
        assert(classSym.isClassLike, "isClassLike: Class")
        assert(traitSym.isClassLike, "isClassLike: Trait")
        assert(objectSym.isClassLike, "isClassLike: Object")
        assert(!methodSym.isClassLike, "isClassLike: Method should be false")

        // isTypeLike: TypeAlias, OpaqueType, AbstractType, TypeParam.
        val typeAliasSym    = makeSymbol(kind = Tasty.SymbolKind.TypeAlias)
        val opaqueTypeSym   = makeSymbol(kind = Tasty.SymbolKind.OpaqueType)
        val abstractTypeSym = makeSymbol(kind = Tasty.SymbolKind.AbstractType)
        val typeParamSym    = makeSymbol(kind = Tasty.SymbolKind.TypeParam)
        val valSym          = makeSymbol(kind = Tasty.SymbolKind.Val)
        assert(typeAliasSym.isTypeLike, "isTypeLike: TypeAlias")
        assert(opaqueTypeSym.isTypeLike, "isTypeLike: OpaqueType")
        assert(abstractTypeSym.isTypeLike, "isTypeLike: AbstractType")
        assert(typeParamSym.isTypeLike, "isTypeLike: TypeParam")
        assert(!valSym.isTypeLike, "isTypeLike: Val should be false")

        // isTerm: Method, Val, Var, Field, Parameter.
        val varSym   = makeSymbol(kind = Tasty.SymbolKind.Var)
        val fieldSym = makeSymbol(kind = Tasty.SymbolKind.Field)
        val paramSym = makeSymbol(kind = Tasty.SymbolKind.Parameter)
        assert(methodSym.isTerm, "isTerm: Method")
        assert(valSym.isTerm, "isTerm: Val")
        assert(varSym.isTerm, "isTerm: Var")
        assert(fieldSym.isTerm, "isTerm: Field")
        assert(paramSym.isTerm, "isTerm: Parameter")
        assert(!classSym.isTerm, "isTerm: Class should be false")

        succeed
    }

end SymbolPredicateTest
