package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Phase 03 plan leaves 9-10: Tasty.allClassLike, allClasses, allObjects, allTraits, allMethods,
  * allVals, allVars, allFields, allTypes, allPackages.
  *
  * Pins: item 29 ClassLike aggregator; item 29 totality.
  */
class QueryAggregatorsTest extends Test:

    import AllowUnsafe.embrace.danger

    private def openFixtureClasspath(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        src.add("root/SomeTrait.tasty", kyo.fixtures.Embedded.someTraitTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openFixtureClasspath

    // Leaf 9: allClassLike covers all four subtypes
    "Leaf 9: Tasty.allClassLike is non-empty for fixture classpath" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    Tasty.allClassLike.map: all =>
                        assert(all.nonEmpty, "allClassLike must return non-empty for fixture classpath")
                        assert(
                            all.forall(_.isInstanceOf[Tasty.Symbol.ClassLike]),
                            "allClassLike must return only ClassLike instances"
                        )
                        succeed).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Leaf 10: aggregators are total on empty Classpath
    "Leaf 10: all aggregators return Chunk.empty for empty Classpath" in run {
        Tasty.withPickles(Chunk.empty):
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
                assert(acl.isEmpty, "allClassLike on empty cp")
                assert(ac.isEmpty, "allClasses on empty cp")
                assert(ao.isEmpty, "allObjects on empty cp")
                assert(at.isEmpty, "allTraits on empty cp")
                assert(am.isEmpty, "allMethods on empty cp")
                assert(av.isEmpty, "allVals on empty cp")
                assert(avr.isEmpty, "allVars on empty cp")
                assert(af.isEmpty, "allFields on empty cp")
                assert(aty.isEmpty, "allTypes on empty cp")
                assert(ap.isEmpty, "allPackages on empty cp")
                succeed
    }

    // allClasses result type is correct
    "allClasses returns Chunk[Symbol.Class]" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    Tasty.allClasses.map: classes =>
                        assert(
                            classes.forall(_.isInstanceOf[Tasty.Symbol.Class]),
                            "allClasses must return only Symbol.Class instances"
                        )
                        succeed).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // allObjects returns objects
    "allObjects returns Chunk[Symbol.Object]" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    Tasty.allObjects.map: objects =>
                        assert(
                            objects.forall(_.isInstanceOf[Tasty.Symbol.Object]),
                            "allObjects must return only Symbol.Object instances"
                        )
                        succeed).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

end QueryAggregatorsTest
