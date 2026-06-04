package kyo

import kyo.Tasty.SymbolId

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
        flags.foldLeft(Tasty.Flags.empty)((acc, f) => acc.union(Tasty.Flags(f)))

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
        // isMacro requires Symbol.Method && !Flag.Synthetic (F-E-006 fix); test with a Method that has Flag.Macro but not Synthetic.
        val macroMethodSym = makeSymbol(kind = Tasty.SymbolKind.Method, flags = flagsOf(Tasty.Flag.Macro))
        assert(macroMethodSym.isMacro, "isMacro: Method + Flag.Macro without Synthetic must return true")
        assert(!sym.isMacro, "isMacro: non-Method symbol with Flag.Macro must return false (kind check)")
        val syntheticMacroSym = makeSymbol(kind = Tasty.SymbolKind.Method, flags = flagsOf(Tasty.Flag.Macro, Tasty.Flag.Synthetic))
        assert(!syntheticMacroSym.isMacro, "isMacro: Method + Flag.Macro + Flag.Synthetic must return false (F-E-006)")
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
        assert(sym.hasDefault, "hasDefault")
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
        assert(sym.isOpaque, "isOpaque")

        // Verify the empty case: a symbol with no flags set has all predicates false.
        val noFlagsSym = makeSymbol(flags = Tasty.Flags.empty)
        assert(!noFlagsSym.isFinal, "isFinal should be false with no flags")
        assert(!noFlagsSym.isAbstract, "isAbstract should be false with no flags")
        assert(!noFlagsSym.isSealed, "isSealed should be false with no flags")
        assert(!noFlagsSym.isCase, "isCase should be false with no flags")
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
        assert(traitSym.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be true for SymbolKind.Trait")
        assert(!traitSym.isInstanceOf[Tasty.Symbol.Class], "isClass must be false for SymbolKind.Trait")
        assert(traitSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike must be true for SymbolKind.Trait")

        // Verify every single-kind discriminator for its matching kind.
        val cases: Seq[(Tasty.SymbolKind, Tasty.Symbol => Boolean, String)] = Seq(
            (Tasty.SymbolKind.Package, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Package], "isPackage"),
            (Tasty.SymbolKind.Class, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Class], "isClass"),
            (Tasty.SymbolKind.Trait, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Trait], "isTrait"),
            (Tasty.SymbolKind.Object, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Object], "isObject"),
            (Tasty.SymbolKind.Method, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Method], "isMethod"),
            (Tasty.SymbolKind.Field, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Field], "isField"),
            (Tasty.SymbolKind.Val, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Val], "isVal"),
            (Tasty.SymbolKind.Var, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Var], "isVar"),
            (Tasty.SymbolKind.TypeAlias, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.TypeAlias], "isTypeAlias"),
            (Tasty.SymbolKind.OpaqueType, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.OpaqueType], "isOpaqueType"),
            (Tasty.SymbolKind.AbstractType, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.AbstractType], "isAbstractType"),
            (Tasty.SymbolKind.TypeParam, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.TypeParam], "isTypeParam"),
            (Tasty.SymbolKind.Parameter, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Parameter], "isParameter"),
            (Tasty.SymbolKind.Unresolved, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Unresolved], "isUnresolved")
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
        assert(
            caseSym.isInstanceOf[Tasty.Symbol.Class] && caseSym.flags.contains(Tasty.Flag.Case),
            "isCaseClass must be true for Class + Case"
        )
        assert(
            !caseSym.isInstanceOf[Tasty.Symbol.Object] && caseSym.flags.contains(Tasty.Flag.Case),
            "isCaseObject must be false for Class + Case (not Object)"
        )

        val caseObjSym = makeSymbol(kind = Tasty.SymbolKind.Object, flags = flagsOf(Tasty.Flag.Case))
        assert(
            caseObjSym.isInstanceOf[Tasty.Symbol.Object] && caseObjSym.flags.contains(Tasty.Flag.Case),
            "isCaseObject must be true for Object + Case"
        )
        assert(
            !caseObjSym.isInstanceOf[Tasty.Symbol.Class] && caseObjSym.flags.contains(Tasty.Flag.Case),
            "isCaseClass must be false for Object + Case (not Class)"
        )

        // isClassLike: Class, Trait, Object all qualify.
        val classSym  = makeSymbol(kind = Tasty.SymbolKind.Class)
        val traitSym  = makeSymbol(kind = Tasty.SymbolKind.Trait)
        val objectSym = makeSymbol(kind = Tasty.SymbolKind.Object)
        val methodSym = makeSymbol(kind = Tasty.SymbolKind.Method)
        assert(classSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike: Class")
        assert(traitSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike: Trait")
        assert(objectSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike: Object")
        assert(!methodSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike: Method should be false")

        // isTypeLike: TypeAlias, OpaqueType, AbstractType, TypeParam.
        val typeAliasSym    = makeSymbol(kind = Tasty.SymbolKind.TypeAlias)
        val opaqueTypeSym   = makeSymbol(kind = Tasty.SymbolKind.OpaqueType)
        val abstractTypeSym = makeSymbol(kind = Tasty.SymbolKind.AbstractType)
        val typeParamSym    = makeSymbol(kind = Tasty.SymbolKind.TypeParam)
        val valSym          = makeSymbol(kind = Tasty.SymbolKind.Val)
        assert(
            typeAliasSym.isInstanceOf[Tasty.Symbol.TypeAlias] || typeAliasSym.isInstanceOf[
                Tasty.Symbol.OpaqueType
            ] || typeAliasSym.isInstanceOf[Tasty.Symbol.AbstractType] || typeAliasSym.isInstanceOf[Tasty.Symbol.TypeParam],
            "isTypeLike: TypeAlias"
        )
        assert(
            opaqueTypeSym.isInstanceOf[Tasty.Symbol.TypeAlias] || opaqueTypeSym.isInstanceOf[
                Tasty.Symbol.OpaqueType
            ] || opaqueTypeSym.isInstanceOf[Tasty.Symbol.AbstractType] || opaqueTypeSym.isInstanceOf[Tasty.Symbol.TypeParam],
            "isTypeLike: OpaqueType"
        )
        assert(
            abstractTypeSym.isInstanceOf[Tasty.Symbol.TypeAlias] || abstractTypeSym.isInstanceOf[
                Tasty.Symbol.OpaqueType
            ] || abstractTypeSym.isInstanceOf[Tasty.Symbol.AbstractType] || abstractTypeSym.isInstanceOf[Tasty.Symbol.TypeParam],
            "isTypeLike: AbstractType"
        )
        assert(
            typeParamSym.isInstanceOf[Tasty.Symbol.TypeAlias] || typeParamSym.isInstanceOf[
                Tasty.Symbol.OpaqueType
            ] || typeParamSym.isInstanceOf[Tasty.Symbol.AbstractType] || typeParamSym.isInstanceOf[Tasty.Symbol.TypeParam],
            "isTypeLike: TypeParam"
        )
        assert(
            !valSym.isInstanceOf[Tasty.Symbol.TypeAlias] || valSym.isInstanceOf[Tasty.Symbol.OpaqueType] || valSym.isInstanceOf[
                Tasty.Symbol.AbstractType
            ] || valSym.isInstanceOf[Tasty.Symbol.TypeParam],
            "isTypeLike: Val should be false"
        )

        // isTerm: Method, Val, Var, Field, Parameter.
        val varSym   = makeSymbol(kind = Tasty.SymbolKind.Var)
        val fieldSym = makeSymbol(kind = Tasty.SymbolKind.Field)
        val paramSym = makeSymbol(kind = Tasty.SymbolKind.Parameter)
        assert(
            methodSym.isInstanceOf[Tasty.Symbol.Method] || methodSym.isInstanceOf[Tasty.Symbol.Val] || methodSym.isInstanceOf[
                Tasty.Symbol.Var
            ] || methodSym.isInstanceOf[Tasty.Symbol.Field] || methodSym.isInstanceOf[Tasty.Symbol.Parameter],
            "isTerm: Method"
        )
        assert(
            valSym.isInstanceOf[Tasty.Symbol.Method] || valSym.isInstanceOf[Tasty.Symbol.Val] || valSym.isInstanceOf[
                Tasty.Symbol.Var
            ] || valSym.isInstanceOf[Tasty.Symbol.Field] || valSym.isInstanceOf[Tasty.Symbol.Parameter],
            "isTerm: Val"
        )
        assert(
            varSym.isInstanceOf[Tasty.Symbol.Method] || varSym.isInstanceOf[Tasty.Symbol.Val] || varSym.isInstanceOf[
                Tasty.Symbol.Var
            ] || varSym.isInstanceOf[Tasty.Symbol.Field] || varSym.isInstanceOf[Tasty.Symbol.Parameter],
            "isTerm: Var"
        )
        assert(
            fieldSym.isInstanceOf[Tasty.Symbol.Method] || fieldSym.isInstanceOf[Tasty.Symbol.Val] || fieldSym.isInstanceOf[
                Tasty.Symbol.Var
            ] || fieldSym.isInstanceOf[Tasty.Symbol.Field] || fieldSym.isInstanceOf[Tasty.Symbol.Parameter],
            "isTerm: Field"
        )
        assert(
            paramSym.isInstanceOf[Tasty.Symbol.Method] || paramSym.isInstanceOf[Tasty.Symbol.Val] || paramSym.isInstanceOf[
                Tasty.Symbol.Var
            ] || paramSym.isInstanceOf[Tasty.Symbol.Field] || paramSym.isInstanceOf[Tasty.Symbol.Parameter],
            "isTerm: Parameter"
        )
        assert(
            !classSym.isInstanceOf[Tasty.Symbol.Method] || classSym.isInstanceOf[Tasty.Symbol.Val] || classSym.isInstanceOf[
                Tasty.Symbol.Var
            ] || classSym.isInstanceOf[Tasty.Symbol.Field] || classSym.isInstanceOf[Tasty.Symbol.Parameter],
            "isTerm: Class should be false"
        )

        succeed
    }

end SymbolPredicateTest
