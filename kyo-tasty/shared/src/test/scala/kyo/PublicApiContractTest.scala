package kyo

import kyo.Tasty.SymbolId

/** Cross-cutting public-API contract tests with concrete-equality assertions.
  *
  * Covers: hasAnnotation/findAnnotation, show(Symbol, Code), typeShow, treeShow, and members(pkg, All).
  */
class PublicApiContractTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Minimal classpath: pkg (id=0) root with memberIds = Chunk(id=1), child (id=1) owned by pkg.
    private val pkg = Tasty.Symbol.Package(
        SymbolId(0),
        Tasty.Name("root"),
        Tasty.Flags.empty,
        SymbolId(-1),
        Chunk(SymbolId(1))
    )

    private val child = Tasty.Symbol.Package(
        SymbolId(1),
        Tasty.Name("root.child"),
        Tasty.Flags.empty,
        SymbolId(0),
        Chunk.empty
    )

    private val minimalCp = Tasty.Classpath(
        symbols = Chunk(pkg, child),
        indices = Tasty.Classpath.Indices.empty,
        errors = Chunk.empty,
        modules = Chunk.empty,
        rootSymbolId = SymbolId(0)
    )

    // Annotation fixture: Package "scala" (id=0), Class "deprecated" (id=1), Method "doWork" carrying @deprecated (id=2).
    private def buildAnnotationFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val scalaPkg = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("scala"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
            val deprClass = Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("deprecated"),
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
            val scalaDeprecatedAnnot = Tasty.Annotation(Tasty.Type.Named(SymbolId(1)), Chunk.empty, Tasty.Name("scala.deprecated"))
            val methodDoWork = Tasty.Symbol.Method(
                SymbolId(2),
                Tasty.Name("doWork"),
                Tasty.Flags.empty,
                SymbolId(-1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk(scalaDeprecatedAnnot),
                Maybe.Absent
            )
            Tasty.Classpath.make(
                symbols = Chunk(scalaPkg, deprClass, methodDoWork),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk(SymbolId(0)),
                fullNameIndex = Dict("scala.deprecated" -> SymbolId(1)),
                packageIndex = Dict("scala" -> SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    // Single-symbol classpath: self-owned class "Foo" at id=0.
    private def buildClassFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val fooClass = Tasty.Symbol.Class(
                SymbolId(0),
                Tasty.Name("Foo"),
                Tasty.Flags.empty,
                SymbolId(0), // self-owned
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
            Tasty.Classpath.fromPicklesWithSymbols(Chunk(fooClass))
        }

    "hasAnnotation returns true for method carrying the annotation" in {
        buildAnnotationFixture.map { classpath =>
            val m = classpath.symbol(SymbolId(2)) match
                case Maybe.Present(m: Tasty.Symbol.Method) => m
                case other                                 => fail(s"expected Symbol.Method at id 2, got $other")
            assert(classpath.hasAnnotation(m, "scala.deprecated"))
        }
    }

    "findAnnotation returns Present with correct annotationType" in {
        buildAnnotationFixture.map { classpath =>
            val m = classpath.symbol(SymbolId(2)) match
                case Maybe.Present(m: Tasty.Symbol.Method) => m
                case other                                 => fail(s"expected Symbol.Method at id 2, got $other")
            classpath.findAnnotation(m, "scala.deprecated") match
                case Maybe.Present(a: Tasty.Annotation) =>
                    assert(a.annotationType == Tasty.Type.Named(SymbolId(1)))
                    assert(a.arguments.isEmpty)
                case other =>
                    fail(s"Expected Present(Tasty.Annotation) but got $other")
            end match
        }
    }

    "findAnnotation returns Absent when fullName does not match" in {
        buildAnnotationFixture.map { classpath =>
            val m = classpath.symbol(SymbolId(2)) match
                case Maybe.Present(m: Tasty.Symbol.Method) => m
                case other                                 => fail(s"expected Symbol.Method at id 2, got $other")
            assert(classpath.findAnnotation(m, "scala.inline").isEmpty)
        }
    }

    "show(Class, Code) returns concrete signature string" in {
        buildClassFixture.map { classpath =>
            val fooSym = classpath.symbol(SymbolId(0)) match
                case Maybe.Present(c: Tasty.Symbol.Class) => c
                case other                                => fail(s"expected Symbol.Class at id 0, got $other")
            assert(classpath.show(fooSym, Tasty.ShowFormat.Code) == "class Foo")
        }
    }

    "typeShow(Named) returns simple name string" in {
        buildClassFixture.map { classpath =>
            assert(classpath.typeShow(Tasty.Type.Named(SymbolId(0))) == "Foo")
        }
    }

    "typeShow(Nothing) returns \"Nothing\" and typeShow(Any) returns \"Any\"" in {
        assert(minimalCp.typeShow(Tasty.Type.Nothing) == "Nothing")
        assert(minimalCp.typeShow(Tasty.Type.Any) == "Any")
    }

    "typeShow(Function) returns arrow syntax with parens" in {
        assert(minimalCp.typeShow(Tasty.Type.Function(Chunk(Tasty.Type.Nothing), Tasty.Type.Any)) == "(Nothing) => Any")
    }

    "typeShow(Tuple) returns parenthesised comma-separated elements" in {
        assert(minimalCp.typeShow(Tasty.Type.Tuple(Chunk(Tasty.Type.Nothing, Tasty.Type.Any))) == "(Nothing, Any)")
    }

    "treeShow(Literal(IntConst(42))) returns \"42\"" in {
        assert(minimalCp.treeShow(Tasty.Tree.Literal(Tasty.Constant.IntConst(42))) == "42")
    }

    "treeShow(Literal(StringConst(\"hi\"))) returns quoted string" in {
        assert(minimalCp.treeShow(Tasty.Tree.Literal(Tasty.Constant.StringConst("hi"))) == "\"hi\"")
    }

    "members(pkg, All) equals members(pkg, Declared) for Package" in {
        val decl = minimalCp.members(pkg, Tasty.MemberScope.Declared).map(_.simpleName)
        val inh  = minimalCp.members(pkg, Tasty.MemberScope.Inherited)
        val all  = minimalCp.members(pkg, Tasty.MemberScope.All).map(_.simpleName)
        assert(decl == Chunk("root.child"))
        assert(all == Chunk("root.child"))
        assert(inh == Chunk.empty)
    }

    "kyo.Tasty.SymbolKind does not resolve" in {
        val err = compiletime.testing.typeCheckErrors("val k: kyo.Tasty.SymbolKind = ???")
        assert(err.nonEmpty, "kyo.Tasty.SymbolKind must not resolve; path removed from public API")
        succeed
    }

    "kyo.Tasty.bindingLocal type is exactly Local[Maybe[Binding]]" in {
        // Compile-time type contract: if the declared type changes, the annotation below
        // fails to compile. Runtime assertion is a no-op; the value of this leaf is the
        // type annotation.
        // Visibility contract (private[kyo] restriction) is verified by
        // external.TastyBindingLocalVisibilityTest, which is in package external and
        // uses typeCheckErrors to confirm the field is inaccessible from outside package kyo.
        val _: kyo.Local[kyo.Maybe[kyo.internal.tasty.query.Binding]] = kyo.Tasty.bindingLocal
        succeed
    }

end PublicApiContractTest
