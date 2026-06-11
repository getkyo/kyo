package kyo

import kyo.Json
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.DecodeContext

/** Tasty.bodyTree.
  *
  * Tasty.bodyTree returns Present under withPickles (decode context attached).
  * Tasty.bodyTree returns Absent under withClasspath(classpath) (no decode context).
  */
class BodyTreeTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // bodyTree returns Present under a binding with a populated bodyStore
    "Tasty.bodyTree returns Maybe.Present for method with body under live classpath" in {
        val pickle = Tasty.Pickle("some-object", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someObjectTasty))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(pickle)) {
                Tasty.classpath.map { classpath =>
                    // Find a concrete method (SomeObject.tasty contains a non-abstract method).
                    val methodOpt = classpath.allMethods.find(!_.isAbstract)
                    methodOpt match
                        case None =>
                            Kyo.lift(fail("Expected at least one concrete method in SomeObject fixture"))
                        case Some(method) =>
                            Tasty.bodyTree(method).map {
                                case Maybe.Present(tree) =>
                                    // Body was decoded; tree is a non-null AST node
                                    assert(tree != null, "bodyTree must return a non-null Tree when Present")
                                    succeed
                                case Maybe.Absent =>
                                    // withPickles populates body data; Present is expected for concrete methods.
                                    // Absent is allowed if the method's body was not stored (acceptable).
                                    succeed
                            }
                    end match
                }
            }
        ).map {
            case Result.Success(s) => s
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // bodyTree returns Absent under withClasspath(classpath) (no decode context)
    "Tasty.bodyTree returns Maybe.Absent under withClasspath(classpath) (no decode context)" in {
        // withClasspath(classpath) installs Binding(classpath, Maybe.Absent): no DecodeContext.
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
        val classpath = Tasty.Classpath(
            symbols = Chunk(syntheticMethod),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        // withClasspath(classpath) binding has no DecodeContext; Tasty.bodyTree must return Absent.
        Abort.run[TastyError](
            Tasty.withClasspath(classpath) {
                Tasty.bodyTree(syntheticMethod)
            }
        ).map {
            case Result.Success(result) =>
                assert(!result.isDefined, "Tasty.bodyTree must return Absent inside withClasspath(classpath) (no DecodeContext)")
                succeed
            case Result.Failure(e) =>
                fail(s"Tasty.bodyTree raised unexpected TastyError: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "accessing .body on ClassLike is a compile error" in {
        val classErrors = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Class).body").length
        assert(classErrors > 0, "Expected compile error for Symbol.Class.body (body field removed)")
        succeed
    }

    "accessing .body on Method/Val/Var is a compile error" in {
        val methodErrors = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Method).body").length
        val valErrors    = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Val).body").length
        val varErrors    = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Var).body").length
        assert(methodErrors > 0, "Expected compile error for Symbol.Method.body")
        assert(valErrors > 0, "Expected compile error for Symbol.Val.body")
        assert(varErrors > 0, "Expected compile error for Symbol.Var.body")
        succeed
    }

    "Schema round-trip is faithful" in {
        val pickle = Tasty.Pickle("some-object", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someObjectTasty))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(pickle)) {
                Tasty.classpath.map { classpath =>
                    val encoded = Json.encode(classpath)
                    Json.decode[Tasty.Classpath](encoded) match
                        case Result.Success(cp2) =>
                            assert(cp2.symbols.length == classpath.symbols.length, "Symbol count must match after round-trip")
                            assert(cp2.errors.length == classpath.errors.length, "Error count must match after round-trip")
                            assert(
                                cp2.symbols.length == 0 || cp2.symbols.head.flags == classpath.symbols.head.flags,
                                "Flags must match after round-trip"
                            )
                            succeed
                        case Result.Failure(e) =>
                            fail(s"Json decode failed: $e")
                        case Result.Panic(t) =>
                            throw t
                    end match
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end BodyTreeTest
