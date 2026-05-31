package kyo

/** Tests for Tasty.Annotation public API surface.
  *
  * Phase 13 (INV: T1). Covers the synthetic Annotation.apply factory and Annotation.unapply extractor.
  *
  * makeNamed is inherited from TastyTestSupport (Phase 21g deduplication).
  */
class TastyAnnotationTest extends Test with TastyTestSupport:

    import AllowUnsafe.embrace.danger

    // Test 6 (INV: T1, Annotation): synthetic factory and unapply extractor work correctly.
    // plan: phase-05; show now takes (using cp: Classpath); name rendering deferred to Phase 09.
    "Annotation synthetic factory: annotationType.show, argsPickle.isEmpty, unapply" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val deprecatedType    = makeNamed("scala.deprecated")
            val a                 = Tasty.Annotation(deprecatedType, Chunk.empty)
            // plan: phase-05; show renders id values (not names) until Phase 09.
            val showStr = a.annotationType.show
            assert(
                showStr.nonEmpty,
                s"Expected non-empty show but got '$showStr'"
            )
            assert(
                a.argsPickle.isEmpty,
                s"Expected argsPickle.isEmpty == true but argsPickle had ${a.argsPickle.length} bytes"
            )
            val unapplyResult = Maybe.fromOption(Tasty.Annotation.unapply(a))
            unapplyResult match
                case Present((tpe, pickle)) =>
                    assert(
                        tpe eq a.annotationType,
                        "Expected unapply to return the same annotationType reference"
                    )
                    assert(
                        pickle.isEmpty,
                        s"Expected unapply argsPickle to be empty but had ${pickle.length} bytes"
                    )
                case Absent =>
                    fail("Expected Present from unapply but got Absent")
            end match
    }

    // Phase 17 Test 3 (INV-014): synthetic factory with empty argsPickle returns Tree.Unknown(-1, 0).
    // Given: a = Tasty.Annotation(Type.Named(id), Chunk.empty) (null decode context via public factory).
    // When: a.args is evaluated.
    // Then: returns Tree.Unknown(-1, 0); NOT Abort.fail(NotImplemented).
    // Pins: INV-014.
    "Phase17-3: Annotation(type, Chunk.empty).args returns Tree.Unknown(-1,0), not NotImplemented" in run {
        val deprecatedType = makeNamed("scala.deprecated")
        val a              = Tasty.Annotation(deprecatedType, Chunk.empty)
        Abort.run[TastyError](a.args).map:
            case Result.Success(Tasty.Tree.Unknown(-1, 0)) =>
                succeed
            case Result.Success(other) =>
                fail(s"Expected Tree.Unknown(-1,0) but got $other")
            case Result.Failure(TastyError.NotImplemented(msg)) =>
                fail(s"Expected Tree.Unknown(-1,0) but got NotImplemented: $msg")
            case Result.Failure(e) =>
                fail(s"Expected Tree.Unknown(-1,0) but got failure $e")
            case Result.Panic(t) =>
                throw t
    }

end TastyAnnotationTest
