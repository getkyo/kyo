package kyo

import kyo.Tasty.SymbolId

/** Tests pinning the Classpath immutability contract: constructor fields have val semantics
  * and return reference-equal values on repeated access.
  */
class ClasspathImmutabilityTest extends kyo.test.Test[Any]:

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    "Classpath constructor fields are immutable (val semantics)" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    Sync.defer {
                        // Access each field twice; both accesses must return reference-equal (or value-equal) results.
                        val syms1     = classpath.symbols
                        val syms2     = classpath.symbols
                        val errs1     = classpath.errors
                        val errs2     = classpath.errors
                        val fullName1 = classpath.indices.byFullName
                        val fullName2 = classpath.indices.byFullName
                        val top1      = classpath.indices.topLevelClassIds
                        val top2      = classpath.indices.topLevelClassIds
                        val root1     = classpath.rootSymbolId
                        val root2     = classpath.rootSymbolId
                        // byFullName is Dict (opaque type); structural sameness checked via same case class field access.
                        val fullNameSameRef = java.lang.System.identityHashCode(fullName1.asInstanceOf[AnyRef]) ==
                            java.lang.System.identityHashCode(fullName2.asInstanceOf[AnyRef])
                        (syms1 eq syms2, errs1 eq errs2, fullNameSameRef, top1 eq top2, root1 == root2)
                    }
                }
            }
        ).map {
            case Result.Success((symsSame, errsSame, fullNameSame, topSame, rootSame)) =>
                assert(symsSame, "symbols field must return reference-equal value on repeated access")
                assert(errsSame, "errors field must return reference-equal value on repeated access")
                assert(fullNameSame, "byFullName field must return reference-equal value on repeated access")
                assert(topSame, "topLevelClassIds field must return reference-equal value on repeated access")
                assert(rootSame, "rootSymbolId field must return equal value on repeated access")
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

end ClasspathImmutabilityTest
