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
            Abort.run[TastyError](openFixtureClasspath.map: cp =>
                given Tasty.Classpath = cp
                val all               = Tasty.allClassLike
                assert(all.nonEmpty, "allClassLike must return non-empty for fixture classpath")
                // all results must be ClassLike instances
                assert(
                    all.forall(_.isInstanceOf[Tasty.Symbol.ClassLike]),
                    "allClassLike must return only ClassLike instances"
                )).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Leaf 10: aggregators are total on empty Classpath
    "Leaf 10: all aggregators return Chunk.empty for empty Classpath" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            assert(Tasty.allClassLike.isEmpty, "allClassLike on empty cp")
            assert(Tasty.allClasses.isEmpty, "allClasses on empty cp")
            assert(Tasty.allObjects.isEmpty, "allObjects on empty cp")
            assert(Tasty.allTraits.isEmpty, "allTraits on empty cp")
            assert(Tasty.allMethods.isEmpty, "allMethods on empty cp")
            assert(Tasty.allVals.isEmpty, "allVals on empty cp")
            assert(Tasty.allVars.isEmpty, "allVars on empty cp")
            assert(Tasty.allFields.isEmpty, "allFields on empty cp")
            assert(Tasty.allTypes.isEmpty, "allTypes on empty cp")
            assert(Tasty.allPackages.isEmpty, "allPackages on empty cp")
    }

    // allClasses result type is correct
    "allClasses returns Chunk[Symbol.Class]" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.map: cp =>
                given Tasty.Classpath = cp
                val classes           = Tasty.allClasses
                assert(
                    classes.forall(_.isInstanceOf[Tasty.Symbol.Class]),
                    "allClasses must return only Symbol.Class instances"
                )).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // allObjects returns objects
    "allObjects returns Chunk[Symbol.Object]" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.map: cp =>
                given Tasty.Classpath = cp
                val objects           = Tasty.allObjects
                assert(
                    objects.forall(_.isInstanceOf[Tasty.Symbol.Object]),
                    "allObjects must return only Symbol.Object instances"
                )).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

end QueryAggregatorsTest
