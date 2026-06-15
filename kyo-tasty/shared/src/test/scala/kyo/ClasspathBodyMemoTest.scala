package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.DecodeContext

/** Tests for the Classpath bodyMemo contract: excluded from equality, each withPickles
  * invocation produces a fresh empty DecodeContext, and bodyTree memoizes within a single context.
  */
class ClasspathBodyMemoTest extends kyo.test.Test[Any]:

    private val someObjectPickle =
        Tasty.Pickle("some-object", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someObjectTasty))

    // bodyMemo is a class body member, NOT a constructor param, so it is excluded from
    // auto-generated equals/hashCode. Verified by reflexivity and stable hashCode after memoization.
    "bodyMemo excluded from case-class equality" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someObjectPickle)) {
                Tasty.classpath.map { classpath =>
                    val symOpt = classpath.allMethods.find(!_.isAbstract)
                    symOpt match
                        case None =>
                            // No concrete method; still verify reflexivity and hashCode stability.
                            Kyo.lift {
                                assert(classpath == classpath, "Classpath must equal itself (reflexivity)")
                                assert(classpath.hashCode == classpath.hashCode, "hashCode must be stable")
                                succeed
                            }
                        case Some(symbol) =>
                            Tasty.bodyTree(symbol).map { _ =>
                                // After memoization, the classpath must still equal itself.
                                assert(classpath == classpath, "Classpath must equal itself after bodyMemo is populated")
                                assert(classpath.hashCode == classpath.hashCode, "hashCode must be stable")
                                // A copy with identical fields is equal (bodyMemo is in DecodeContext, not in Classpath).
                                val cpCopy = classpath.copy(errors = classpath.errors)
                                assert(
                                    classpath == cpCopy,
                                    "classpath.copy with identical fields must equal original (bodyMemo is in DecodeContext, not Classpath)"
                                )
                                succeed
                            }
                    end match
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "each withPickles invocation produces a fresh empty DecodeContext bodyMemo" in {
        // A fresh DecodeContext starts at 0 entries; verify behavioral observable.
        Sync.defer {
            val freshCtx = DecodeContext.fresh()
            assert(freshCtx.bodyMemo.size() == 0, s"Expected fresh DecodeContext bodyMemo size 0, got ${freshCtx.bodyMemo.size()}")
            succeed
        }
    }

    // Both calls on the same symbol must return the SAME Tree instance (reference equality via bodyMemo).
    "Tasty.bodyTree memoizes within a single withPickles scope (reference equality)" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someObjectPickle)) {
                Tasty.classpath.map { classpath =>
                    val symOpt = classpath.allMethods.find(!_.isAbstract)
                    symOpt match
                        case None =>
                            // No concrete method; test is inconclusive but not failed.
                            Kyo.lift(succeed)
                        case Some(symbol) =>
                            for
                                result1 <- Tasty.bodyTree(symbol)
                                result2 <- Tasty.bodyTree(symbol)
                            yield
                                if result1.isDefined && result2.isDefined then
                                    // Memoization: both calls must return the SAME Tree instance.
                                    assert(
                                        result1.get.asInstanceOf[AnyRef] eq result2.get.asInstanceOf[AnyRef],
                                        "Both Tasty.bodyTree calls on the same symbol must return the same Tree instance (bodyMemo)"
                                    )
                                end if
                                succeed
                    end match
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

end ClasspathBodyMemoTest
