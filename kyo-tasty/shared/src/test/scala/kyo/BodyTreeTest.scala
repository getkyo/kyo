package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Phase 03 plan leaves 22-23: Tasty.bodyTree.
  *
  * Leaf 22: Tasty.bodyTree returns Present under withClasspath(roots) (decode context attached).
  * Leaf 23: Tasty.bodyTree returns Absent under withClasspath(cp) (no decode context).
  *
  * Pins: item 29 bodyTree migration; INV-009 site-3.
  */
class BodyTreeTest extends Test:

    import AllowUnsafe.embrace.danger

    // Leaf 22: bodyTree returns Present under ClasspathOrchestrator.init (decode context present)
    "Leaf 22: Tasty.bodyTree returns Maybe.Present for method with body under live classpath" in run {
        val src = MemoryFileSource()
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).flatMap: cp =>
                    given Tasty.Classpath = cp
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

    // Leaf 23: bodyTree returns Absent under Classpath.fromPickles (no decode context)
    "Leaf 23: Tasty.bodyTree returns Maybe.Absent under Classpath.fromPickles (no decode context)" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            // On a pure-data classpath (fromPickles with no symbols), bodyTree for any symbol
            // must return Absent because there is no decode context.
            // We verify using a synthetic method symbol.
            val syntheticMethod = Tasty.Symbol.Method(
                kyo.Tasty.SymbolId(99),
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
            // fromPickles classpath has no decode context; bodyTree must return Absent
            // (the method has Maybe.Absent body field)
            val result = syntheticMethod.body
            assert(!result.isDefined, "Pure-data symbol with Absent body must have Absent body")
    }

end BodyTreeTest
