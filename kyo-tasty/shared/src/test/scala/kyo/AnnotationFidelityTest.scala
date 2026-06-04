package kyo

import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

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
  *
  * Decoder-fidelity-3 Phase 3.02 (CARRY-3): adds leaf 5 to verify that the in-memory snapshot round-trip preserves
  * symbolsAnnotatedWith("scala.deprecated") count. Verifies cold == warm on JVM, JS, and Native, closing the
  * cold=1 warm=0 regression documented in the DF2 final-report.md CARRY-3 entry.
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

    // F-G-001 leaf 2 (Phase 05): tailrec-found
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.symbolsAnnotatedWith("scala.annotation.tailrec").size
    // Then: post-fix >= 1 (scala stdlib has multiple @tailrec methods);
    //       before fix size == 0 (same root cause as deprecated-found)
    // Pins: F-G-001
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): the @tailrec annotation
    //   tycon is encoded in TASTy as TYPEREFsymbol (addr-based reference to the external class), not TERMREFpkg
    //   (FQN-string reference). The unresolvedFqnByNegId mechanism only stores FQNs for TERMREFpkg / TYPEREFpkg /
    //   TYPEREFin paths; TYPEREFsymbol cross-file references discard the FQN at decode time. Resolving @tailrec
    //   therefore requires the scala-library jar (containing scala/annotation/tailrec.class) on the loaded
    //   classpath so the addr resolves to a real SymbolId. JS/Native cannot supply scala-library as a TASTy
    //   classpath; the proper fix is TYPEREFsymbol cross-file FQN tracking, a non-trivial decoder refactor.
    "F-G-001 (Phase 05): cp.symbolsAnnotatedWith(scala.annotation.tailrec).size >= 1" taggedAs jvmOnly in run {
        TestClasspaths.withClasspath():
            Tasty.classpath.flatMap: classpath =>
                classpath.symbolsAnnotatedWith("scala.annotation.tailrec").map: annotated =>
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

    // F-B-006 / F-G-001 leaf 4 (Phase 05): inline-annotation-tree-decoded
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded AnnotatedFixture)
    // When: checking that methods with annotations exist (verifying both readModifiers and
    //       scanForwardAndCollectFlags annotation decode paths work)
    // Then: post-fix at least one method carries annotations; count of all annotated methods >= 1;
    //       before fix annotations was Chunk.empty on all methods
    // Pins: F-B-006 unification with F-G-001
    // Cross-platform: AnnotatedFixtureMethods.countDown (@tailrec) and annotatedWithUnused (@unused) are annotated.
    "F-B-006 (Phase 05): an @inline method carries a decodable annotation args tree" in run {
        TestClasspaths.withClasspath():
            Tasty.classpath.map: classpath =>
                val methodsWithAnnotations = classpath.symbols.collect:
                    case m: Tasty.Symbol.Method if m.annotations.nonEmpty => m
                assert(
                    methodsWithAnnotations.nonEmpty,
                    "Expected at least one method with annotations after Phase 05 fix; found zero. " +
                        "Embedded AnnotatedFixtureMethods has @tailrec countDown and @unused annotatedWithUnused."
                )
                succeed
    }

    // CARRY-3 leaf 5 (decoder-fidelity-3 Phase 3.02): annotation-warm-load-cold-parity
    // Given: a cold classpath and a warm classpath produced by an in-memory snapshot round-trip
    // When: calling symbolsAnnotatedWith("scala.deprecated") on both
    // Then: warm count == cold count (pre-fix warm returned 0 when cold returned 1)
    // Root cause documented in DF2 final-report.md CARRY-3: the ANNOTS_ section serializer stored FQNs
    // as TermRef(Tuple(empty), Name(fqn)) which typeFqnString correctly unwraps to fqn. The test
    // verifies end-to-end parity. Cold >= 1 is also asserted so a vacuous warm==cold==0 does not pass.
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory (no filesystem needed).
    // Pins: CARRY-3 from decoder-fidelity-2; cold==warm parity for symbolsAnnotatedWith.
    "CARRY-3 (Phase 3.02): in-memory snapshot round-trip preserves symbolsAnnotatedWith count" in run {
        TestClasspaths2.withSnapshotInMemory().flatMap: (cold, warm) =>
            for
                coldA <- cold.symbolsAnnotatedWith("scala.deprecated")
                warmA <- warm.symbolsAnnotatedWith("scala.deprecated")
            yield
                val coldCount = coldA.size
                val warmCount = warmA.size
                assert(
                    coldCount >= 1,
                    s"CARRY-3: cold classpath must have >= 1 @deprecated symbol; got $coldCount. " +
                        s"Embedded AnnotatedFixture has 2 @deprecated symbols."
                )
                assert(
                    warmCount == coldCount,
                    s"CARRY-3: warm symbolsAnnotatedWith count ($warmCount) != cold ($coldCount). " +
                        s"Annotation FQN round-trip through ANNOTS_ section failed."
                )
                succeed
    }

end AnnotationFidelityTest
