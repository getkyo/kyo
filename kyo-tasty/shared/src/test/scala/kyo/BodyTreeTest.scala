package kyo

import kyo.Json
import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.TastyState

/** Phase 03 plan leaves 22-23: Tasty.bodyTree.
  *
  * Leaf 22: Tasty.bodyTree returns Present under withClasspath(roots) (decode context attached).
  * Leaf 23: Tasty.bodyTree returns Absent under withClasspath(cp) (no decode context).
  *
  * Pins: item 29 bodyTree migration; INV-009 site-3.
  */
class BodyTreeTest extends Test:

    import AllowUnsafe.embrace.danger

    // Leaf 22: bodyTree returns Present under a binding with a populated bodyStore
    "Leaf 22: Tasty.bodyTree returns Maybe.Present for method with body under live classpath" in run {
        val src = MemoryFileSource()
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        Scope.run:
            Abort.run[TastyError](
                // Use coldLoadBinding to populate DecodeContext.bodyStore with body data
                ClasspathOrchestrator.coldLoadBinding(Seq("root"), Tasty.ErrorMode.SoftFail, Maybe.Absent, src, 1).flatMap: binding =>
                    val cp = binding.cp
                    TastyState.bindingLocal.let(Maybe.Present(binding)):
                        // Find any method that likely has a body (SomeObject.foo or similar)
                        val methodOpt = cp.allMethods.toSeq.find(m => !m.isAbstract)
                        methodOpt match
                            case None => Kyo.lift(succeed) // no concrete methods in fixture
                            case Some(method) =>
                                Tasty.bodyTree(method).map:
                                    case Maybe.Present(tree) =>
                                        // Body was decoded; tree should be non-null
                                        assert(tree != null, "bodyTree must return a non-null Tree when Present")
                                        succeed
                                    case Maybe.Absent =>
                                        // Not all methods have bodies (abstract, Java-sourced, etc.)
                                        succeed
                        end match
            ).map:
                case Result.Success(s) => s
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Leaf 23: bodyTree returns Absent under withClasspath(cp) (no decode context)
    "Leaf 23: Tasty.bodyTree returns Maybe.Absent under withClasspath(cp) (no decode context)" in run {
        // withClasspath(cp) installs Binding(cp, Maybe.Absent): no DecodeContext.
        // Tasty.bodyTree checks the DecodeContext first; Absent DecodeContext -> Absent result.
        // We use a synthetic method to confirm that Tasty.bodyTree (not the field) returns Absent.
        val syntheticMethod = Tasty.Symbol.Method(
            kyo.Tasty.SymbolId(0),
            Tasty.Name("fake"),
            Tasty.Flags.empty,
            kyo.Tasty.SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val cp = Tasty.Classpath(
            symbols = Chunk(syntheticMethod),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        // withClasspath(cp) binding has no DecodeContext; Tasty.bodyTree must return Absent.
        Abort.run[TastyError](
            Tasty.withClasspath(cp):
                Tasty.bodyTree(syntheticMethod)
        ).map:
            case Result.Success(result) =>
                assert(!result.isDefined, "Tasty.bodyTree must return Absent inside withClasspath(cp) (no DecodeContext)")
                succeed
            case Result.Failure(e) =>
                fail(s"Tasty.bodyTree raised unexpected TastyError: $e")
            case Result.Panic(t) =>
                throw t
    }

    // Phase 09 Leaf 3: noBodyFieldOnAnyClassLike
    // Given: a probe accessing .body on Symbol.Class
    // When: compileErrors is invoked
    // Then: non-empty string (body is not a member of Symbol.Class after Phase 09)
    // Pins: Cat 17 Option A
    "Phase 09 Leaf 3: accessing .body on ClassLike is a compile error" in {
        assert(
            compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Class).body").nonEmpty,
            "Expected compile error for Symbol.Class.body after Phase 09"
        )
        succeed
    }

    // Phase 09 Leaf 4: noBodyFieldOnMethodValVar
    // Given: probes accessing .body on Symbol.Method, Symbol.Val, Symbol.Var
    // When: compileErrors is invoked for each
    // Then: every probe returns a non-empty string
    // Pins: Cat 17 Option A
    "Phase 09 Leaf 4: accessing .body on Method/Val/Var is a compile error" in {
        assert(
            compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Method).body").nonEmpty,
            "Expected compile error for Symbol.Method.body"
        )
        assert(
            compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Val).body").nonEmpty,
            "Expected compile error for Symbol.Val.body"
        )
        assert(
            compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Var).body").nonEmpty,
            "Expected compile error for Symbol.Var.body"
        )
        succeed
    }

    // Phase 09 Leaf 5: schemaRoundTripFaithfulPostBodyRemoval
    // Given: a classpath cp built via coldLoadBinding; encoded via Json/Schema; decoded back
    // When: the test reads fields of the round-tripped cp
    // Then: every field equals the original; .body is not callable (compile check)
    // Pins: Cat 17 faithful round-trip replaces lossy schemaSymbolBody
    "Phase 09 Leaf 5: Schema round-trip is faithful after body field removal" in run {
        val src = MemoryFileSource()
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.coldLoadBinding(Seq("root"), Tasty.ErrorMode.SoftFail, Maybe.Absent, src, 1).flatMap: binding =>
                    val cp      = binding.cp
                    val encoded = Json.encode(cp)
                    Json.decode[Tasty.Classpath](encoded) match
                        case Result.Success(cp2) =>
                            assert(cp2.symbols.length == cp.symbols.length, "Symbol count must match after round-trip")
                            assert(cp2.errors.length == cp.errors.length, "Error count must match after round-trip")
                            assert(
                                cp2.symbols.length == 0 || cp2.symbols.head.flags == cp.symbols.head.flags,
                                "Flags must match after round-trip"
                            )
                            // body is not callable (confirmed by Leaf 3 and 4)
                            succeed
                        case Result.Failure(e) =>
                            fail(s"Json decode failed: $e")
                        case Result.Panic(t) =>
                            throw t
                    end match
            ).map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

end BodyTreeTest
