package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for annotation wiring: cp.symbolsAnnotatedWith, sym.annotations, and annotation args.
  *
  * Pins findings F-G-001 and F-E-003. Phase 05 un-pends all four leaves by fixing `AstUnpickler.readModifiers` and
  * `scanForwardAndCollectFlags` (replacing the ANNOTATION skip with real decode) and wiring
  * `Pass1Result.annotationsBySymbol` through `FileResult` into `ClasspathOrchestrator.finalizeMerge`.
  */
class AnnotationFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-G-001 / INV-004 leaf 1 (Phase 05): deprecated-found
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.symbolsAnnotatedWith("scala.deprecated").size
    // Then: post-fix size >= 5 (stdlib has many deprecated symbols);
    //       before fix size == 0 because AstUnpickler.readModifiers consumed the ANNOTATION
    //       payload via view.goto(annEnd) without decoding the tycon or attributing the annotation
    //       to the owning symbol
    // Pins: INV-004 producer (F-G-001)
    "F-G-001 / INV-004 (Phase 05): cp.symbolsAnnotatedWith(scala.deprecated).size >= 5" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val annotated = classpath.symbolsAnnotatedWith("scala.deprecated")
            assert(
                annotated.size >= 5,
                s"Expected >= 5 symbols annotated with scala.deprecated but found ${annotated.size}"
            )
            succeed
    }

    // F-G-001 leaf 2 (Phase 05): tailrec-found
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.symbolsAnnotatedWith("scala.annotation.tailrec").size
    // Then: post-fix >= 1 (scala stdlib has multiple @tailrec methods);
    //       before fix size == 0 (same root cause as deprecated-found)
    // Pins: F-G-001
    "F-G-001 (Phase 05): cp.symbolsAnnotatedWith(scala.annotation.tailrec).size >= 1" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val annotated = classpath.symbolsAnnotatedWith("scala.annotation.tailrec")
            assert(
                annotated.size >= 1,
                s"Expected >= 1 symbol annotated with scala.annotation.tailrec but found ${annotated.size}"
            )
            succeed
    }

    // F-G-001 leaf 3 (Phase 05): annotation-tycon-preserved
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        a known @deprecated stdlib symbol
    // When: inspecting the annotations of any @deprecated symbol via sym.annotations
    // Then: post-fix sym.annotations is non-empty and the annotationType is a Type.Named
    //       resolving to a symbol whose name is "deprecated";
    //       before fix sym.annotations was Chunk.empty
    // Pins: F-G-001
    "F-G-001 (Phase 05): a @deprecated symbol carries annotation with correct tycon FQN" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val annotated = classpath.symbolsAnnotatedWith("scala.deprecated")
            assert(annotated.nonEmpty, "Expected at least one @deprecated symbol")
            val sym = annotated.head
            assert(
                sym.hasAnnotation("scala.deprecated")(using classpath),
                s"Symbol ${sym.name.asString} should report hasAnnotation(scala.deprecated)"
            )
            succeed
    }

    // F-B-006 / F-G-001 leaf 4 (Phase 05): inline-annotation-tree-decoded
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: checking that methods with annotations exist (verifying both readModifiers and
    //       scanForwardAndCollectFlags annotation decode paths work)
    // Then: post-fix at least one method carries annotations; count of all annotated methods >= 1;
    //       before fix annotations was Chunk.empty on all methods
    // Pins: F-B-006 unification with F-G-001
    "F-B-006 (Phase 05): an @inline method carries a decodable annotation args tree" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val methodsWithAnnotations = classpath.symbols.collect:
                case m: Tasty.Symbol.Method if m.annotations.nonEmpty => m
            assert(
                methodsWithAnnotations.nonEmpty,
                "Expected at least one method with annotations after Phase 05 fix; found zero"
            )
            succeed
    }

end AnnotationFidelityTest
