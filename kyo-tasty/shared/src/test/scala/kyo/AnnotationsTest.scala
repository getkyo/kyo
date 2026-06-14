package kyo

import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId

/** Tasty.hasAnnotation, findAnnotation, symbolsAnnotatedWith.
  *
  * Uses synthetic classpath with owner chain for fully-qualified name resolution (same pattern as ClasspathAnnotatedTest).
  * symbol[0] = Package "scala", symbol[1] = Class "deprecated" (owner=scala), so fully-qualified name = "scala.deprecated".
  * symbol[2] = Method "m1" annotated with @deprecated, symbol[3] = Method "m2" not annotated.
  */
class AnnotationsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Build synthetic classpath with owner chain so fully-qualified name resolution works.
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
            SymbolId(0), // owned by scala package -> fully-qualified name = "scala.deprecated"
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
        val annotation      = Tasty.Annotation(deprecationType, Chunk.empty, Tasty.Name("scala.deprecated"))
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

    // classpath.hasAnnotation returns true for annotated symbol
    "classpath.hasAnnotation returns true for @deprecated method" in {
        buildAnnotatedClasspath.map { classpath =>
            val m1 = classpath.symbols(2) // annotated method (symbol[2])
            assert(classpath.hasAnnotation(m1, "scala.deprecated"), "hasAnnotation must return true for m1 annotated with @deprecated")
        }
    }

    // classpath.hasAnnotation returns false for non-annotated symbol
    "classpath.hasAnnotation returns false for non-annotated method" in {
        buildAnnotatedClasspath.map { classpath =>
            val m2 = classpath.symbols(3) // not annotated (symbol[3])
            assert(!classpath.hasAnnotation(m2, "scala.deprecated"), "hasAnnotation must return false for m2 not annotated")
        }
    }

    // Tasty.symbolsAnnotatedWith returns exactly the annotated symbols
    "Tasty.symbolsAnnotatedWith returns only annotated symbols" in {
        buildAnnotatedClasspath.map { classpath =>
            Tasty.withClasspath(classpath) {
                Tasty.symbolsAnnotatedWith("scala.deprecated").map { annotated =>
                    assert(annotated.size == 1, s"Expected exactly 1 @deprecated symbol, got ${annotated.size}")
                    assert(annotated.head.simpleName == "m1", s"Expected m1, got ${annotated.head.simpleName}")
                    succeed
                }
            }
        }
    }

end AnnotationsTest
