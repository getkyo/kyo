package kyo

import kyo.Tasty.SymbolId

/** Plan-mandated tests for Phase 06 (leaf 128): Classpath.symbolsAnnotatedWith.
  *
  * Anti-reward-hacking: fixture includes BOTH annotated and non-annotated symbols. The test asserts that ONLY the annotated ones are
  * returned; a vacuous "if nonEmpty succeed" pattern would not verify correctness.
  *
  * Fixture layout: 0 -> Class "deprecated" fqn "scala.deprecated" (the annotation class itself, no annotation) 1 -> Method "m1" annotated
  * with scala.deprecated (id 0) 2 -> Val "v1" annotated with scala.deprecated (id 0) 3 -> Class "PlainA" NOT annotated 4 -> Method "m2" NOT
  * annotated
  *
  * fqnIndex: "scala.deprecated" -> SymbolId(0)
  *
  * After calling symbolsAnnotatedWith("scala.deprecated"), only symbols at id 1 and 2 must be returned.
  *
  * Pins: INV-005.
  */
class ClasspathAnnotatedTest extends Test:

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString

    private def makeAnnotClass(id: Int, name: String, fqnName: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
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

    private def makeMethod(id: Int, name: String, ownerId: Int, anns: Chunk[Tasty.Annotation]): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            anns,
            Maybe.Absent
        )

    private def makeVal(id: Int, name: String, ownerId: Int, anns: Chunk[Tasty.Annotation]): Tasty.Symbol.Val =
        Tasty.Symbol.Val(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            anns
        )

    private def makeClass(id: Int, name: String, anns: Chunk[Tasty.Annotation]): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            anns,
            Chunk.empty
        )

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            // Symbol layout (index == id.value) -- owner chain must resolve "scala.deprecated":
            //   0 -> Package "scala"       (ownerId = -1)
            //   1 -> Class   "deprecated"  (ownerId = 0, so fullName = "scala.deprecated")
            //   2 -> Method  "m1"          (ownerId = 4, annotated with ann referencing id 1)
            //   3 -> Val     "v1"          (ownerId = 4, annotated with ann referencing id 1)
            //   4 -> Class   "PlainA"      (ownerId = -1, no annotation)
            //   5 -> Method  "m2"          (ownerId = 4, no annotation)

            val scalaPkg        = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("scala"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
            val deprecatedClass = makeAnnotClass(1, "deprecated", "scala.deprecated")
            // We build it with ownerId pointing to the scala package (id 0)
            // but makeAnnotClass ignores ownerId, so rebuild inline:
            val deprecatedClassWithOwner = Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("deprecated"),
                Tasty.Flags.empty,
                SymbolId(0),
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

            // Annotation: annotationType = Type.Named(SymbolId(1)) -> fullName resolves to "scala.deprecated"
            val ann = Tasty.Annotation(Tasty.Type.Named(SymbolId(1)), Chunk.empty)

            // id 2: method annotated with @deprecated
            val annotatedMethod = makeMethod(2, "m1", ownerId = 4, Chunk(ann))

            // id 3: val annotated with @deprecated
            val annotatedVal = makeVal(3, "v1", ownerId = 4, Chunk(ann))

            // id 4: a plain class WITHOUT annotation
            val plainClass = makeClass(4, "PlainA", Chunk.empty)

            // id 5: an unannotated method
            val plainMethod = makeMethod(5, "m2", ownerId = 4, Chunk.empty)

            Tasty.Classpath.make(
                symbols = Chunk(scalaPkg, deprecatedClassWithOwner, annotatedMethod, annotatedVal, plainClass, plainMethod),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(1), SymbolId(4)),
                packageIds = Chunk(SymbolId(0)),
                fqnIndex = Dict("scala.deprecated" -> SymbolId(1), "PlainA" -> SymbolId(4)),
                packageIndex = Dict("scala" -> SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    // ── Leaf 128: symbolsAnnotatedWith ────────────────────────────────────────
    // Given: fixture with @deprecated def m1 and @deprecated val v1, plus unannotated PlainA and m2.
    // When: cp.symbolsAnnotatedWith("scala.deprecated")
    // Then: Chunk[Symbol] of size 2; contains m1 and v1; excludes PlainA, deprecatedClass, and m2.
    // Pins: INV-005
    "Leaf 128: symbolsAnnotatedWith returns only symbols bearing the given annotation" in run {
        buildFixture.flatMap: cp =>
            cp.symbolsAnnotatedWith("scala.deprecated").map: annotated =>
                assert(
                    annotated.size == 2,
                    s"Expected 2 annotated symbols but got ${annotated.size}: ${annotated.map(_.name.asString)}"
                )
                val names = annotated.map(_.name.asString).toSeq.toSet
                assert(names.contains("m1"), s"Expected m1 in annotated set but got: $names")
                assert(names.contains("v1"), s"Expected v1 in annotated set but got: $names")
                assert(!names.contains("PlainA"), s"PlainA must NOT be in annotated set: $names")
                assert(!names.contains("m2"), s"m2 must NOT be in annotated set: $names")
                assert(!names.contains("deprecated"), s"deprecated class itself must NOT be in annotated set: $names")
                succeed
    }

end ClasspathAnnotatedTest
