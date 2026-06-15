package kyo

/** Tasty.allClassLike, allClasses, allObjects, allTraits, allMethods,
  * allVals, allVars, allFields, allTypes, allPackages.
  */
class QueryAggregatorsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val fixturePickles: Chunk[Tasty.Pickle] = Chunk(
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty)),
        Tasty.Pickle("some-object", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someObjectTasty)),
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty))
    )

    // allClassLike covers all four subtypes
    "Tasty.allClassLike is non-empty for fixture classpath" in {
        Abort.run[TastyError](
            Tasty.withPickles(fixturePickles) {
                Tasty.allClassLike.map { all =>
                    assert(all.nonEmpty, "allClassLike must return non-empty for fixture classpath")
                    assert(
                        all.forall(_.isInstanceOf[Tasty.Symbol.ClassLike]),
                        "allClassLike must return only ClassLike instances"
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // aggregators are total on empty Classpath
    "all aggregators return Chunk.empty for empty Classpath" in {
        Tasty.withPickles(Chunk.empty) {
            for
                acl <- Tasty.allClassLike
                ac  <- Tasty.allClasses
                ao  <- Tasty.allObjects
                at  <- Tasty.allTraits
                am  <- Tasty.allMethods
                av  <- Tasty.allVals
                avr <- Tasty.allVars
                af  <- Tasty.allFields
                aty <- Tasty.allTypes
                ap  <- Tasty.allPackages
            yield
                assert(acl.isEmpty, "allClassLike on empty classpath")
                assert(ac.isEmpty, "allClasses on empty classpath")
                assert(ao.isEmpty, "allObjects on empty classpath")
                assert(at.isEmpty, "allTraits on empty classpath")
                assert(am.isEmpty, "allMethods on empty classpath")
                assert(av.isEmpty, "allVals on empty classpath")
                assert(avr.isEmpty, "allVars on empty classpath")
                assert(af.isEmpty, "allFields on empty classpath")
                assert(aty.isEmpty, "allTypes on empty classpath")
                assert(ap.isEmpty, "allPackages on empty classpath")
                succeed
        }
    }

    // allClasses result type is correct
    "allClasses returns Chunk[Symbol.Class]" in {
        Abort.run[TastyError](
            Tasty.withPickles(fixturePickles) {
                Tasty.allClasses.map { classes =>
                    assert(
                        classes.forall(_.isInstanceOf[Tasty.Symbol.Class]),
                        "allClasses must return only Symbol.Class instances"
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // allObjects returns objects
    "allObjects returns Chunk[Symbol.Object]" in {
        Abort.run[TastyError](
            Tasty.withPickles(fixturePickles) {
                Tasty.allObjects.map { objects =>
                    assert(
                        objects.forall(_.isInstanceOf[Tasty.Symbol.Object]),
                        "allObjects must return only Symbol.Object instances"
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end QueryAggregatorsTest
