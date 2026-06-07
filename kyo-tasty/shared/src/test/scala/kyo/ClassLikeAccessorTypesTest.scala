package kyo

import kyo.Tasty.SymbolId

/** compile-time type-shape verification for ClassLike typed accessors.
  *
  * After the Symbol methods (methods, vals, typeParams, declarations) are removed.
  * The equivalents are on object Tasty.*: Tasty.declarations(sym), Tasty.typeParams(sym), etc.
  */
class ClassLikeAccessorTypesTest extends kyo.test.Test[Any]:

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
            Chunk.empty
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
            Chunk.empty
        )

    // typed-return-types-compile
    // Given: Symbol.Class c with some declarations
    // When: 5 bindings on c using Tasty.* free functions
    // Then: every binding compiles cleanly
    "typed-return-types-compile: five typed bindings on ClassLike all compile" in {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val valSym    = makeVal(id = 2, name = "x")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                for
                    decls  <- Tasty.declarations(withDecls)
                    typePs <- Tasty.typeParams(withDecls)
                    ms = decls.filter(_.isInstanceOf[Tasty.Symbol.Method])
                    vs = decls.filter(_.isInstanceOf[Tasty.Symbol.Val])
                    nt = decls.filter(s =>
                        s.isInstanceOf[Tasty.Symbol.Class] || s.isInstanceOf[Tasty.Symbol.Trait] || s.isInstanceOf[Tasty.Symbol.Object]
                    )
                    ta = decls.collect { case t: Tasty.Symbol.TypeAlias => t }
                yield
                    assert(ms.length == 1, s"Expected 1 method but got ${ms.length}")
                    assert(vs.length == 1, s"Expected 1 val but got ${vs.length}")
                    assert(typePs.isEmpty, "Expected no type params")
                    assert(nt.isEmpty, "Expected no nested types")
                    assert(ta.isEmpty, "Expected no type aliases")
                    succeed
    }

    // typed-return-preserves-method-specific-access
    // Given: Symbol.Class c with method declaration
    // When: get methods via Tasty.declarations + filter
    // Then: returns the method correctly
    "typed-return-preserves-method-specific-access: methods via Tasty.declarations filter compiles cleanly" in {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.declarations(withDecls).map: decls =>
                    val ms = decls.collect { case m: Tasty.Symbol.Method => m }
                    assert(ms.length == 1, s"Expected 1 method but got ${ms.length}")
                    assert(ms(0).name.asString == "foo", s"Expected method name 'foo'")
                    succeed
    }

    // layered-preservation-base-symbol-caller
    // Given: sym: Tasty.Symbol (upcast of Symbol.Class)
    // When: val decls: Chunk[Symbol] < Sync = Tasty.declarations(sym)
    // Then: compiles
    "layered-preservation-base-symbol-caller: Tasty.declarations(sym) compiles from flat-Symbol caller" in {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1)).flatMap: cp =>
            Tasty.withClasspath(cp):
                val sym: Tasty.Symbol = withDecls
                Tasty.declarations(sym).map: decls =>
                    assert(decls.length == 1, s"Expected 1 declaration from flat-Symbol accessor but got ${decls.length}")
                    succeed
    }

    // declarations-returns-untyped-symbol
    // Given: Symbol.Class c with declarations
    // When: val ds: Chunk[Symbol] < Sync = Tasty.declarations(c)
    // Then: compiles (declarations return type is Chunk[Symbol] < Sync)
    "declarations-returns-untyped-symbol: Tasty.declarations(c) returns Chunk[Symbol]" in {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val valSym    = makeVal(id = 2, name = "x")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                val ds: Chunk[Tasty.Symbol] < Sync = Tasty.declarations(withDecls)
                ds.map: syms =>
                    assert(syms.length == 2, s"Expected 2 declarations but got ${syms.length}")
                    succeed
    }

end ClassLikeAccessorTypesTest
