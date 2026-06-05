package kyo

import kyo.Tasty.SymbolId

/** Phase 08 followup for W-06-02: exercises the Java-annotation path (`javaAnnotations`) of `Classpath.symbolsAnnotatedWith`.
  *
  * The existing `ClasspathAnnotatedTest` (leaf 128) only covers the Scala-annotation path. This test covers the Java annotation branch of
  * `symbolsAnnotatedWith` at Tasty.scala:annotationFqnMatches is not exercised for Java; the Java path is tested here.
  *
  * Fixture layout: 0 -> Symbol.Class "Deprecated" (the Java annotation class itself; no annotations; fqn "java.lang.Deprecated") 1 ->
  * Symbol.Class "A" with a JavaAnnotation pointing to symbol 0 2 -> Symbol.Class "B" with no JavaAnnotation
  *
  * After calling symbolsAnnotatedWith("java.lang.Deprecated"), only symbol 1 ("A") must be returned.
  *
  * Pins: INV-005.
  */
class ClasspathAnnotatedJavaTest extends Test:

    import AllowUnsafe.embrace.danger

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
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
                Chunk.empty,
                Maybe.Absent
            )
            // Symbol 3: class "A" carrying a JavaAnnotation whose annotationClass is symbol 2
            val javaAnnot = Tasty.JavaAnnotation(annotCls, Chunk.empty)
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
                javaAnnotations = Chunk(javaAnnot),
                body = Maybe.Absent
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
                Chunk.empty,
                Maybe.Absent
            )
            Tasty.Classpath.make(
                symbols = Chunk(pkgJava, pkgLang, annotCls, clsA, clsB),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk(SymbolId(0), SymbolId(1)),
                fqnIndex = Dict("A" -> SymbolId(3), "B" -> SymbolId(4)),
                packageIndex = Dict("java" -> SymbolId(0), "java.lang" -> SymbolId(1)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    // Given: fixture with class A carrying JavaAnnotation(Deprecated, _) and class B with no annotation
    // When: symbolsAnnotatedWith("java.lang.Deprecated")
    // Then: returns exactly class A (the Java-annotation path is exercised)
    "ClasspathAnnotatedJavaTest: symbolsAnnotatedWith returns class A via Java annotation path" in run {
        buildFixture.flatMap: cp =>
            cp.symbolsAnnotatedWith("java.lang.Deprecated").map: result =>
                assert(
                    result.length == 1,
                    s"Expected 1 symbol annotated with java.lang.Deprecated via Java path, got ${result.length}: ${result.map(_.name.asString).mkString(", ")}"
                )
                assert(result(0).name.asString == "A", s"Expected annotated symbol to be 'A', got '${result(0).name.asString}'")
                succeed
    }

end ClasspathAnnotatedJavaTest
