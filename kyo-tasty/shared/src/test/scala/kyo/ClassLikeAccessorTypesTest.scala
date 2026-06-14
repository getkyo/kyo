package kyo

import kyo.Tasty.SymbolId

/** Compile-time type-shape verification for ClassLike typed accessors.
  *
  * The equivalents are pure `Classpath` instance methods: classpath.declarations(symbol), classpath.typeParams(symbol), etc.
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

    "typed-return-types-compile: five typed bindings on ClassLike all compile" in {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val valSym    = makeVal(id = 2, name = "x")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym)).map { classpath =>
            val decls  = classpath.declarations(withDecls)
            val typePs = classpath.typeParams(withDecls)
            val ms     = decls.filter(_.isInstanceOf[Tasty.Symbol.Method])
            val vs     = decls.filter(_.isInstanceOf[Tasty.Symbol.Val])
            val nt = decls.filter(s =>
                s.isInstanceOf[Tasty.Symbol.Class] || s.isInstanceOf[Tasty.Symbol.Trait] || s.isInstanceOf[Tasty.Symbol.Object]
            )
            val ta = decls.collect { case t: Tasty.Symbol.TypeAlias => t }
            assert(ms.length == 1, s"Expected 1 method but got ${ms.length}")
            assert(vs.length == 1, s"Expected 1 val but got ${vs.length}")
            assert(typePs.isEmpty, "Expected no type params")
            assert(nt.isEmpty, "Expected no nested types")
            assert(ta.isEmpty, "Expected no type aliases")
        }
    }

    "typed-return-preserves-method-specific-access: methods via classpath.declarations filter compiles cleanly" in {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1)).map { classpath =>
            val decls = classpath.declarations(withDecls)
            val ms    = decls.collect { case m: Tasty.Symbol.Method => m }
            assert(ms.length == 1, s"Expected 1 method but got ${ms.length}")
            assert(ms(0).name.asString == "foo", s"Expected method name 'foo'")
        }
    }

    "classpath.declarations rejects bare Symbol at compile time" in {
        val errors = compiletime.testing.typeCheckErrors(
            "(??? : kyo.Tasty.Symbol.Method) : kyo.Tasty.Symbol.ClassLike | kyo.Tasty.Symbol.Package"
        )
        assert(errors.nonEmpty, "Symbol.Method must not be assignable to ClassLike | Package; expected a compile error")
        succeed
    }

    "declarations-returns-untyped-symbol: classpath.declarations(c) returns Chunk[Symbol]" in {
        val classSym  = makeClass(id = 0, name = "Foo")
        val method1   = makeMethod(id = 1, name = "foo")
        val valSym    = makeVal(id = 2, name = "x")
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, valSym)).map { classpath =>
            val ds: Chunk[Tasty.Symbol] = classpath.declarations(withDecls)
            assert(ds.length == 2, s"Expected 2 declarations but got ${ds.length}")
        }
    }

end ClassLikeAccessorTypesTest
