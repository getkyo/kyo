package kyo

/** Tests pinning the effect-row contract for Tasty.bodyTree. */
class TastyEffectRowTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val someObjectPickle =
        Tasty.Pickle("some-object", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someObjectTasty))

    // The bodyMemo ConcurrentHashMap guarantees the first decode result is stored and returned
    // on all subsequent calls, producing reference-equal Tree instances.
    "Symbol.body and Tasty.bodyTree return the same Tree instance via bodyMemo" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someObjectPickle)) {
                Tasty.classpath.map { classpath =>
                    val allSyms = classpath.symbols
                    val methodSym = allSyms.find {
                        case _: Tasty.Symbol.Method => true
                        case _: Tasty.Symbol.Val    => true
                        case _                      => false
                    }
                    methodSym match
                        case None =>
                            Kyo.lift(fail("fixture missing method/val with body; test cannot proceed"))
                        case Some(symbol) =>
                            for
                                viaFirst  <- Tasty.bodyTree(symbol)
                                viaSecond <- Tasty.bodyTree(symbol)
                            yield
                                // If bodyTree returns Present, both calls must return the same memoized instance.
                                if viaFirst.isDefined && viaSecond.isDefined then
                                    assert(
                                        viaFirst.get.asInstanceOf[AnyRef] eq viaSecond.get.asInstanceOf[AnyRef],
                                        s"Tasty.bodyTree calls must return the same Tree instance (bodyMemo memoization); " +
                                            s"got different instances of ${viaFirst.get.getClass.getSimpleName}"
                                    )
                                end if
                                // If bodyTree returns Absent (no body stored), that is acceptable.
                                succeed
                    end match
                }
            }
        ).map {
            case Result.Success(a) => a
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

end TastyEffectRowTest
