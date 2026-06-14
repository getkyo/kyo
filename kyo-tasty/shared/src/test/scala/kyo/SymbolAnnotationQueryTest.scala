package kyo

import kyo.Tasty.SymbolId

/** Symbol annotation queries.
  *
  * Covers: hasAnnotation, findAnnotation across symbol subtypes.
  */
class SymbolAnnotationQueryTest extends kyo.test.Test[Any]:

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
            Chunk.empty
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
            Maybe.Absent
        )

    private def makeClass(
        id: Int,
        name: String,
        ownerId: Int,
        annots: Chunk[Tasty.Annotation] = Chunk.empty,
        jannots: Chunk[Tasty.Java.Annotation] = Chunk.empty
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
            jannots
        )

    private def makeField(
        id: Int,
        name: String,
        ownerId: Int,
        jannots: Chunk[Tasty.Java.Annotation]
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

    /** Build a classpath with: 0 -> Package "scala" 1 -> Class "deprecated" owned by pkg 0 (fully-qualified name = "scala.deprecated") 2 -> Class "inline"
      * owned by pkg 0 (fully-qualified name = "scala.inline") 3 -> Package "java.lang" 4 -> Class "Deprecated" owned by pkg 3 (fully-qualified name = "java.lang.Deprecated")
      * 5 -> Symbol.Method "foo" with @deprecated annotation 6 -> Symbol.Class "Bar" with @SerialVersionUID (using scala.deprecated slot for
      * simplicity) 7 -> Symbol.Field "f" with @java.lang.Deprecated 8 -> Symbol.Package "empty" 9 -> Symbol.TypeParam "T" 10 ->
      * Symbol.Method "inlined" with @scala.inline
      */
    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val scalaPkg    = makePackage(0, "scala")
            val deprecated  = makeAnnotClass(1, pkgId = 0, "deprecated")
            val inlineCls   = makeAnnotClass(2, pkgId = 0, "inline")
            val jlPkg       = makePackage(3, "java.lang")
            val jDeprecated = makeAnnotClass(4, pkgId = 3, "Deprecated")

            val scalaDeprecatedAnnot = Tasty.Annotation(
                Tasty.Type.Named(SymbolId(1)),
                Chunk.empty,
                Tasty.Name("scala.deprecated")
            )
            val scalaInlineAnnot = Tasty.Annotation(
                Tasty.Type.Named(SymbolId(2)),
                Chunk.empty,
                Tasty.Name("scala.inline")
            )
            val javaDeprecatedAnnot = Tasty.Java.Annotation(
                annotationClass = jDeprecated,
                values = Chunk.empty,
                annotationFullName = Tasty.Name("java.lang.Deprecated")
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
                fullNameIndex = Dict(
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
        }

    "hasAnnotation returns true for Scala annotation on method" in {
        buildFixture.map { classpath =>
            val m = classpath.symbol(SymbolId(5)) match
                case Maybe.Present(m: Tasty.Symbol.Method) => m
                case other                                 => fail(s"expected Symbol.Method at id 5, got $other")
            assert(classpath.hasAnnotation(m, "scala.deprecated"))
        }
    }

    "hasAnnotation returns true for Scala annotation on class" in {
        buildFixture.map { classpath =>
            val c = classpath.symbol(SymbolId(6)) match
                case Maybe.Present(c: Tasty.Symbol.Class) => c
                case other                                => fail(s"expected Symbol.Class at id 6, got $other")
            assert(classpath.hasAnnotation(c, "scala.deprecated"))
        }
    }

    "hasAnnotation returns true for Java annotation on field" in {
        buildFixture.map { classpath =>
            val f = classpath.symbol(SymbolId(7)) match
                case Maybe.Present(f: Tasty.Symbol.Field) => f
                case other                                => fail(s"expected Symbol.Field at id 7, got $other")
            assert(classpath.hasAnnotation(f, "java.lang.Deprecated"))
        }
    }

    "hasAnnotation returns false for Package" in {
        buildFixture.map { classpath =>
            val p = classpath.symbol(SymbolId(8)) match
                case Maybe.Present(p: Tasty.Symbol.Package) => p
                case other                                  => fail(s"expected Symbol.Package at id 8, got $other")
            assert(!classpath.hasAnnotation(p, "anything"))
        }
    }

    "hasAnnotation returns false for TypeParam" in {
        buildFixture.map { classpath =>
            val t = classpath.symbol(SymbolId(9)) match
                case Maybe.Present(t: Tasty.Symbol.TypeParam) => t
                case other                                    => fail(s"expected Symbol.TypeParam at id 9, got $other")
            assert(!classpath.hasAnnotation(t, "scala.deprecated"))
        }
    }

    "findAnnotation returns Present Annotation for matching Scala annotation" in {
        buildFixture.map { classpath =>
            val m = classpath.symbol(SymbolId(10)) match
                case Maybe.Present(m: Tasty.Symbol.Method) => m
                case other                                 => fail(s"expected Symbol.Method at id 10, got $other")
            classpath.findAnnotation(m, "scala.inline") match
                case Maybe.Present(a: Tasty.Annotation) =>
                    assert(
                        a.annotationType == Tasty.Type.Named(SymbolId(2)),
                        s"Expected Named(SymbolId(2)) but got ${a.annotationType}"
                    )
                    assert(a.arguments.isEmpty, s"Expected empty arguments but got ${a.arguments}")
                case other =>
                    fail(s"Expected Present Tasty.Annotation but got $other")
            end match
        }
    }

    "findAnnotation returns Present JavaAnnotation for matching Java annotation" in {
        buildFixture.map { classpath =>
            val f = classpath.symbol(SymbolId(7)) match
                case Maybe.Present(f: Tasty.Symbol.Field) => f
                case other                                => fail(s"expected Symbol.Field at id 7, got $other")
            classpath.findAnnotation(f, "java.lang.Deprecated") match
                case Maybe.Present(a: Tasty.Java.Annotation) =>
                    assert(
                        a.annotationClass.id == SymbolId(4),
                        s"Expected annotationClass id SymbolId(4) but got ${a.annotationClass.id}"
                    )
                    assert(a.values.isEmpty, s"Expected empty values but got ${a.values}")
                case other =>
                    fail(s"Expected Present Tasty.Java.Annotation but got $other")
            end match
        }
    }

    "findAnnotation returns Absent when annotation not present" in {
        buildFixture.map { classpath =>
            val m = classpath.symbol(SymbolId(5)) match
                case Maybe.Present(m: Tasty.Symbol.Method) => m
                case other                                 => fail(s"expected Symbol.Method at id 5, got $other")
            assert(!classpath.findAnnotation(m, "missing.Anno").isDefined)
        }
    }

end SymbolAnnotationQueryTest
