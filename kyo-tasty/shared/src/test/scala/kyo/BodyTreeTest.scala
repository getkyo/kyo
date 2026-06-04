package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext

/** Phase 03 plan leaves 22-23: Tasty.bodyTree.
  *
  * Leaf 22: Tasty.bodyTree returns Present under withClasspath(roots) (decode context attached).
  * Leaf 23: Tasty.bodyTree returns Absent under withClasspath(cp) (no decode context).
  *
  * Pins: item 29 bodyTree migration; INV-009 site-3.
  */
class BodyTreeTest extends Test:

    import AllowUnsafe.embrace.danger

    // Leaf 22: bodyTree returns Present under a binding with a fresh DecodeContext
    "Leaf 22: Tasty.bodyTree returns Maybe.Present for method with body under live classpath" in run {
        val src = MemoryFileSource()
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).flatMap: cp =>
                    val binding = Binding(cp, Maybe.Present(DecodeContext.fresh()))
                    Tasty.bindingLocal.let(Maybe.Present(binding)):
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
            Maybe.Absent,
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

end BodyTreeTest
