package kyo

import kyo.internal.tasty.query.ClasspathRef

/** Tests for Tasty.Annotation public API surface.
  *
  * Phase 13 (INV: T1). Covers the synthetic Annotation.apply factory and Annotation.unapply extractor.
  */
class TastyAnnotationTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Build a synthetic Named type for the given dotted FQN. */
    private def makeNamed(fqn: String): Tasty.Type.Named =
        val parts = fqn.split("\\.").toList
        val root = Tasty.Symbol.make(
            Tasty.SymbolKind.Package,
            Tasty.Flags.empty,
            Tasty.Name(""),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val finalSym = parts.foldLeft(root) { (owner, part) =>
            Tasty.Symbol.make(
                Tasty.SymbolKind.Class,
                Tasty.Flags.empty,
                Tasty.Name(part),
                owner,
                new ClasspathRef,
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
        }
        Tasty.Type.Named(finalSym)
    end makeNamed

    // Test 6 (INV: T1, Annotation): synthetic factory and unapply extractor work correctly.
    // Given: deprecatedSym with fullName "scala.deprecated"; a = Annotation.apply(Named(deprecatedSym), Chunk.empty).
    // When: a.annotationType.show, a.argsPickle.isEmpty, Annotation.unapply(a).
    // Then: annotationType.show == "scala.deprecated"; argsPickle.isEmpty == true;
    //       unapply result wrapped in Maybe equals Present((a.annotationType, Chunk.empty)).
    // Pins: T1 (Annotation public factory and extractor coverage).
    "Annotation synthetic factory: annotationType.show, argsPickle.isEmpty, unapply" in {
        val deprecatedType = makeNamed("scala.deprecated")
        val a              = Tasty.Annotation(deprecatedType, Chunk.empty)
        assert(
            a.annotationType.show == "scala.deprecated",
            s"Expected annotationType.show == 'scala.deprecated' but got '${a.annotationType.show}'"
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
    // Given: a = Tasty.Annotation(Type.Named(sym), Chunk.empty) (null decode context via public factory).
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
