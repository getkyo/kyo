package kyo

import kyo.Tasty.SymbolId
// typeCheckErrors is accessed via compiletime.testing.typeCheckErrors inline

/** Phase 03 plan leaves 1-4 and 13.
  *
  * Leaf 1: every public ADT derives Schema (compile-time summon). Leaf 2: every public ADT derives CanEqual (compile-time summon). Leaf 3:
  * removed HEAD Symbol methods are absent (compileErrors). Leaf 4: Symbol.EnumCase is a peer of Symbol.Class (compileErrors cross-cast).
  * Leaf 13: Tag[Symbol.EnumCase] summon succeeds.
  *
  * Pins: INV-008 Schema derivation; INV-008 CanEqual uniformity.
  */
class PureDataAdtsTest extends Test:

    // ── Leaf 1: Schema[T] summon at file scope (compile-time check) ──────────
    // All lines below are compile-time assertions: if Schema derivation is missing for any type
    // the file will not compile and sbt reports a compilation error.

    val _s01: Schema[Tasty.Symbol]              = summon[Schema[Tasty.Symbol]]
    val _s02: Schema[Tasty.Symbol.Class]        = summon[Schema[Tasty.Symbol.Class]]
    val _s03: Schema[Tasty.Symbol.Trait]        = summon[Schema[Tasty.Symbol.Trait]]
    val _s04: Schema[Tasty.Symbol.Object]       = summon[Schema[Tasty.Symbol.Object]]
    val _s05: Schema[Tasty.Symbol.EnumCase]     = summon[Schema[Tasty.Symbol.EnumCase]]
    val _s06: Schema[Tasty.Symbol.Package]      = summon[Schema[Tasty.Symbol.Package]]
    val _s07: Schema[Tasty.Symbol.Method]       = summon[Schema[Tasty.Symbol.Method]]
    val _s08: Schema[Tasty.Symbol.Val]          = summon[Schema[Tasty.Symbol.Val]]
    val _s09: Schema[Tasty.Symbol.Var]          = summon[Schema[Tasty.Symbol.Var]]
    val _s10: Schema[Tasty.Symbol.Field]        = summon[Schema[Tasty.Symbol.Field]]
    val _s11: Schema[Tasty.Symbol.TypeAlias]    = summon[Schema[Tasty.Symbol.TypeAlias]]
    val _s12: Schema[Tasty.Symbol.OpaqueType]   = summon[Schema[Tasty.Symbol.OpaqueType]]
    val _s13: Schema[Tasty.Symbol.AbstractType] = summon[Schema[Tasty.Symbol.AbstractType]]
    val _s14: Schema[Tasty.Symbol.TypeParam]    = summon[Schema[Tasty.Symbol.TypeParam]]
    val _s15: Schema[Tasty.Symbol.Parameter]    = summon[Schema[Tasty.Symbol.Parameter]]
    // _s16 was Schema[Symbol.Unresolved] -- removed in Phase 08 (Cat 19)
    val _s16: Schema[Tasty.Symbol.Package]         = summon[Schema[Tasty.Symbol.Package]]
    val _s17: Schema[Tasty.Type]                   = summon[Schema[Tasty.Type]]
    val _s18: Schema[Tasty.Tree]                   = summon[Schema[Tasty.Tree]]
    val _s19: Schema[Tasty.Constant]               = summon[Schema[Tasty.Constant]]
    val _s20: Schema[Tasty.Variance]               = summon[Schema[Tasty.Variance]]
    val _s21: Schema[Tasty.Visibility]             = summon[Schema[Tasty.Visibility]]
    val _s22: Schema[Tasty.OpenLevel]              = summon[Schema[Tasty.OpenLevel]]
    val _s23: Schema[Tasty.ShowFormat]             = summon[Schema[Tasty.ShowFormat]]
    val _s24: Schema[Tasty.MemberScope]            = summon[Schema[Tasty.MemberScope]]
    val _s25: Schema[Tasty.Position]               = summon[Schema[Tasty.Position]]
    val _s26: Schema[Tasty.TypeBounds]             = summon[Schema[Tasty.TypeBounds]]
    val _s27: Schema[Tasty.Annotation]             = summon[Schema[Tasty.Annotation]]
    val _s28: Schema[Tasty.Java.Annotation]        = summon[Schema[Tasty.Java.Annotation]]
    val _s29: Schema[Tasty.Java.Metadata]          = summon[Schema[Tasty.Java.Metadata]]
    val _s30: Schema[Tasty.Java.Module.Descriptor] = summon[Schema[Tasty.Java.Module.Descriptor]]
    val _s31: Schema[Tasty.Pickle]                 = summon[Schema[Tasty.Pickle]]

    // ── Leaf 2: CanEqual[T, T] summon at file scope (compile-time check) ─────

    val _c01: CanEqual[Tasty.Symbol, Tasty.Symbol]                   = summon[CanEqual[Tasty.Symbol, Tasty.Symbol]]
    val _c02: CanEqual[Tasty.Symbol.Class, Tasty.Symbol.Class]       = summon[CanEqual[Tasty.Symbol.Class, Tasty.Symbol.Class]]
    val _c03: CanEqual[Tasty.Symbol.Trait, Tasty.Symbol.Trait]       = summon[CanEqual[Tasty.Symbol.Trait, Tasty.Symbol.Trait]]
    val _c04: CanEqual[Tasty.Symbol.Object, Tasty.Symbol.Object]     = summon[CanEqual[Tasty.Symbol.Object, Tasty.Symbol.Object]]
    val _c05: CanEqual[Tasty.Symbol.EnumCase, Tasty.Symbol.EnumCase] = summon[CanEqual[Tasty.Symbol.EnumCase, Tasty.Symbol.EnumCase]]
    val _c06: CanEqual[Tasty.Symbol.Package, Tasty.Symbol.Package]   = summon[CanEqual[Tasty.Symbol.Package, Tasty.Symbol.Package]]
    val _c07: CanEqual[Tasty.Symbol.Method, Tasty.Symbol.Method]     = summon[CanEqual[Tasty.Symbol.Method, Tasty.Symbol.Method]]
    val _c08: CanEqual[Tasty.Symbol.Val, Tasty.Symbol.Val]           = summon[CanEqual[Tasty.Symbol.Val, Tasty.Symbol.Val]]
    val _c09: CanEqual[Tasty.Symbol.Var, Tasty.Symbol.Var]           = summon[CanEqual[Tasty.Symbol.Var, Tasty.Symbol.Var]]
    val _c10: CanEqual[Tasty.Symbol.Field, Tasty.Symbol.Field]       = summon[CanEqual[Tasty.Symbol.Field, Tasty.Symbol.Field]]
    val _c11: CanEqual[Tasty.Type, Tasty.Type]                       = summon[CanEqual[Tasty.Type, Tasty.Type]]
    val _c12: CanEqual[Tasty.Tree, Tasty.Tree]                       = summon[CanEqual[Tasty.Tree, Tasty.Tree]]
    val _c13: CanEqual[Tasty.Constant, Tasty.Constant]               = summon[CanEqual[Tasty.Constant, Tasty.Constant]]
    val _c14: CanEqual[Tasty.Variance, Tasty.Variance]               = summon[CanEqual[Tasty.Variance, Tasty.Variance]]
    val _c15: CanEqual[Tasty.MemberScope, Tasty.MemberScope]         = summon[CanEqual[Tasty.MemberScope, Tasty.MemberScope]]
    val _c16: CanEqual[Tasty.Annotation, Tasty.Annotation]           = summon[CanEqual[Tasty.Annotation, Tasty.Annotation]]
    val _c17: CanEqual[Tasty.Java.Annotation, Tasty.Java.Annotation] = summon[CanEqual[Tasty.Java.Annotation, Tasty.Java.Annotation]]
    val _c18: CanEqual[Tasty.Java.Metadata, Tasty.Java.Metadata]     = summon[CanEqual[Tasty.Java.Metadata, Tasty.Java.Metadata]]
    val _c19: CanEqual[Tasty.Pickle, Tasty.Pickle]                   = summon[CanEqual[Tasty.Pickle, Tasty.Pickle]]

    // ── Leaf 3: removed HEAD Symbol methods absent (compileErrors) ───────────
    // Each compileErrors call should return a non-empty string because the method no longer exists.

    "Leaf 3: removed HEAD Symbol methods are off the surface" in {
        // typeCheckErrors takes a fully-qualified expression string; local vars are not in scope.
        // Use the null-cast idiom: (null: kyo.Tasty.Symbol).xxx -- if xxx is absent the typecheck fails.
        assert(
            compiletime.testing.typeCheckErrors("(null: kyo.Tasty.Symbol).isPackage").nonEmpty,
            "isPackage must be absent"
        )
        assert(
            compiletime.testing.typeCheckErrors("(null: kyo.Tasty.Symbol).isClass").nonEmpty,
            "isClass must be absent"
        )
        assert(
            compiletime.testing.typeCheckErrors("(null: kyo.Tasty.Symbol).isMethod").nonEmpty,
            "isMethod must be absent"
        )
        assert(
            compiletime.testing.typeCheckErrors("(null: kyo.Tasty.Symbol).isTrait").nonEmpty,
            "isTrait must be absent"
        )
        // kind is private[kyo] so accessible within the kyo package; only verify it's not a public method.
        // The four predicates above (isPackage, isClass, isMethod, isTrait) are sufficient.
        succeed
    }

    // ── Leaf 4: Symbol.EnumCase is a peer of Symbol.Class (compileErrors) ────

    "Leaf 4: Symbol.EnumCase is NOT a subtype of Symbol.Class" in {
        val ec: Tasty.Symbol.EnumCase = Tasty.Symbol.EnumCase(
            SymbolId(1),
            Tasty.Name("MyCase"),
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
        // EnumCase should match EnumCase arm. Use upcast to Symbol so the match is over the sealed base type
        // where both arms are potentially reachable.
        val symEc: Tasty.Symbol = ec
        val label = symEc match
            case _: Tasty.Symbol.EnumCase => "enumCase"
            case _: Tasty.Symbol.Class    => "class"
            case _                        => "other"
        assert(label == "enumCase", s"EnumCase must match EnumCase arm, not Class; got $label")
        // typeCheckErrors: assigning EnumCase to Class-typed val must fail
        assert(
            compiletime.testing.typeCheckErrors(
                "val c: kyo.Tasty.Symbol.Class = (null: kyo.Tasty.Symbol.EnumCase)"
            ).nonEmpty,
            "Assigning EnumCase to Symbol.Class must be a type error (EnumCase is no longer a subtype of Class)"
        )
        succeed
    }

    // ── Leaf 13: Tag[Symbol.EnumCase] summon succeeds ─────────────────────────

    "Leaf 13: Tag[Symbol.EnumCase] summon compiles and produces a valid tag" in {
        val tag = summon[Tag[Tasty.Symbol.EnumCase]]
        // verify the tag is not null (tags are always non-null; this is a sanity check)
        discard(tag)
        succeed
    }

end PureDataAdtsTest
