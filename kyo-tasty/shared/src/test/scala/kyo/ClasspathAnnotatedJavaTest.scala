package kyo

import kyo.Tasty.SymbolId

/** Tests for the Java-annotation path of Classpath.symbolsAnnotatedWith via the javaAnnotations field.
  *
  * Fixture layout: symbol 0 = Deprecated (the annotation class itself; no annotations),
  * symbol 1 = class "A" carrying a JavaAnnotation to symbol 0, symbol 2 = class "B" with none.
  * Only symbol 1 ("A") must be returned by symbolsAnnotatedWith("java.lang.Deprecated").
  */
class ClasspathAnnotatedJavaTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            // Owner chain for "java.lang.Deprecated": pkg "java"(0) -> pkg "lang"(1) -> cls "Deprecated"(2)
            // Symbol 0: Package "java" (root owner)
            val pkgJava =
                Tasty.Symbol.Package(SymbolId(0), Tasty.Name("java"), Tasty.Flags.empty, SymbolId(-1), Chunk(SymbolId(1)))
            // Symbol 1: Package "lang" (owner = java)
            val pkgLang =
                Tasty.Symbol.Package(SymbolId(1), Tasty.Name("lang"), Tasty.Flags.empty, SymbolId(0), Chunk(SymbolId(2)))
            // Symbol 2: Class "Deprecated" (owner = lang); fullName = "java.lang.Deprecated"
            val annotCls = Tasty.Symbol.Class(
                SymbolId(2),
                Tasty.Name("Deprecated"),
                Tasty.Flags.empty,
                SymbolId(1),
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
            // Symbol 3: class "A" carrying a JavaAnnotation whose annotationClass is symbol 2
            val javaAnnot = Tasty.Java.Annotation(annotCls, Chunk.empty, Tasty.Name("java.lang.Deprecated"))
            val clsA = Tasty.Symbol.Class(
                SymbolId(3),
                Tasty.Name("A"),
                Tasty.Flags.empty,
                SymbolId(-1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                annotations = Chunk.empty,
                javaAnnotations = Chunk(javaAnnot)
            )
            // Symbol 4: class "B" with no Java annotation
            val clsB = Tasty.Symbol.Class(
                SymbolId(4),
                Tasty.Name("B"),
                Tasty.Flags.empty,
                SymbolId(-1),
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
            Tasty.Classpath.make(
                symbols = Chunk(pkgJava, pkgLang, annotCls, clsA, clsB),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk(SymbolId(0), SymbolId(1)),
                fullNameIndex = Dict("A" -> SymbolId(3), "B" -> SymbolId(4)),
                packageIndex = Dict("java" -> SymbolId(0), "java.lang" -> SymbolId(1)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    "ClasspathAnnotatedJavaTest: symbolsAnnotatedWith returns class A via Java annotation path" in {
        buildFixture.map { classpath =>
            val result = classpath.symbolsAnnotatedWith("java.lang.Deprecated")
            assert(
                result.length == 1,
                s"Expected 1 symbol annotated with java.lang.Deprecated via Java path, got ${result.length}: ${result.map(_.name.asString).mkString(", ")}"
            )
            assert(result(0).name.asString == "A", s"Expected annotated symbol to be 'A', got '${result(0).name.asString}'")
            succeed
        }
    }

end ClasspathAnnotatedJavaTest
