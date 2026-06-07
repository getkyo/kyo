package kyo

import kyo.Tasty.SymbolId

/** Cross-cutting public-API contract gate (F-017).
  *
  * Each leaf pins a concrete-equality assertion for a public API that the bug audit flagged as weakly tested.
  * Grouped here per Q-007 (RESOLVED): one new file consolidates the aggregation contracts rather than scattering them
  * across the per-finding test files.
  *
  * Leaf coverage:
  *   A: hasAnnotation / findAnnotation positive case (was: only negative / empty-fixture assertions)
  *   B: show(Symbol, Code) concrete equality for a Class symbol (was: only .nonEmpty)
  *   C: typeShow(Named) concrete simple-name equality (was: only .nonEmpty)
  *   D: typeShow(Function / Tuple / Nothing / Any) concrete equality (was: only .nonEmpty)
  *   E: treeShow(Literal(IntConst)) and treeShow(Literal(StringConst)) concrete equality (was: only .nonEmpty)
  *   F: members(pkg, All) cross-check reference leaf (already strengthened in Phases 04/09; one anchor leaf here)
  */
class PublicApiContractTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Shared fixtures ──────────────────────────────────────────────────────

    // Minimal classpath: two Package symbols, no annotations.
    // pkg  (id=0) root with memberIds = Chunk(id=1)
    // child (id=1) owned by pkg
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

    // Annotation fixture: a classpath with a scala.deprecated annotation class and a method carrying it.
    // Layout:
    //   id=0: Package "scala"                    (FQN "scala")
    //   id=1: Class "deprecated" owned by id=0   (FQN "scala.deprecated")
    //   id=2: Method "doWork" carrying @scala.deprecated
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
    // Used for show(Symbol) and typeShow(Named) leaves.
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

    // ── Leaf A1: hasAnnotation positive case ─────────────────────────────────
    // Given: Symbol.Method carrying @scala.deprecated in its annotations.
    // When: hasAnnotation(method, "scala.deprecated").
    // Then: returns true (concrete Boolean equality).
    "Leaf A1: hasAnnotation returns true for method carrying the annotation" in {
        buildAnnotationFixture.flatMap: cp =>
            val m = cp.symbol(SymbolId(2)).asInstanceOf[Tasty.Symbol.Method]
            Tasty.withClasspath(cp):
                Tasty.hasAnnotation(m, "scala.deprecated").map: result =>
                    assert(result == true)
                    succeed
    }

    // ── Leaf A2: findAnnotation positive case ────────────────────────────────
    // Given: same Symbol.Method carrying @scala.deprecated.
    // When: findAnnotation(method, "scala.deprecated").
    // Then: returns Present with annotationType == Type.Named(SymbolId(1)).
    "Leaf A2: findAnnotation returns Present with correct annotationType" in {
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

    // ── Leaf A3: findAnnotation absent case (no annotation) ──────────────────
    // Given: same Symbol.Method carrying @scala.deprecated.
    // When: findAnnotation(method, "scala.inline").
    // Then: returns Absent (isEmpty == true is the concrete assertion; == on union Maybe is not supported).
    "Leaf A3: findAnnotation returns Absent when fqn does not match" in {
        buildAnnotationFixture.flatMap: cp =>
            val m = cp.symbol(SymbolId(2)).asInstanceOf[Tasty.Symbol.Method]
            Tasty.withClasspath(cp):
                Tasty.findAnnotation(m, "scala.inline").map: result =>
                    assert(result.isEmpty, s"Expected Absent but got $result")
                    succeed
    }

    // ── Leaf B: show(Symbol, Code) concrete equality for Class ───────────────
    // Given: self-owned Class "Foo" at id=0.
    // When: show(fooSym, ShowFormat.Code).
    // Then: exactly "class Foo".
    "Leaf B: show(Class, Code) returns concrete signature string" in {
        buildClassFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                val fooSym = cp.symbol(SymbolId(0)).asInstanceOf[Tasty.Symbol.Class]
                Tasty.show(fooSym, Tasty.ShowFormat.Code).map: result =>
                    assert(result == "class Foo")
                    succeed
    }

    // ── Leaf C: typeShow(Named) concrete simple-name equality ────────────────
    // Given: classpath containing Class "Foo" at id=0 (self-owned, so fullName resolves to "Foo").
    //        typeShow uses cp.symbol(id).map(_.name.asString) -- simple name only.
    // When: typeShow(Type.Named(SymbolId(0))).
    // Then: exactly "Foo".
    "Leaf C: typeShow(Named) returns simple name string" in {
        buildClassFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.typeShow(Tasty.Type.Named(SymbolId(0))).map: result =>
                    assert(result == "Foo")
                    succeed
    }

    // ── Leaf D1: typeShow(Nothing) and typeShow(Any) sentinels ───────────────
    // Given: any classpath.
    // When: typeShow(Type.Nothing) and typeShow(Type.Any).
    // Then: "Nothing" and "Any" respectively.
    "Leaf D1: typeShow(Nothing) returns \"Nothing\" and typeShow(Any) returns \"Any\"" in {
        Tasty.withClasspath(minimalCp):
            for
                nothingStr <- Tasty.typeShow(Tasty.Type.Nothing)
                anyStr     <- Tasty.typeShow(Tasty.Type.Any)
            yield
                assert(nothingStr == "Nothing")
                assert(anyStr == "Any")
                succeed
    }

    // ── Leaf D2: typeShow(Function) concrete equality ────────────────────────
    // Given: any classpath.
    // When: typeShow(Type.Function(Chunk(Type.Nothing), Type.Any)).
    // Then: "(Nothing) => Any" (the single-arg case wraps in parens because typeShow uses "(ps.map...) => r").
    "Leaf D2: typeShow(Function) returns arrow syntax with parens" in {
        Tasty.withClasspath(minimalCp):
            Tasty.typeShow(Tasty.Type.Function(Chunk(Tasty.Type.Nothing), Tasty.Type.Any)).map: result =>
                assert(result == "(Nothing) => Any")
                succeed
    }

    // ── Leaf D3: typeShow(Tuple) concrete equality ───────────────────────────
    // Given: any classpath.
    // When: typeShow(Type.Tuple(Chunk(Type.Nothing, Type.Any))).
    // Then: "(Nothing, Any)".
    "Leaf D3: typeShow(Tuple) returns parenthesised comma-separated elements" in {
        Tasty.withClasspath(minimalCp):
            Tasty.typeShow(Tasty.Type.Tuple(Chunk(Tasty.Type.Nothing, Tasty.Type.Any))).map: result =>
                assert(result == "(Nothing, Any)")
                succeed
    }

    // ── Leaf E1: treeShow(Literal(IntConst(42))) concrete equality ───────────
    // Given: any classpath.
    // When: treeShow(Tree.Literal(Constant.IntConst(42))).
    // Then: "42" (delegates to Constant.show, which renders IntConst as its decimal value).
    "Leaf E1: treeShow(Literal(IntConst(42))) returns \"42\"" in {
        Tasty.withClasspath(minimalCp):
            Tasty.treeShow(Tasty.Tree.Literal(Tasty.Constant.IntConst(42))).map: result =>
                assert(result == "42")
                succeed
    }

    // ── Leaf E2: treeShow(Literal(StringConst("hi"))) concrete equality ──────
    // Given: any classpath.
    // When: treeShow(Tree.Literal(Constant.StringConst("hi"))).
    // Then: "\"hi\"" (delegates to Constant.show which adds surrounding double-quotes).
    // Pins: F-008 escape semantics round-trip through treeShow.
    "Leaf E2: treeShow(Literal(StringConst(\"hi\"))) returns quoted string" in {
        Tasty.withClasspath(minimalCp):
            Tasty.treeShow(Tasty.Tree.Literal(Tasty.Constant.StringConst("hi"))).map: result =>
                assert(result == "\"hi\"")
                succeed
    }

    // ── Leaf F: members(pkg, All) cross-check reference ──────────────────────
    // This leaf consolidates the public-API contract already enforced by Inv009BehavioralTest Leaf 5
    // and MembersTest. Having one anchor leaf here confirms the F-004 fix is visible via the
    // public-API-focused test file per Q-007.
    //
    // Given: minimalCp with pkg (id=0) having memberIds = Chunk(id=1, child package "root.child").
    // When: members(pkg, All) and members(pkg, Declared) and members(pkg, Inherited).
    // Then: All == Declared == Chunk containing "root.child"; Inherited == Chunk.empty.
    "Leaf F: members(pkg, All) equals members(pkg, Declared) for Package" in {
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
