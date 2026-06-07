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
        Sync.defer:
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
            val scalaDeprecatedAnnot = Tasty.Annotation(Tasty.Type.Named(SymbolId(1)), Chunk.empty)
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
                fqnIndex = Dict("scala.deprecated" -> SymbolId(1)),
                packageIndex = Dict("scala" -> SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    // Single-symbol classpath: self-owned class "Foo" at id=0.
    private def buildClassFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
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

    "hasAnnotation returns true for method carrying the annotation" in {
        buildAnnotationFixture.flatMap: cp =>
            val m = cp.symbol(SymbolId(2)).asInstanceOf[Tasty.Symbol.Method]
            Tasty.withClasspath(cp):
                Tasty.hasAnnotation(m, "scala.deprecated").map: result =>
                    assert(result == true)
                    succeed
    }

    "findAnnotation returns Present with correct annotationType" in {
        buildAnnotationFixture.flatMap: cp =>
            val m = cp.symbol(SymbolId(2)).asInstanceOf[Tasty.Symbol.Method]
            Tasty.withClasspath(cp):
                Tasty.findAnnotation(m, "scala.deprecated").map:
                    case Maybe.Present(a: Tasty.Annotation) =>
                        assert(a.annotationType == Tasty.Type.Named(SymbolId(1)))
                        assert(a.arguments.isEmpty)
                        succeed
                    case other =>
                        fail(s"Expected Present(Tasty.Annotation) but got $other")
    }

    "findAnnotation returns Absent when fqn does not match" in {
        buildAnnotationFixture.flatMap: cp =>
            val m = cp.symbol(SymbolId(2)).asInstanceOf[Tasty.Symbol.Method]
            Tasty.withClasspath(cp):
                Tasty.findAnnotation(m, "scala.inline").map: result =>
                    assert(result.isEmpty, s"Expected Absent but got $result")
                    succeed
    }

    "show(Class, Code) returns concrete signature string" in {
        buildClassFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                val fooSym = cp.symbol(SymbolId(0)).asInstanceOf[Tasty.Symbol.Class]
                Tasty.show(fooSym, Tasty.ShowFormat.Code).map: result =>
                    assert(result == "class Foo")
                    succeed
    }

    "typeShow(Named) returns simple name string" in {
        buildClassFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.typeShow(Tasty.Type.Named(SymbolId(0))).map: result =>
                    assert(result == "Foo")
                    succeed
    }

    "typeShow(Nothing) returns \"Nothing\" and typeShow(Any) returns \"Any\"" in {
        Tasty.withClasspath(minimalCp):
            for
                nothingStr <- Tasty.typeShow(Tasty.Type.Nothing)
                anyStr     <- Tasty.typeShow(Tasty.Type.Any)
            yield
                assert(nothingStr == "Nothing")
                assert(anyStr == "Any")
                succeed
    }

    "typeShow(Function) returns arrow syntax with parens" in {
        Tasty.withClasspath(minimalCp):
            Tasty.typeShow(Tasty.Type.Function(Chunk(Tasty.Type.Nothing), Tasty.Type.Any)).map: result =>
                assert(result == "(Nothing) => Any")
                succeed
    }

    "typeShow(Tuple) returns parenthesised comma-separated elements" in {
        Tasty.withClasspath(minimalCp):
            Tasty.typeShow(Tasty.Type.Tuple(Chunk(Tasty.Type.Nothing, Tasty.Type.Any))).map: result =>
                assert(result == "(Nothing, Any)")
                succeed
    }

    "treeShow(Literal(IntConst(42))) returns \"42\"" in {
        Tasty.withClasspath(minimalCp):
            Tasty.treeShow(Tasty.Tree.Literal(Tasty.Constant.IntConst(42))).map: result =>
                assert(result == "42")
                succeed
    }

    "treeShow(Literal(StringConst(\"hi\"))) returns quoted string" in {
        Tasty.withClasspath(minimalCp):
            Tasty.treeShow(Tasty.Tree.Literal(Tasty.Constant.StringConst("hi"))).map: result =>
                assert(result == "\"hi\"")
                succeed
    }

    "members(pkg, All) equals members(pkg, Declared) for Package" in {
        Tasty.withClasspath(minimalCp):
            for
                decl <- Tasty.members(pkg, Tasty.MemberScope.Declared).map(_.map(_.simpleName))
                inh  <- Tasty.members(pkg, Tasty.MemberScope.Inherited)
                all  <- Tasty.members(pkg, Tasty.MemberScope.All).map(_.map(_.simpleName))
            yield
                assert(decl == Chunk("root.child"))
                assert(all == Chunk("root.child"))
                assert(inh == Chunk.empty)
                succeed
    }

end PublicApiContractTest
