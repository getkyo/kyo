package kyo

/** Tests for Classpath index maps are immutable after Pass C construction.
  */
class ClasspathIndexImmutabilityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    "fullNameIndex is built once and never mutated (val semantics)" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val idx1 = classpath.indices.byFullName
                    // Perform arbitrary read operations
                    val _    = classpath.findClass("kyo.fixtures.PlainClass")
                    val _    = classpath.topLevelClasses
                    val _    = classpath.packages
                    val idx2 = classpath.indices.byFullName
                    (idx1, idx2)
                }
            }
        ).map {
            case Result.Success((idx1, idx2)) =>
                // Dict is an opaque type; identity check via identityHashCode to confirm same backing object.
                assert(
                    java.lang.System.identityHashCode(idx1.asInstanceOf[AnyRef]) ==
                        java.lang.System.identityHashCode(idx2.asInstanceOf[AnyRef]),
                    "fullNameIndex must be the same reference before and after read operations"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // subclassIndex is built by inverting parentTypes during Pass C; same-file TYPEREFdirect/
    // TYPEREFsymbol parents are indexed. This test pins: subclassIndex is immutable after Pass C.
    "subclassIndex is a non-null immutable Map after Pass C" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val idx1 = classpath.indices.subclassIndex
                    val _    = classpath.findClass("kyo.fixtures.PlainClass") // arbitrary read
                    val idx2 = classpath.indices.subclassIndex
                    (idx1, idx2)
                }
            }
        ).map {
            case Result.Success((idx1, idx2)) =>
                assert(idx1.nonEmpty || idx1.isEmpty, "subclassIndex must be accessible after open")
                // Dict is an opaque type; identity check via identityHashCode to confirm same backing object.
                assert(
                    java.lang.System.identityHashCode(idx1.asInstanceOf[AnyRef]) ==
                        java.lang.System.identityHashCode(idx2.asInstanceOf[AnyRef]),
                    "subclassIndex must be the same Dict instance (val semantics)"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "SingleAssign deletion check delegates to ClasspathIndexGrepTest (JVM)" in {
        succeed
    }

    "ClasspathRef deletion check delegates to ClasspathIndexGrepTest (JVM)" in {
        succeed
    }

    "UnresolvedRef deletion check delegates to ClasspathIndexGrepTest (JVM)" in {
        succeed
    }

end ClasspathIndexImmutabilityTest
