package kyo

import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId

/** Tasty.hasAnnotation, findAnnotation, symbolsAnnotatedWith.
  *
  * Uses synthetic classpath with owner chain for FQN resolution (same pattern as ClasspathAnnotatedTest).
  * sym[0] = Package "scala", sym[1] = Class "deprecated" (owner=scala), so FQN = "scala.deprecated".
  * sym[2] = Method "m1" annotated with @deprecated, sym[3] = Method "m2" not annotated.
  */
class AnnotationsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Build synthetic classpath with owner chain so FQN resolution works.
    private def buildAnnotatedClasspath(using Frame): Tasty.Classpath < Sync =
        val scalaPackage = Tasty.Symbol.Package(
            SymbolId(0),
            Tasty.Name("scala"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Chunk.empty
        )
        val deprecatedClass = Tasty.Symbol.Class(
            SymbolId(1),
            Tasty.Name("deprecated"),
            Tasty.Flags.empty,
            SymbolId(0), // owned by scala package -> FQN = "scala.deprecated"
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        val deprecationType = Tasty.Type.Named(SymbolId(1))
        val annotation      = Tasty.Annotation(deprecationType, Chunk.empty)
        val annotatedMethod = Tasty.Symbol.Method(
            SymbolId(2),
            Tasty.Name("m1"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk(annotation),
            Maybe.Absent
        )
        val plainMethod = Tasty.Symbol.Method(
            SymbolId(3),
            Tasty.Name("m2"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(scalaPackage, deprecatedClass, annotatedMethod, plainMethod))
    end buildAnnotatedClasspath

    // Tasty.hasAnnotation returns true for annotated symbol
    "Tasty.hasAnnotation returns true for @deprecated method" in {
        buildAnnotatedClasspath.flatMap: cp =>
            val m1 = cp.symbols(2) // annotated method (sym[2])
            Tasty.withClasspath(cp):
                Tasty.hasAnnotation(m1, "scala.deprecated").map: has =>
                    assert(has, "hasAnnotation must return true for m1 annotated with @deprecated")
                    succeed
    }

    // Tasty.hasAnnotation returns false for non-annotated symbol
    "Tasty.hasAnnotation returns false for non-annotated method" in {
        buildAnnotatedClasspath.flatMap: cp =>
            val m2 = cp.symbols(3) // not annotated (sym[3])
            Tasty.withClasspath(cp):
                Tasty.hasAnnotation(m2, "scala.deprecated").map: has =>
                    assert(!has, "hasAnnotation must return false for m2 not annotated")
                    succeed
    }

    // Tasty.symbolsAnnotatedWith returns exactly the annotated symbols
    "Tasty.symbolsAnnotatedWith returns only annotated symbols" in {
        buildAnnotatedClasspath.flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.symbolsAnnotatedWith("scala.deprecated").map: annotated =>
                    assert(annotated.size == 1, s"Expected exactly 1 @deprecated symbol, got ${annotated.size}")
                    assert(annotated.head.simpleName == "m1", s"Expected m1, got ${annotated.head.simpleName}")
                    succeed
    }

end AnnotationsTest
