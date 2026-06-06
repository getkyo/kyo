package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator

/** plan leaves 5-8: Tasty.findClass, findClassLike, findObject, findSymbol, findPackage,
  * requireClass, requireClassLike, requireObject, requireSymbol, requirePackage, requireMethod.
  *
  * Uses embedded PlainClass/SomeObject/SomeTrait fixtures for cross-platform coverage.
  */
class QueryFindTest extends Test:

    import AllowUnsafe.embrace.danger

    private def openFixtureClasspath(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        src.add("root/SomeTrait.tasty", kyo.fixtures.Embedded.someTraitTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openFixtureClasspath

    // Leaf 5 + 6: findClass returns Present / Absent
    "Leaf 5+6: Tasty.findClass returns Present for fixture class, Absent for missing" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    for
                        presentResult <- Tasty.findClass("kyo.fixtures.PlainClass")
                        absentResult  <- Tasty.findClass("does.not.Exist")
                    yield
                        assert(presentResult.isDefined, s"findClass(PlainClass) must return Present; got $presentResult")
                        assert(!absentResult.isDefined, "findClass(does.not.Exist) must return Absent")
                        succeed).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Leaf 7: requireClass aborts on missing FQN
    "Leaf 7: Tasty.requireClass aborts with TastyError.NotFound for missing FQN" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    Abort.run[TastyError](Tasty.requireClass("does.not.Exist")).map:
                        case Result.Failure(_: TastyError.NotFound) => succeed
                        case Result.Success(_)                      => fail("requireClass must abort for missing FQN")
                        case other                                  => fail(s"Unexpected: $other")).map:
                case Result.Success(s) => s
                case Result.Failure(e) => fail(s"Unexpected outer failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Tasty.allClassLike returns a non-empty Chunk for fixture classpath
    "Leaf 9 (partial): Tasty.allClassLike returns non-empty Chunk for fixture classpath" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    Tasty.allClassLike.map: all =>
                        assert(all.nonEmpty, "allClassLike must return non-empty for fixture classpath")
                        succeed).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Leaf 10: aggregators on empty Classpath return Chunk.empty
    "Leaf 10: aggregators on empty Classpath return Chunk.empty" in run {
        Tasty.withPickles(Chunk.empty):
            for
                acl <- Tasty.allClassLike
                am  <- Tasty.allMethods
                at  <- Tasty.allTypes
                ap  <- Tasty.allPackages
            yield
                assert(acl.isEmpty, "allClassLike on empty cp must be empty")
                assert(am.isEmpty, "allMethods on empty cp must be empty")
                assert(at.isEmpty, "allTypes on empty cp must be empty")
                assert(ap.isEmpty, "allPackages on empty cp must be empty")
                succeed
    }

    // Tasty.findObject returns Present for a Scala object
    "Tasty.findObject returns Present for SomeObject fixture" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    Tasty.findObject("kyo.fixtures.SomeObject").map: obj =>
                        assert(obj.isDefined, s"findObject(SomeObject) must return Present; got $obj")
                        succeed).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Tasty.findClassLike returns Present for a trait
    "Tasty.findClassLike returns Present for SomeTrait fixture" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    Tasty.findClassLike("kyo.fixtures.SomeTrait").map: trt =>
                        assert(trt.isDefined, s"findClassLike(SomeTrait) must return Present; got $trt")
                        succeed).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

end QueryFindTest
