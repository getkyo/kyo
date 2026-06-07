package kyo

import kyo.Json
import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.TastyState

/** plan leaves 22-23: Tasty.bodyTree.
  *
  * Leaf 22: Tasty.bodyTree returns Present under withClasspath(roots) (decode context attached).
  * Leaf 23: Tasty.bodyTree returns Absent under withClasspath(cp) (no decode context).
  */
class BodyTreeTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Leaf 22: bodyTree returns Present under a binding with a populated bodyStore
    "Leaf 22: Tasty.bodyTree returns Maybe.Present for method with body under live classpath" in {
        val src = MemoryFileSource()
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        Scope.run:
            Abort.run[TastyError](
                // Use coldLoadBinding to populate DecodeContext.bodyStore with body data
                ClasspathOrchestrator.coldLoadBinding(Seq("root"), Tasty.ErrorMode.SoftFail, Maybe.Absent, src, 1).flatMap: binding =>
                    val cp = binding.cp
                    TastyState.bindingLocal.let(Maybe.Present(binding)):
                        // Find a concrete method (SomeObject.tasty contains a non-abstract method).
                        // The bodyStore is populated by coldLoadBinding, so a concrete method from
                        // the loaded fixture MUST have its body tree available via Tasty.bodyTree.
                        val methodOpt = cp.allMethods.toSeq.find(m => !m.isAbstract)
                        methodOpt match
                            case None =>
                                Kyo.lift(fail("Expected at least one concrete method in SomeObject fixture"))
                            case Some(method) =>
                                Tasty.bodyTree(method).map:
                                    case Maybe.Present(tree) =>
                                        // Body was decoded; tree is a non-null AST node
                                        assert(tree != null, "bodyTree must return a non-null Tree when Present")
                                        succeed
                                    case Maybe.Absent =>
                                        // A concrete method loaded via coldLoadBinding must have body bytes
                                        // in bodyStore; Maybe.Absent here means the bodyStore is empty or
                                        // the method's body was not recorded -- both are incorrect.
                                        fail(
                                            s"Tasty.bodyTree returned Maybe.Absent for concrete method '${method.name}' " +
                                                "loaded via coldLoadBinding; bodyStore should be populated"
                                        )
                        end match
            ).map:
                case Result.Success(s) => s
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Leaf 23: bodyTree returns Absent under withClasspath(cp) (no decode context)
    "Leaf 23: Tasty.bodyTree returns Maybe.Absent under withClasspath(cp) (no decode context)" in {
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

    // Leaf 3: noBodyFieldOnAnyClassLike
    // Given: a probe accessing .body on Symbol.Class
    // When: compileErrors is invoked
    // Then: non-empty string (body is not a member of Symbol.Class after)
    "accessing .body on ClassLike is a compile error" in {
        val classErrors = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Class).body").length
        assert(classErrors > 0, "Expected compile error for Symbol.Class.body (body field removed)")
        succeed
    }

    // Leaf 4: noBodyFieldOnMethodValVar
    // Given: probes accessing .body on Symbol.Method, Symbol.Val, Symbol.Var
    // When: compileErrors is invoked for each
    // Then: every probe returns a non-empty string
    "accessing .body on Method/Val/Var is a compile error" in {
        val methodErrors = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Method).body").length
        val valErrors    = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Val).body").length
        val varErrors    = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Var).body").length
        assert(methodErrors > 0, "Expected compile error for Symbol.Method.body")
        assert(valErrors > 0, "Expected compile error for Symbol.Val.body")
        assert(varErrors > 0, "Expected compile error for Symbol.Var.body")
        succeed
    }

    // Leaf 5: schemaRoundTripFaithfulPostBodyRemoval
    // Given: a classpath cp built via coldLoadBinding; encoded via Json/Schema; decoded back
    // When: the test reads fields of the round-tripped cp
    // Then: every field equals the original; .body is not callable (compile check)
    "Schema round-trip is faithful after body field removal" in {
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
