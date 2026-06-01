package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for annotation wiring: cp.symbolsAnnotatedWith, sym.annotations, and annotation args.
  *
  * Pins findings F-G-001 and F-E-003. All leaves are PENDING until Phase 05 un-pends them by fixing `AstUnpickler.readModifiers` (replace
  * ANNOTATION skip with real decode) and wiring `FileResult.annotationsBySymbol` through `ClasspathOrchestrator.finalizeMerge`.
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
    "F-G-001 / INV-004 (Phase 05): cp.symbolsAnnotatedWith(scala.deprecated).size >= 5" in pending

    // F-G-001 leaf 2 (Phase 05): tailrec-found
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: calling cp.symbolsAnnotatedWith("scala.annotation.tailrec").size
    // Then: post-fix >= 1 (scala stdlib has multiple @tailrec methods);
    //       before fix size == 0 (same root cause as deprecated-found)
    // Pins: F-G-001
    "F-G-001 (Phase 05): cp.symbolsAnnotatedWith(scala.annotation.tailrec).size >= 1" in pending

    // F-G-001 leaf 3 (Phase 05): annotation-tycon-preserved
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        a known @deprecated stdlib symbol (e.g. scala.Predef.any2stringadd)
    // When: inspecting sym.annotations.head.tycon.show
    // Then: post-fix the string starts with "scala.deprecated" or "scala.annotation.Annotation";
    //       before fix sym.annotations was Chunk.empty so .head throws
    // Pins: F-G-001
    "F-G-001 (Phase 05): a @deprecated symbol carries annotation with correct tycon FQN" in pending

    // F-B-006 / F-G-001 leaf 4 (Phase 05): inline-annotation-tree-decoded
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        an @inline def from stdlib (e.g. scala.Predef.???)
    // When: walking foo.annotations.head.args (lazy Tree decode)
    // Then: post-fix Tree.Apply of the inline annotation constructor is present;
    //       before fix annotations was Chunk.empty so args had nothing to walk
    // Pins: F-B-006 unification with F-G-001
    "F-B-006 (Phase 05): an @inline method carries a decodable annotation args tree" in pending

end AnnotationFidelityTest
