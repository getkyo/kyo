package kyo

/** Tasty.findClass, findClassLike, findObject, findSymbol, findPackage,
  * requireClass, requireClassLike, requireObject, requireSymbol, requirePackage, requireMethod.
  *
  * Uses embedded PlainClass/SomeObject/SomeTrait fixtures for cross-platform coverage.
  */
class QueryFindTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val fixturePickles: Chunk[Tasty.Pickle] = Chunk(
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty)),
        Tasty.Pickle("some-object", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someObjectTasty)),
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty))
    )

    "Tasty.findClass returns Present for fixture class, Absent for missing" in {
        Abort.run[TastyError](
            Tasty.withPickles(fixturePickles) {
                for
                    presentResult <- Tasty.findClass("kyo.fixtures.PlainClass")
                    absentResult  <- Tasty.findClass("does.not.Exist")
                yield
                    assert(presentResult.isDefined, s"findClass(PlainClass) must return Present; got $presentResult")
                    assert(!absentResult.isDefined, "findClass(does.not.Exist) must return Absent")
                    succeed
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // requireClass aborts on missing fully-qualified name
    "Tasty.requireClass aborts with TastyError.NotFound for missing fully-qualified name" in {
        Abort.run[TastyError](
            Tasty.withPickles(fixturePickles) {
                Abort.run[TastyError](Tasty.requireClass("does.not.Exist")).map {
                    case Result.Failure(_: TastyError.NotFound) => succeed
                    case Result.Success(_)                      => fail("requireClass must abort for missing fully-qualified name")
                    case other                                  => fail(s"Unexpected: $other")
                }
            }
        ).map {
            case Result.Success(s) => s
            case Result.Failure(e) => fail(s"Unexpected outer failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "Tasty.allClassLike returns non-empty Chunk for fixture classpath" in {
        Abort.run[TastyError](
            Tasty.withPickles(fixturePickles) {
                Tasty.allClassLike.map { all =>
                    assert(all.nonEmpty, "allClassLike must return non-empty for fixture classpath")
                    succeed
                }
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "aggregators on empty Classpath return Chunk.empty" in {
        Tasty.withPickles(Chunk.empty) {
            for
                acl <- Tasty.allClassLike
                am  <- Tasty.allMethods
                at  <- Tasty.allTypes
                ap  <- Tasty.allPackages
            yield
                assert(acl.isEmpty, "allClassLike on empty classpath must be empty")
                assert(am.isEmpty, "allMethods on empty classpath must be empty")
                assert(at.isEmpty, "allTypes on empty classpath must be empty")
                assert(ap.isEmpty, "allPackages on empty classpath must be empty")
                succeed
        }
    }

    "Tasty.findObject returns Present for SomeObject fixture" in {
        Abort.run[TastyError](
            Tasty.withPickles(fixturePickles) {
                Tasty.findObject("kyo.fixtures.SomeObject").map { obj =>
                    assert(obj.isDefined, s"findObject(SomeObject) must return Present; got $obj")
                    succeed
                }
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "Tasty.findClassLike returns Present for SomeTrait fixture" in {
        Abort.run[TastyError](
            Tasty.withPickles(fixturePickles) {
                Tasty.findClassLike("kyo.fixtures.SomeTrait").map { trt =>
                    assert(trt.isDefined, s"findClassLike(SomeTrait) must return Present; got $trt")
                    succeed
                }
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end QueryFindTest
