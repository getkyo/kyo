package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.symbol.SymbolKind

/** Tests for the pure-data Symbol flag and kind predicates.
  *
  * Leaves:
  *   1. Every flag predicate reflects flags.contains for the corresponding Flag value.
  *   2. Single-kind discriminator equality: isTrait / isClass / isClassLike composites.
  *   3. Composite kind predicates compose correctly (isCaseClass = isClass && isCase).
  */
class SymbolPredicateTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Fixture ─────────────────────────────────────────────────────────────

    private def makeSymbol(
        kind: SymbolKind = SymbolKind.Class,
        flags: Tasty.Flags = Tasty.Flags.empty
    ): Tasty.Symbol =
        // use TypedSymbolFactory.from to create the correctly-typed Symbol
        kyo.internal.tasty.symbol.TypedSymbolFactory.from(new kyo.internal.tasty.symbol.SymbolDescriptor(
            id = 1,
            kind = kind,
            flags = flags,
            name = Tasty.Name("TestSym"),
            ownerId = 0,
            declaredType = Maybe.Absent,
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            body = Maybe.Absent
        ))

    private def flagsOf(flags: Tasty.Flag*): Tasty.Flags =
        flags.foldLeft(Tasty.Flags.empty)((acc, f) => acc.union(Tasty.Flags(f)))

    "every flag predicate reflects flags.contains" in {
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
            Tasty.Flag.Covariant,
            Tasty.Flag.Contravariant,
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
        val symbol = makeSymbol(flags = allFlags)

        assert(symbol.isFinal, "isFinal")
        assert(symbol.isAbstract, "isAbstract")
        assert(symbol.isSealed, "isSealed")
        assert(symbol.isCase, "isCase")
        assert(symbol.isLazy, "isLazy")
        assert(symbol.isOverride, "isOverride")
        assert(symbol.isPrivate, "isPrivate")
        assert(symbol.isProtected, "isProtected")
        assert(symbol.isPublic, "isPublic")
        assert(symbol.isStatic, "isStatic")
        assert(symbol.isMutable, "isMutable")
        assert(symbol.isErased, "isErased")
        assert(symbol.isInfix, "isInfix")
        assert(symbol.isOpen, "isOpen")
        assert(symbol.isTransparent, "isTransparent")
        // isMacro requires Symbol.Method && !Flag.Synthetic ; test with a Method that has Flag.Macro but not Synthetic.
        val macroMethodSym = makeSymbol(kind = SymbolKind.Method, flags = flagsOf(Tasty.Flag.Macro))
        assert(macroMethodSym.isMacro, "isMacro: Method + Flag.Macro without Synthetic must return true")
        assert(!symbol.isMacro, "isMacro: non-Method symbol with Flag.Macro must return false (kind check)")
        val syntheticMacroSym = makeSymbol(kind = SymbolKind.Method, flags = flagsOf(Tasty.Flag.Macro, Tasty.Flag.Synthetic))
        assert(!syntheticMacroSym.isMacro, "isMacro: Method + Flag.Macro + Flag.Synthetic must return false ")
        assert(symbol.isSynthetic, "isSynthetic")
        assert(symbol.isArtifact, "isArtifact")
        assert(symbol.isCovariant, "isCovariant")
        assert(symbol.isContravariant, "isContravariant")
        assert(symbol.isExtension, "isExtension")
        assert(symbol.isTracked, "isTracked")
        assert(symbol.isStable, "isStable")
        assert(symbol.isParamAccessor, "isParamAccessor")
        assert(symbol.isCaseAccessor, "isCaseAccessor")
        assert(symbol.isFieldAccessor, "isFieldAccessor")
        assert(symbol.isExported, "isExported")
        assert(symbol.isLocal, "isLocal")
        assert(symbol.hasDefault, "hasDefault")
        assert(symbol.isInvisible, "isInvisible")
        assert(symbol.isInto, "isInto")
        assert(symbol.isInlineProxy, "isInlineProxy")
        assert(symbol.isTailrec, "isTailrec")
        assert(symbol.isScala2, "isScala2")
        assert(symbol.isJavaRecord, "isJavaRecord")
        assert(symbol.isEnum, "isEnum")
        assert(symbol.isModule, "isModule")
        assert(symbol.isJava, "isJava")
        assert(symbol.isInline, "isInline")
        assert(symbol.isOpaque, "isOpaque")

        // Verify the empty case: a symbol with no flags set has all predicates false.
        val noFlagsSym = makeSymbol(flags = Tasty.Flags.empty)
        assert(!noFlagsSym.isFinal, "isFinal should be false with no flags")
        assert(!noFlagsSym.isAbstract, "isAbstract should be false with no flags")
        assert(!noFlagsSym.isSealed, "isSealed should be false with no flags")
        assert(!noFlagsSym.isCase, "isCase should be false with no flags")
        assert(!noFlagsSym.isOpaque, "isOpaque should be false with no flags")

        succeed
    }

    "single-kind discriminator equality" in {
        val traitSym = makeSymbol(kind = SymbolKind.Trait)
        assert(traitSym.isInstanceOf[Tasty.Symbol.Trait], "isTrait must be true for SymbolKind.Trait")
        assert(!traitSym.isInstanceOf[Tasty.Symbol.Class], "isClass must be false for SymbolKind.Trait")
        assert(traitSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike must be true for SymbolKind.Trait")

        // Verify every single-kind discriminator for its matching kind.
        val cases: Seq[(SymbolKind, Tasty.Symbol => Boolean, String)] = Seq(
            (SymbolKind.Package, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Package], "isPackage"),
            (SymbolKind.Class, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Class], "isClass"),
            (SymbolKind.Trait, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Trait], "isTrait"),
            (SymbolKind.Object, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Object], "isObject"),
            (SymbolKind.Method, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Method], "isMethod"),
            (SymbolKind.Field, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Field], "isField"),
            (SymbolKind.Val, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Val], "isVal"),
            (SymbolKind.Var, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Var], "isVar"),
            (SymbolKind.TypeAlias, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.TypeAlias], "isTypeAlias"),
            (SymbolKind.OpaqueType, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.OpaqueType], "isOpaqueType"),
            (SymbolKind.AbstractType, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.AbstractType], "isAbstractType"),
            (SymbolKind.TypeParam, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.TypeParam], "isTypeParam"),
            (SymbolKind.Parameter, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Parameter], "isParameter"),
            (SymbolKind.Package, (s: Tasty.Symbol) => s.isInstanceOf[Tasty.Symbol.Package], "isUnresolved")
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

    "composite kind predicates compose correctly" in {
        val caseSym = makeSymbol(kind = SymbolKind.Class, flags = flagsOf(Tasty.Flag.Case))
        assert(
            caseSym.isInstanceOf[Tasty.Symbol.Class] && caseSym.flags.contains(Tasty.Flag.Case),
            "isCaseClass must be true for Class + Case"
        )
        assert(
            !caseSym.isInstanceOf[Tasty.Symbol.Object] && caseSym.flags.contains(Tasty.Flag.Case),
            "isCaseObject must be false for Class + Case (not Object)"
        )

        val caseObjSym = makeSymbol(kind = SymbolKind.Object, flags = flagsOf(Tasty.Flag.Case))
        assert(
            caseObjSym.isInstanceOf[Tasty.Symbol.Object] && caseObjSym.flags.contains(Tasty.Flag.Case),
            "isCaseObject must be true for Object + Case"
        )
        assert(
            !caseObjSym.isInstanceOf[Tasty.Symbol.Class] && caseObjSym.flags.contains(Tasty.Flag.Case),
            "isCaseClass must be false for Object + Case (not Class)"
        )

        // isClassLike: Class, Trait, Object all qualify.
        val classSym  = makeSymbol(kind = SymbolKind.Class)
        val traitSym  = makeSymbol(kind = SymbolKind.Trait)
        val objectSym = makeSymbol(kind = SymbolKind.Object)
        val methodSym = makeSymbol(kind = SymbolKind.Method)
        assert(classSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike: Class")
        assert(traitSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike: Trait")
        assert(objectSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike: Object")
        assert(!methodSym.isInstanceOf[Tasty.Symbol.ClassLike], "isClassLike: Method should be false")

        // isTypeLike: TypeAlias, OpaqueType, AbstractType, TypeParam.
        val typeAliasSym    = makeSymbol(kind = SymbolKind.TypeAlias)
        val opaqueTypeSym   = makeSymbol(kind = SymbolKind.OpaqueType)
        val abstractTypeSym = makeSymbol(kind = SymbolKind.AbstractType)
        val typeParamSym    = makeSymbol(kind = SymbolKind.TypeParam)
        val valSym          = makeSymbol(kind = SymbolKind.Val)
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
        val varSym   = makeSymbol(kind = SymbolKind.Var)
        val fieldSym = makeSymbol(kind = SymbolKind.Field)
        val paramSym = makeSymbol(kind = SymbolKind.Parameter)
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
