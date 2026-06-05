package kyo

import kyo.Tasty.SymbolId

/** Plan-mandated tests for Phase 07 (leaves 137-144): Symbol annotation queries.
  *
  * Covers: hasAnnotation, findAnnotation across symbol subtypes.
  *
  * Pins: INV-003.
  */
class SymbolAnnotationQueryTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makeAnnotClass(id: Int, pkgId: Int, simpleName: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(simpleName),
            Tasty.Flags.empty,
            SymbolId(pkgId),
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

    private def makePackage(id: Int, name: String): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name(name), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)

    private def makeMethod(
        id: Int,
        name: String,
        ownerId: Int,
        annots: Chunk[Tasty.Annotation]
    ): Tasty.Symbol.Method =
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
            annots,
            Maybe.Absent,
            Maybe.Absent
        )

    private def makeClass(
        id: Int,
        name: String,
        ownerId: Int,
        annots: Chunk[Tasty.Annotation] = Chunk.empty,
        jannots: Chunk[Tasty.JavaAnnotation] = Chunk.empty
    ): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            annots,
            jannots,
            Maybe.Absent
        )

    private def makeField(
        id: Int,
        name: String,
        ownerId: Int,
        jannots: Chunk[Tasty.JavaAnnotation]
    ): Tasty.Symbol.Field =
        Tasty.Symbol.Field(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            jannots
        )

    /** Build a classpath with: 0 -> Package "scala" 1 -> Class "deprecated" owned by pkg 0 (FQN = "scala.deprecated") 2 -> Class "inline"
      * owned by pkg 0 (FQN = "scala.inline") 3 -> Package "java.lang" 4 -> Class "Deprecated" owned by pkg 3 (FQN = "java.lang.Deprecated")
      * 5 -> Symbol.Method "foo" with @deprecated annotation 6 -> Symbol.Class "Bar" with @SerialVersionUID (using scala.deprecated slot for
      * simplicity) 7 -> Symbol.Field "f" with @java.lang.Deprecated 8 -> Symbol.Package "empty" 9 -> Symbol.TypeParam "T" 10 ->
      * Symbol.Method "inlined" with @scala.inline
      */
    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val scalaPkg    = makePackage(0, "scala")
            val deprecated  = makeAnnotClass(1, pkgId = 0, "deprecated")
            val inlineCls   = makeAnnotClass(2, pkgId = 0, "inline")
            val jlPkg       = makePackage(3, "java.lang")
            val jDeprecated = makeAnnotClass(4, pkgId = 3, "Deprecated")

            val scalaDeprecatedAnnot = Tasty.Annotation(
                Tasty.Type.Named(SymbolId(1)),
                Chunk.empty
            )
            val scalaInlineAnnot = Tasty.Annotation(
                Tasty.Type.Named(SymbolId(2)),
                Chunk.empty
            )
            val javaDeprecatedAnnot = Tasty.JavaAnnotation(
                annotationClass = jDeprecated,
                values = Chunk.empty
            )

            val methodFoo = makeMethod(5, "foo", ownerId = -1, Chunk(scalaDeprecatedAnnot))
            val classBar  = makeClass(6, "Bar", ownerId = -1, annots = Chunk(scalaDeprecatedAnnot))
            val fieldF    = makeField(7, "f", ownerId = -1, jannots = Chunk(javaDeprecatedAnnot))
            val emptyPkg  = makePackage(8, "empty")
            val typeParam = Tasty.Symbol.TypeParam(
                SymbolId(9),
                Tasty.Name("T"),
                Tasty.Flags.empty,
                SymbolId(-1),
                Maybe.Absent,
                Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
                Tasty.Variance.Invariant
            )
            val methodInlined = makeMethod(10, "inlined", ownerId = -1, Chunk(scalaInlineAnnot))

            Tasty.Classpath.make(
                symbols = Chunk(
                    scalaPkg,
                    deprecated,
                    inlineCls,
                    jlPkg,
                    jDeprecated,
                    methodFoo,
                    classBar,
                    fieldF,
                    emptyPkg,
                    typeParam,
                    methodInlined
                ),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(6)),
                packageIds = Chunk(SymbolId(0), SymbolId(3), SymbolId(8)),
                fqnIndex = Dict(
                    "scala.deprecated"     -> SymbolId(1),
                    "scala.inline"         -> SymbolId(2),
                    "java.lang.Deprecated" -> SymbolId(4)
                ),
                packageIndex = Dict(
                    "scala"     -> SymbolId(0),
                    "java.lang" -> SymbolId(3),
                    "empty"     -> SymbolId(8)
                ),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    // ── Leaf 137: hasAnnotation-scala-on-method ────────────────────────────────
    // Given: Symbol.Method carrying @deprecated in its annotations.
    // When: m.hasAnnotation("scala.deprecated").
    // Then: returns true.
    // Pins: INV-003
    "Leaf 137: hasAnnotation returns true for Scala annotation on method" in run {
        buildFixture.flatMap: cp =>
            val m = cp.symbol(SymbolId(5)).asInstanceOf[Tasty.Symbol.Method]
            Tasty.withClasspath(cp):
                Tasty.hasAnnotation(m, "scala.deprecated").map: has =>
                    assert(has)
                    succeed
    }

    // ── Leaf 138: hasAnnotation-scala-on-class ─────────────────────────────────
    // Given: Symbol.Class carrying @deprecated in its annotations.
    // When: c.hasAnnotation("scala.deprecated").
    // Then: returns true.
    // Pins: INV-003
    "Leaf 138: hasAnnotation returns true for Scala annotation on class" in run {
        buildFixture.flatMap: cp =>
            val c = cp.symbol(SymbolId(6)).asInstanceOf[Tasty.Symbol.Class]
            Tasty.withClasspath(cp):
                Tasty.hasAnnotation(c, "scala.deprecated").map: has =>
                    assert(has)
                    succeed
    }

    // ── Leaf 139: hasAnnotation-java-on-field ─────────────────────────────────
    // Given: Symbol.Field with @java.lang.Deprecated in javaAnnotations.
    // When: f.hasAnnotation("java.lang.Deprecated").
    // Then: returns true.
    // Pins: INV-003
    "Leaf 139: hasAnnotation returns true for Java annotation on field" in run {
        buildFixture.flatMap: cp =>
            val f = cp.symbol(SymbolId(7)).asInstanceOf[Tasty.Symbol.Field]
            Tasty.withClasspath(cp):
                Tasty.hasAnnotation(f, "java.lang.Deprecated").map: has =>
                    assert(has)
                    succeed
    }

    // ── Leaf 140: hasAnnotation-on-package-returns-false ──────────────────────
    // Given: a Symbol.Package (which carries no annotation fields).
    // When: p.hasAnnotation("anything").
    // Then: returns false.
    // Pins: INV-003
    "Leaf 140: hasAnnotation returns false for Package" in run {
        buildFixture.flatMap: cp =>
            val p = cp.symbol(SymbolId(8)).asInstanceOf[Tasty.Symbol.Package]
            Tasty.hasAnnotation(p, "anything").map: has =>
                assert(!has)
                succeed
    }

    // ── Leaf 141: hasAnnotation-on-typeparam-returns-false ────────────────────
    // Given: a Symbol.TypeParam.
    // When: t.hasAnnotation("scala.deprecated").
    // Then: returns false.
    // Pins: INV-003
    "Leaf 141: hasAnnotation returns false for TypeParam" in run {
        buildFixture.flatMap: cp =>
            val t = cp.symbol(SymbolId(9)).asInstanceOf[Tasty.Symbol.TypeParam]
            Tasty.hasAnnotation(t, "scala.deprecated").map: has =>
                assert(!has)
                succeed
    }

    // ── Leaf 142: findAnnotation-scala-present ─────────────────────────────────
    // Given: Symbol.Method with one @scala.inline annotation.
    // When: m.findAnnotation("scala.inline").
    // Then: returns Maybe.Present(a) where a.isInstanceOf[Tasty.Annotation].
    // Pins: INV-003
    "Leaf 142: findAnnotation returns Present Annotation for matching Scala annotation" in run {
        buildFixture.flatMap: cp =>
            val m = cp.symbol(SymbolId(10)).asInstanceOf[Tasty.Symbol.Method]
            Tasty.withClasspath(cp):
                Tasty.findAnnotation(m, "scala.inline").map:
                    case Maybe.Present(a: Tasty.Annotation) =>
                        assert(
                            a.annotationType == Tasty.Type.Named(SymbolId(2)),
                            s"Expected Named(SymbolId(2)) but got ${a.annotationType}"
                        )
                        assert(a.arguments.isEmpty, s"Expected empty arguments but got ${a.arguments}")
                        succeed
                    case other =>
                        fail(s"Expected Present Tasty.Annotation but got $other")
    }

    // ── Leaf 143: findAnnotation-java-present ──────────────────────────────────
    // Given: Symbol.Field with one @java.lang.Deprecated.
    // When: f.findAnnotation("java.lang.Deprecated").
    // Then: returns Maybe.Present(a) where a.isInstanceOf[Tasty.JavaAnnotation].
    // Pins: INV-003
    "Leaf 143: findAnnotation returns Present JavaAnnotation for matching Java annotation" in run {
        buildFixture.flatMap: cp =>
            val f = cp.symbol(SymbolId(7)).asInstanceOf[Tasty.Symbol.Field]
            Tasty.withClasspath(cp):
                Tasty.findAnnotation(f, "java.lang.Deprecated").map:
                    case Maybe.Present(a: Tasty.JavaAnnotation) =>
                        assert(
                            a.annotationClass.id == SymbolId(4),
                            s"Expected annotationClass id SymbolId(4) but got ${a.annotationClass.id}"
                        )
                        assert(a.values.isEmpty, s"Expected empty values but got ${a.values}")
                        succeed
                    case other =>
                        fail(s"Expected Present Tasty.JavaAnnotation but got $other")
    }

    // ── Leaf 144: findAnnotation-absent ────────────────────────────────────────
    // Given: any symbol with no matching annotation.
    // When: s.findAnnotation("missing.Anno").
    // Then: returns Maybe.Absent.
    // Pins: INV-003
    "Leaf 144: findAnnotation returns Absent when annotation not present" in run {
        buildFixture.flatMap: cp =>
            val m = cp.symbol(SymbolId(5)).asInstanceOf[Tasty.Symbol.Method]
            Tasty.findAnnotation(m, "missing.Anno").map: result =>
                assert(!result.isDefined, s"Expected Absent but got $result")
                succeed
    }

end SymbolAnnotationQueryTest
