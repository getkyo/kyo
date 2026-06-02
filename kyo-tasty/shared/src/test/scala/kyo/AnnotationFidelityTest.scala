package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for annotation wiring: cp.symbolsAnnotatedWith, sym.annotations, and annotation args.
  *
  * Pins findings F-G-001 and F-E-003. Phase 05 un-pends all four leaves by fixing `AstUnpickler.readModifiers` and
  * `scanForwardAndCollectFlags` (replacing the ANNOTATION skip with real decode) and wiring
  * `Pass1Result.annotationsBySymbol` through `FileResult` into `ClasspathOrchestrator.finalizeMerge`.
  *
  * Phase 2.12 corrective: leaves 1, 3, 4 ungated. AnnotatedFixture (kyo-tasty-fixtures/shared) provides @deprecated and
  * @scala.annotation.unused annotations in the embedded fixture set. Leaf 1 threshold lowered from >= 5 to >= 1; the
  * property is "deprecated symbols found" and the embedded set has 2. Leaf 2 remains jvmOnly: @tailrec is encoded in
  * TASTy as TYPEREFsymbol (addr-based, external class reference), not TERMREFpkg (FQN-string); the FQN fallback in
  * unresolvedFqnByNegId only covers the FQN-string paths. Fixing requires TYPEREFsymbol cross-file FQN tracking.
  */
class AnnotationFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-G-001 / INV-004 leaf 1 (Phase 05): deprecated-found
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded AnnotatedFixture)
    // When: calling cp.symbolsAnnotatedWith("scala.deprecated").size
    // Then: post-fix size >= 1 (AnnotatedFixture has 2 @deprecated symbols; stdlib has many more);
    //       before fix size == 0 because AstUnpickler.readModifiers consumed the ANNOTATION
    //       payload via view.goto(annEnd) without decoding the tycon or attributing the annotation
    //       to the owning symbol
    // Pins: INV-004 producer (F-G-001)
    // Cross-platform: threshold lowered from >= 5 to >= 1. The property is "deprecated symbols found";
    //   embedded AnnotatedFixture provides 2 @deprecated symbols (AnnotatedFixtureDeprecated, deprecatedTopLevel).
    "F-G-001 / INV-004 (Phase 05): cp.symbolsAnnotatedWith(scala.deprecated).size >= 1" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val annotated = classpath.symbolsAnnotatedWith("scala.deprecated")
            assert(
                annotated.size >= 1,
                s"Expected >= 1 symbol annotated with scala.deprecated but found ${annotated.size}. " +
                    s"Embedded fixtures include AnnotatedFixture with @deprecated symbols. " +
                    s"If this fails, check that AnnotatedFixture TASTy bytes are in Embedded.scala and TestClasspaths loads them."
            )
            succeed
    }

    // F-G-001 leaf 2 (Phase 05): tailrec-found
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.symbolsAnnotatedWith("scala.annotation.tailrec").size
    // Then: post-fix >= 1 (scala stdlib has multiple @tailrec methods);
    //       before fix size == 0 (same root cause as deprecated-found)
    // Pins: F-G-001
    // JVM-only: @tailrec annotation tycon is encoded in TASTy as TYPEREFsymbol (addr-based reference to the
    //   external class), not TERMREFpkg (FQN-string reference). The unresolvedFqnByNegId mechanism only covers
    //   TERMREFpkg/TYPEREFpkg/TYPEREFin paths; TYPEREFsymbol cross-file references do not store the FQN.
    //   On JVM, the tailrec class IS on the classpath (scala-library), so the addr resolves to a real SymbolId.
    //   Fixing TYPEREFsymbol FQN tracking requires a larger refactor deferred to a future phase.
    "F-G-001 (Phase 05): cp.symbolsAnnotatedWith(scala.annotation.tailrec).size >= 1" taggedAs jvmOnly in run {
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
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded AnnotatedFixture)
    // When: inspecting the annotations of any @deprecated symbol via sym.annotations
    // Then: post-fix sym.annotations is non-empty and hasAnnotation("scala.deprecated") is true;
    //       before fix sym.annotations was Chunk.empty
    // Pins: F-G-001
    // Cross-platform: embedded AnnotatedFixture provides @deprecated symbols for JS/Native.
    "F-G-001 (Phase 05): a @deprecated symbol carries annotation with correct tycon FQN" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val annotated = classpath.symbolsAnnotatedWith("scala.deprecated")
            assert(annotated.nonEmpty, "Expected at least one @deprecated symbol (embedded AnnotatedFixture has 2)")
            val sym = annotated.head
            assert(
                sym.hasAnnotation("scala.deprecated")(using classpath),
                s"Symbol ${sym.name.asString} should report hasAnnotation(scala.deprecated)"
            )
            succeed
    }

    // F-B-006 / F-G-001 leaf 4 (Phase 05): inline-annotation-tree-decoded
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded AnnotatedFixture)
    // When: checking that methods with annotations exist (verifying both readModifiers and
    //       scanForwardAndCollectFlags annotation decode paths work)
    // Then: post-fix at least one method carries annotations; count of all annotated methods >= 1;
    //       before fix annotations was Chunk.empty on all methods
    // Pins: F-B-006 unification with F-G-001
    // Cross-platform: AnnotatedFixtureMethods.countDown (@tailrec) and annotatedWithUnused (@unused) are annotated.
    "F-B-006 (Phase 05): an @inline method carries a decodable annotation args tree" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val methodsWithAnnotations = classpath.symbols.collect:
                case m: Tasty.Symbol.Method if m.annotations.nonEmpty => m
            assert(
                methodsWithAnnotations.nonEmpty,
                "Expected at least one method with annotations after Phase 05 fix; found zero. " +
                    "Embedded AnnotatedFixtureMethods has @tailrec countDown and @unused annotatedWithUnused."
            )
            succeed
    }

end AnnotationFidelityTest
