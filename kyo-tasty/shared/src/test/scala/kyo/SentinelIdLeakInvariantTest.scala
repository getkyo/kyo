package kyo

/** Verifies that no SymbolId.value == -1 sentinel survives in produced public ADTs.
  *
  * After finalizeMerge, `Named(SymbolId(-1))` sentinels must be filtered out of
  * parentTypes, declaredType, and annotations. This test verifies that invariant holds against
  * a real fixture classpath loaded cold via ClasspathOrchestrator.
  *
  * Covers:
  *   noSentinelIdInParentTypes: parentTypes Chunk contains no Named(SymbolId(-1))
  *   noSentinelIdInClassLikeAnnotations: ClassLike annotation tycon types are not Named(SymbolId(-1))
  */
class SentinelIdLeakInvariantTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Walk a Type recursively and return all SymbolId values found in Named and ThisType nodes. */
    private def namedIdsInType(t: Tasty.Type): List[Int] =
        t match
            case Tasty.Type.Named(sid)               => List(sid.value)
            case Tasty.Type.Applied(base, args)      => namedIdsInType(base) ++ args.flatMap(namedIdsInType).toList
            case Tasty.Type.Function(ps, r)          => ps.flatMap(namedIdsInType).toList ++ namedIdsInType(r)
            case Tasty.Type.ContextFunction(ps, r)   => ps.flatMap(namedIdsInType).toList ++ namedIdsInType(r)
            case Tasty.Type.ByName(u)                => namedIdsInType(u)
            case Tasty.Type.Repeated(e)              => namedIdsInType(e)
            case Tasty.Type.Array(e)                 => namedIdsInType(e)
            case Tasty.Type.AndType(l, r)            => namedIdsInType(l) ++ namedIdsInType(r)
            case Tasty.Type.OrType(l, r)             => namedIdsInType(l) ++ namedIdsInType(r)
            case Tasty.Type.Refinement(p, _, i)      => namedIdsInType(p) ++ namedIdsInType(i)
            case Tasty.Type.Annotated(u, annotation) => namedIdsInType(u) ++ namedIdsInType(annotation.annotationType)
            case Tasty.Type.SuperType(th, u)         => namedIdsInType(th) ++ namedIdsInType(u)
            case Tasty.Type.Wildcard(lo, hi)         => namedIdsInType(lo) ++ namedIdsInType(hi)
            case Tasty.Type.MatchType(b, s, cs)      => namedIdsInType(b) ++ namedIdsInType(s) ++ cs.flatMap(namedIdsInType).toList
            case Tasty.Type.FlexibleType(u)          => namedIdsInType(u)
            case Tasty.Type.MatchCase(p, r)          => namedIdsInType(p) ++ namedIdsInType(r)
            case Tasty.Type.Rec(p)                   => namedIdsInType(p)
            case Tasty.Type.RecThis(rec)             => namedIdsInType(rec)
            case Tasty.Type.Skolem(u)                => namedIdsInType(u)
            case Tasty.Type.TermRef(pref, _)         => namedIdsInType(pref)
            case Tasty.Type.TypeRef(pref, _)         => namedIdsInType(pref)
            case Tasty.Type.TypeLambda(_, body)      => namedIdsInType(body)
            case Tasty.Type.Bounds(lo, hi)           => namedIdsInType(lo) ++ namedIdsInType(hi)
            case Tasty.Type.ThisType(sid)            => List(sid.value)
            case _                                   => Nil

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    // noSentinelIdInParentTypes
    "noSentinelIdInParentTypes: no Named(SymbolId(-1)) in any symbol parentTypes after cold load" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    var violations = List.empty[String]
                    classpath.symbols.foreach { symbol =>
                        symbol match
                            case c: Tasty.Symbol.ClassLike =>
                                c.parentTypes.foreach { pt =>
                                    val ids = namedIdsInType(pt)
                                    ids.foreach { v =>
                                        if v == -1 then
                                            violations ::= s"${c.name.asString}.parentType=$pt has Named(SymbolId(-1))"
                                    }
                                }
                            case _ => ()
                    }
                    violations
                }
            }
        ).map {
            case Result.Success(violations) =>
                assert(violations.isEmpty, s"Sentinel leaks found:\n${violations.mkString("\n")}")
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // noSentinelIdInClassLikeAnnotations
    "noSentinelIdInClassLikeAnnotations: no Named(SymbolId(-1)) in ClassLike annotation types after cold load" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    var violations = List.empty[String]
                    classpath.symbols.foreach { symbol =>
                        symbol match
                            case c: Tasty.Symbol.ClassLike =>
                                c.annotations.foreach { annotation =>
                                    val ids = namedIdsInType(annotation.annotationType)
                                    ids.foreach { v =>
                                        if v == -1 then
                                            violations ::= s"${c.name.asString}.annotation.type=${annotation.annotationType} has Named(SymbolId(-1))"
                                    }
                                }
                            case _ => ()
                    }
                    violations
                }
            }
        ).map {
            case Result.Success(violations) =>
                assert(violations.isEmpty, s"Sentinel leaks found:\n${violations.mkString("\n")}")
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end SentinelIdLeakInvariantTest
