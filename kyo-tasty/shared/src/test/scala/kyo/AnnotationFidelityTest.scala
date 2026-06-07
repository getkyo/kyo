package kyo

import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Fidelity tests for annotation wiring: cp.symbolsAnnotatedWith, sym.annotations, and annotation args.
  *
  * Exercises AstUnpickler.readModifiers and scanForwardAndCollectFlags (which decode the ANNOTATION
  * section) and the wiring of Pass1Result.annotationsBySymbol through FileResult into
  * ClasspathOrchestrator.finalizeMerge.
  *
  * AnnotatedFixture provides @deprecated and @scala.annotation.unused annotations in the embedded
  * fixture set. The snapshot round-trip test verifies symbolsAnnotatedWith count is preserved
  * cold == warm on JVM, JS, and Native.
  */
class AnnotationFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "cp.symbolsAnnotatedWith(scala.deprecated).size >= 1" in {
        TestClasspaths.withClasspath():
            Tasty.classpath.flatMap: classpath =>
                classpath.symbolsAnnotatedWith("scala.deprecated").map: annotated =>
                    assert(
                        annotated.size >= 1,
                        s"Expected >= 1 symbol annotated with scala.deprecated but found ${annotated.size}. " +
                            s"Embedded fixtures include AnnotatedFixture with @deprecated symbols. " +
                            s"If this fails, check that AnnotatedFixture TASTy bytes are in Embedded.scala and TestClasspaths loads them."
                    )
                    succeed
    }

    "a @deprecated symbol carries annotation with correct tycon FQN" in {
        TestClasspaths.withClasspath():
            Tasty.classpath.flatMap: classpath =>
                classpath.symbolsAnnotatedWith("scala.deprecated").flatMap: annotated =>
                    assert(annotated.nonEmpty, "Expected at least one @deprecated symbol (embedded AnnotatedFixture has 2)")
                    val sym = annotated.head
                    Tasty.hasAnnotation(sym, "scala.deprecated").map: has =>
                        assert(
                            has,
                            s"Symbol ${sym.name.asString} should report hasAnnotation(scala.deprecated)"
                        )
                        succeed
    }

    "an @inline method carries a decodable annotation args tree" in {
        TestClasspaths.withClasspath():
            Tasty.classpath.map: classpath =>
                val methodsWithAnnotations = classpath.symbols.collect:
                    case m: Tasty.Symbol.Method if m.annotations.nonEmpty => m
                assert(
                    methodsWithAnnotations.nonEmpty,
                    "Expected at least one method with annotations; found zero. " +
                        "Embedded AnnotatedFixtureMethods has @tailrec countDown and @unused annotatedWithUnused."
                )
                succeed
    }

    "in-memory snapshot round-trip preserves symbolsAnnotatedWith count" in {
        TestClasspaths2.withSnapshotInMemory().flatMap: (cold, warm) =>
            for
                coldA <- cold.symbolsAnnotatedWith("scala.deprecated")
                warmA <- warm.symbolsAnnotatedWith("scala.deprecated")
            yield
                val coldCount = coldA.size
                val warmCount = warmA.size
                assert(
                    coldCount >= 1,
                    s"cold classpath must have >= 1 @deprecated symbol; got $coldCount. " +
                        s"Embedded AnnotatedFixture has 2 @deprecated symbols."
                )
                assert(
                    warmCount == coldCount,
                    s"warm symbolsAnnotatedWith count ($warmCount) != cold ($coldCount). " +
                        s"Annotation FQN round-trip through ANNOTS_ section failed."
                )
                succeed
    }

end AnnotationFidelityTest
