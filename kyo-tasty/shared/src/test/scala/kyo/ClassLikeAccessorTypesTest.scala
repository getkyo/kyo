package kyo

import kyo.internal.tasty.symbol.SymbolId

/** Plan-mandated tests for Phase 03 (leaves 66-68): compile-time type-shape verification for ClassLike typed accessors.
  *
  * Leaf 66: five typed bindings all compile with the narrowed return type. Leaf 67: the corrected positive assertion -- val ms:
  * Chunk[Method] = c.methods compiles cleanly (Chunk covariance means Chunk[Method] <: Chunk[Symbol]; assigning to Chunk[Method] directly
  * works because that IS the declared return type). Leaf 67b: layered preservation -- a flat Symbol caller still gets Chunk[Symbol] from
  * the base-Symbol accessor. Leaf 68: val ds: Chunk[Symbol] = c.declarations compiles (declarations returns Chunk[Symbol]).
  *
  * Pins: INV-005.
  */
class ClassLikeAccessorTypesTest extends Test:

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString

    private def makeClass(id: Int, name: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
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
            Chunk.empty,
            Maybe.Absent
        )

    private def makeMethod(id: Int, name: String): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Maybe.Absent
        )

    private def makeVal(id: Int, name: String): Tasty.Symbol.Val =
        Tasty.Symbol.Val(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Maybe.Absent
        )

    // Leaf 66: typed-return-types-compile
    // Given: Symbol.Class c with some declarations
    // When: 5 typed bindings on c (methods, vals, typeParams, nestedTypes, typeAliases)
    // Then: every binding compiles cleanly
    // Pins: INV-005
    "typed-return-types-compile: five typed bindings on ClassLike all compile" in run {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val valSym    = makeVal(id = 2, name = "x")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym)).map: cp =>
            given Tasty.Classpath                 = cp
            val ms: Chunk[Tasty.Symbol.Method]    = withDecls.methods
            val vs: Chunk[Tasty.Symbol.Val]       = withDecls.vals
            val ps: Chunk[Tasty.Symbol.TypeParam] = withDecls.typeParams
            val nt: Chunk[Tasty.Symbol.ClassLike] = withDecls.nestedTypes
            val ta: Chunk[Tasty.Symbol.TypeAlias] = withDecls.typeAliases
            // all bindings compiled; verify shapes
            assert(ms.length == 1, s"Expected 1 method but got ${ms.length}")
            assert(vs.length == 1, s"Expected 1 val but got ${vs.length}")
            assert(ps.isEmpty, "Expected no type params")
            assert(nt.isEmpty, "Expected no nested types")
            assert(ta.isEmpty, "Expected no type aliases")
            succeed
    }

    // Leaf 67: typed-return-preserves-method-specific-access
    // Plan bug corrected: the plan claimed val ms: Chunk[Symbol] = c.methods would be a type error.
    // Chunk[+A] is covariant, so Chunk[Method] <: Chunk[Symbol] and that assignment COMPILES.
    // The corrected positive assertion: val ms: Chunk[Method] = c.methods compiles (the return type IS Chunk[Method]).
    // Pins: INV-005
    "typed-return-preserves-method-specific-access: val ms: Chunk[Method] = c.methods compiles cleanly" in run {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1)).map: cp =>
            given Tasty.Classpath = cp
            // This binding uses the exact return type of ClassLike.methods; must compile without cast.
            val ms: Chunk[Tasty.Symbol.Method] = withDecls.methods
            assert(ms.length == 1, s"Expected 1 method but got ${ms.length}")
            assert(ms(0).name.asString == "foo", s"Expected method name 'foo'")
            succeed
    }

    // Leaf 67b: layered-preservation-base-symbol-caller
    // Given: sym: Tasty.Symbol (upcast of Symbol.Class)
    // When: val ms: Chunk[Symbol] = sym.methods
    // Then: compiles via base-Symbol accessor returning Chunk[Symbol] (Chunk covariance + base accessor preserved)
    // Pins: INV-005 layered/no-restrict rule from steering.md
    "layered-preservation-base-symbol-caller: Chunk[Symbol] = (sym: Symbol).methods compiles via base accessor" in run {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1)).map: cp =>
            given Tasty.Classpath = cp
            // Upcast to flat Symbol; base-Symbol.methods still returns Chunk[Symbol]
            val sym: Tasty.Symbol       = withDecls
            val ms: Chunk[Tasty.Symbol] = sym.methods
            assert(ms.length == 1, s"Expected 1 method from flat-Symbol accessor but got ${ms.length}")
            succeed
    }

    // Leaf 68: declarations-returns-untyped-symbol
    // Given: Symbol.Class c with declarations
    // When: val ds: Chunk[Symbol] = c.declarations
    // Then: compiles (declarations return type is Chunk[Symbol])
    // Pins: INV-005
    "declarations-returns-untyped-symbol: val ds: Chunk[Symbol] = c.declarations compiles" in run {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val valSym    = makeVal(id = 2, name = "x")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym)).map: cp =>
            given Tasty.Classpath       = cp
            val ds: Chunk[Tasty.Symbol] = withDecls.declarations
            assert(ds.length == 2, s"Expected 2 declarations but got ${ds.length}")
            succeed
    }

end ClassLikeAccessorTypesTest
