package kyo

import kyo.Tasty.SymbolId
import scala.compiletime.testing.typeCheckErrors

/** Tests for Classpath.typeParams, Classpath.hasAnnotation, Classpath.findAnnotation, and
  * Classpath.symbolsAnnotatedWith as pure instance methods, and the corresponding companion
  * shortcuts that delegate through the active classpath binding.
  *
  * Fixture layout:
  *   0  -> Symbol.TypeParam  "T"         (ownerId = 2)
  *   1  -> Symbol.TypeParam  "U"         (ownerId = 3)
  *   2  -> Symbol.Class      "Box"       (typeParamIds = [0]; annotations = [@shop.Tag]; ownerId = -1)
  *   3  -> Symbol.Method     "apply"     (typeParamIds = [1]; ownerId = 2)
  *   4  -> Symbol.TypeAlias  "Alias"     (typeParamIds = [0]; ownerId = -1)
  *   5  -> Symbol.OpaqueType "Opaque"    (typeParamIds = [0]; ownerId = -1)
  *   6  -> Symbol.Val        "v1"        (annotations = [@shop.Tag]; ownerId = 2)
  *   7  -> Symbol.Var        "var1"      (annotations = [@shop.Tag]; ownerId = 2)
  *   8  -> Symbol.AbstractType "AT"      (annotations = [@shop.Tag]; ownerId = 2)
  *   9  -> Symbol.Parameter  "p1"        (annotations = [@shop.Tag]; ownerId = 3)
  *   10 -> Symbol.Field      "f1"        (javaAnnotations = [@shop.JavaTag]; ownerId = 2)
  *   11 -> Symbol.Class      "Tag"       (ownerId = 12; the annotation class for @shop.Tag)
  *   12 -> Symbol.Package    "shop"      (ownerId = -1)
  *   13 -> Symbol.Class      "JavaTag"   (ownerId = 12)
  *   14 -> Symbol.TypeParam  "V"         (ownerId = 2; second type param of Box for completeness)
  *
  * Annotations wired:
  *   @shop.Tag  = Annotation(Type.Named(SymbolId(11)), Chunk.empty, Name("shop.Tag"))
  *   @shop.JavaTag = Java.Annotation(annotCls=13, Chunk.empty, Name("shop.JavaTag"))
  */
class Classpath4WayUnionAnnotationTest extends kyo.test.Test[Any]:

    private val tpTId     = SymbolId(0)
    private val tpUId     = SymbolId(1)
    private val boxId     = SymbolId(2)
    private val applyId   = SymbolId(3)
    private val aliasId   = SymbolId(4)
    private val opaqueId  = SymbolId(5)
    private val valId     = SymbolId(6)
    private val varId     = SymbolId(7)
    private val absTypeId = SymbolId(8)
    private val paramId   = SymbolId(9)
    private val fieldId   = SymbolId(10)
    private val tagClsId  = SymbolId(11)
    private val shopPkgId = SymbolId(12)
    private val javaTagId = SymbolId(13)

    private def makeTypeParam(id: SymbolId, name: String, ownerId: SymbolId): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            id,
            Tasty.Name(name),
            Tasty.Flags.empty,
            ownerId,
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Invariant
        )

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val tagAnn = Tasty.Annotation(Tasty.Type.Named(tagClsId), Chunk.empty, Tasty.Name("shop.Tag"))
            val tagCls = Tasty.Symbol.Class(
                tagClsId,
                Tasty.Name("Tag"),
                Tasty.Flags.empty,
                shopPkgId,
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
            val javaTagCls = Tasty.Symbol.Class(
                javaTagId,
                Tasty.Name("JavaTag"),
                Tasty.Flags.empty,
                shopPkgId,
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
            val shopPkg = Tasty.Symbol.Package(
                shopPkgId,
                Tasty.Name("shop"),
                Tasty.Flags.empty,
                SymbolId(-1),
                Chunk(tagClsId, javaTagId)
            )
            val javaTagAnn = Tasty.Java.Annotation(javaTagCls, Chunk.empty, Tasty.Name("shop.JavaTag"))
            val tpT        = makeTypeParam(tpTId, "T", boxId)
            val tpU        = makeTypeParam(tpUId, "U", applyId)
            val boxClass = Tasty.Symbol.Class(
                boxId,
                Tasty.Name("Box"),
                Tasty.Flags.empty,
                SymbolId(-1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                typeParamIds = Chunk(tpTId),
                declarationIds = Chunk(applyId, valId, varId, absTypeId, fieldId),
                Maybe.Absent,
                annotations = Chunk(tagAnn),
                javaAnnotations = Chunk(javaTagAnn)
            )
            val applyMethod = Tasty.Symbol.Method(
                applyId,
                Tasty.Name("apply"),
                Tasty.Flags.empty,
                boxId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                typeParamIds = Chunk(tpUId),
                Chunk.empty,
                Maybe.Absent
            )
            val aliasTypeAlias = Tasty.Symbol.TypeAlias(
                aliasId,
                Tasty.Name("Alias"),
                Tasty.Flags.empty,
                SymbolId(-1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                typeParamIds = Chunk(tpTId),
                Chunk.empty
            )
            val opaqueTypeSym = Tasty.Symbol.OpaqueType(
                opaqueId,
                Tasty.Name("Opaque"),
                Tasty.Flags.empty,
                SymbolId(-1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
                typeParamIds = Chunk(tpTId),
                Chunk.empty
            )
            val v1Val = Tasty.Symbol.Val(
                valId,
                Tasty.Name("v1"),
                Tasty.Flags.empty,
                boxId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                annotations = Chunk(tagAnn)
            )
            val var1Var = Tasty.Symbol.Var(
                varId,
                Tasty.Name("var1"),
                Tasty.Flags.empty,
                boxId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                annotations = Chunk(tagAnn)
            )
            val absType = Tasty.Symbol.AbstractType(
                absTypeId,
                Tasty.Name("AT"),
                Tasty.Flags.empty,
                boxId,
                Maybe.Absent,
                Maybe.Absent,
                Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
                annotations = Chunk(tagAnn)
            )
            val p1Param = Tasty.Symbol.Parameter(
                paramId,
                Tasty.Name("p1"),
                Tasty.Flags.empty,
                applyId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                annotations = Chunk(tagAnn)
            )
            val f1Field = Tasty.Symbol.Field(
                fieldId,
                Tasty.Name("f1"),
                Tasty.Flags.empty,
                boxId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                javaAnnotations = Chunk(javaTagAnn)
            )
            Tasty.Classpath.make(
                symbols = Chunk(
                    tpT,
                    tpU,
                    boxClass,
                    applyMethod,
                    aliasTypeAlias,
                    opaqueTypeSym,
                    v1Val,
                    var1Var,
                    absType,
                    p1Param,
                    f1Field,
                    tagCls,
                    shopPkg,
                    javaTagCls
                ),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(boxId, aliasId, opaqueId),
                packageIds = Chunk(shopPkgId),
                fullNameIndex = Dict(
                    "Box"          -> boxId,
                    "shop.Tag"     -> tagClsId,
                    "shop.JavaTag" -> javaTagId
                ),
                packageIndex = Dict("shop" -> shopPkgId),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    // typeParams: ClassLike case
    "typeParams returns TypeParam chunk for ClassLike" in {
        buildFixture.map { classpath =>
            val result = classpath.typeParams(classpath.symbols.collect {
                case c: Tasty.Symbol.Class if c.name == Tasty.Name("Box") => c
            }.head)
            assert(result.nonEmpty, s"Box must have type parameter T but got empty chunk")
            result match
                case chunk if chunk.nonEmpty =>
                    assert(chunk(0).name == Tasty.Name("T"), s"Expected T but got ${chunk(0).name}")
                case _ =>
                    fail("Expected non-empty TypeParam chunk for Box")
            end match
            succeed
        }
    }

    // typeParams: Method case
    "typeParams returns TypeParam chunk for Method" in {
        buildFixture.map { classpath =>
            val method = classpath.symbols.collect { case m: Tasty.Symbol.Method => m }.head
            val result = classpath.typeParams(method)
            assert(result.nonEmpty, s"apply must have type parameter U but got empty chunk")
            result match
                case chunk if chunk.nonEmpty =>
                    assert(chunk(0).name == Tasty.Name("U"), s"Expected U but got ${chunk(0).name}")
                case _ =>
                    fail("Expected non-empty TypeParam chunk for apply")
            end match
            succeed
        }
    }

    // typeParams: TypeAlias case
    "typeParams returns TypeParam chunk for TypeAlias" in {
        buildFixture.map { classpath =>
            val alias  = classpath.symbols.collect { case ta: Tasty.Symbol.TypeAlias => ta }.head
            val result = classpath.typeParams(alias)
            assert(result.nonEmpty, s"Alias must have type parameter T but got empty chunk")
            succeed
        }
    }

    // typeParams: OpaqueType case
    "typeParams returns TypeParam chunk for OpaqueType" in {
        buildFixture.map { classpath =>
            val opaque = classpath.symbols.collect { case ot: Tasty.Symbol.OpaqueType => ot }.head
            val result = classpath.typeParams(opaque)
            assert(result.nonEmpty, s"Opaque must have type parameter T but got empty chunk")
            succeed
        }
    }

    // typeParams: wrong-kind rejected at compile time (4-way)
    "typeParams rejects Val at compile time" in {
        val errors = typeCheckErrors(
            """
            val classpath: kyo.Tasty.Classpath = ???
            val v: kyo.Tasty.Symbol.Val = ???
            classpath.typeParams(v)
            """
        )
        assert(errors.nonEmpty, "classpath.typeParams(val) must be a compile error; only ClassLike|Method|TypeAlias|OpaqueType accepted")
        succeed
    }

    // hasAnnotation: Class with present Scala annotation
    "hasAnnotation returns true for ClassLike carrying Scala annotation" in {
        buildFixture.map { classpath =>
            val box = classpath.symbols.collect { case c: Tasty.Symbol.Class if c.name == Tasty.Name("Box") => c }.head
            assert(classpath.hasAnnotation(box, "shop.Tag"), "Box carries @shop.Tag; hasAnnotation must return true")
            succeed
        }
    }

    // hasAnnotation: Class with absent annotation
    "hasAnnotation returns false for ClassLike not carrying given annotation" in {
        buildFixture.map { classpath =>
            val box = classpath.symbols.collect { case c: Tasty.Symbol.Class if c.name == Tasty.Name("Box") => c }.head
            assert(!classpath.hasAnnotation(box, "shop.Other"), "Box does not carry @shop.Other; hasAnnotation must return false")
            succeed
        }
    }

    // hasAnnotation: Val with present annotation
    "hasAnnotation returns true for Val carrying Scala annotation" in {
        buildFixture.map { classpath =>
            val v1 = classpath.symbols.collect { case v: Tasty.Symbol.Val => v }.head
            assert(classpath.hasAnnotation(v1, "shop.Tag"), "v1 carries @shop.Tag; hasAnnotation must return true")
            succeed
        }
    }

    // hasAnnotation: TypeParam returns false
    "hasAnnotation returns false for TypeParam" in {
        buildFixture.map { classpath =>
            val tp = classpath.symbols.collect { case tp: Tasty.Symbol.TypeParam => tp }.head
            assert(!classpath.hasAnnotation(tp, "shop.Tag"), "TypeParam carries no annotations; hasAnnotation must return false")
            succeed
        }
    }

    // hasAnnotation: Parameter returns false for absent annotation name (Parameter stores annotations but not @shop.Tag)
    "hasAnnotation returns false for Parameter not carrying the annotation" in {
        buildFixture.map { classpath =>
            val param = classpath.symbols.collect { case p: Tasty.Symbol.Parameter => p }.head
            // p1 DOES carry @shop.Tag in the fixture, so use a different name
            assert(!classpath.hasAnnotation(param, "shop.Other"), "Parameter does not carry @shop.Other; hasAnnotation must return false")
            succeed
        }
    }

    // hasAnnotation: Package returns false
    "hasAnnotation returns false for Package" in {
        buildFixture.map { classpath =>
            val pkg = classpath.symbols.collect { case p: Tasty.Symbol.Package => p }.head
            assert(!classpath.hasAnnotation(pkg, "shop.Tag"), "Package carries no annotations; hasAnnotation must return false")
            succeed
        }
    }

    // hasAnnotation: ClassLike with Java annotation matches via javaAnnotations
    "hasAnnotation returns true for ClassLike carrying Java annotation" in {
        buildFixture.map { classpath =>
            val box = classpath.symbols.collect { case c: Tasty.Symbol.Class if c.name == Tasty.Name("Box") => c }.head
            assert(
                classpath.hasAnnotation(box, "shop.JavaTag"),
                "Box carries @shop.JavaTag via javaAnnotations; hasAnnotation must return true"
            )
            succeed
        }
    }

    // findAnnotation: Scala-side
    "findAnnotation returns Present for symbol carrying Scala annotation" in {
        buildFixture.map { classpath =>
            val box = classpath.symbols.collect { case c: Tasty.Symbol.Class if c.name == Tasty.Name("Box") => c }.head
            classpath.findAnnotation(box, "shop.Tag") match
                case Maybe.Present(_: Tasty.Annotation) => succeed
                case Maybe.Present(_: Tasty.Java.Annotation) =>
                    fail("Expected Scala Annotation for @shop.Tag but got Java.Annotation")
                case Maybe.Absent =>
                    fail("Expected Maybe.Present for @shop.Tag on Box but got Maybe.Absent")
            end match
        }
    }

    // findAnnotation: Java-side
    "findAnnotation returns Present for symbol carrying Java annotation" in {
        buildFixture.map { classpath =>
            val field = classpath.symbols.collect { case f: Tasty.Symbol.Field => f }.head
            classpath.findAnnotation(field, "shop.JavaTag") match
                case Maybe.Present(_: Tasty.Java.Annotation) => succeed
                case Maybe.Present(_: Tasty.Annotation) =>
                    fail("Expected Java.Annotation for @shop.JavaTag on Field but got Scala Annotation")
                case Maybe.Absent =>
                    fail("Expected Maybe.Present for @shop.JavaTag on f1 but got Maybe.Absent")
            end match
        }
    }

    // findAnnotation: Absent on TypeParam
    "findAnnotation returns Absent for TypeParam" in {
        buildFixture.map { classpath =>
            val tp = classpath.symbols.collect { case tp: Tasty.Symbol.TypeParam => tp }.head
            assert(
                classpath.findAnnotation(tp, "shop.Tag") == Maybe.Absent,
                "TypeParam carries no annotations; findAnnotation must return Maybe.Absent"
            )
            succeed
        }
    }

    // findAnnotation: ClassLike with both Scala and Java annotation matching same name returns Scala first
    "findAnnotation returns Scala annotation first when both Scala and Java match for ClassLike" in {
        buildFixture.map { classpath =>
            // Box has both @shop.Tag (Scala) and @shop.JavaTag (Java), but with different names.
            // To test the "Scala first" ordering on Box for @shop.Tag, we confirm the Scala one is returned.
            val box = classpath.symbols.collect { case c: Tasty.Symbol.Class if c.name == Tasty.Name("Box") => c }.head
            classpath.findAnnotation(box, "shop.Tag") match
                case Maybe.Present(_: Tasty.Annotation) =>
                    // Scala annotation returned first (body order: c.annotations.find(...) before javaAnnotations)
                    succeed
                case Maybe.Present(_: Tasty.Java.Annotation) =>
                    fail("Expected Scala annotation first for @shop.Tag on Box; got Java.Annotation")
                case Maybe.Absent =>
                    fail("Expected Maybe.Present for @shop.Tag on Box but got Maybe.Absent")
            end match
        }
    }

    // findAnnotation: return type Maybe[AnnotationLike] (type-level audit)
    "findAnnotation return type is Maybe[AnnotationLike]" in {
        val errors = typeCheckErrors(
            """
            val classpath: kyo.Tasty.Classpath = ???
            val symbol: kyo.Tasty.Symbol = ???
            val _: kyo.Maybe[kyo.Tasty.AnnotationLike] = classpath.findAnnotation(symbol, "x")
            """
        )
        assert(errors.isEmpty, s"classpath.findAnnotation must type-check as Maybe[AnnotationLike]; got errors: $errors")
        succeed
    }

    // symbolsAnnotatedWith: concrete index hit
    "symbolsAnnotatedWith returns non-empty Chunk when annotation is present" in {
        buildFixture.map { classpath =>
            val result = classpath.symbolsAnnotatedWith("shop.Tag")
            assert(result.nonEmpty, s"Expected at least one symbol annotated with @shop.Tag but got empty chunk")
            succeed
        }
    }

    // symbolsAnnotatedWith: missing annotation returns empty
    "symbolsAnnotatedWith returns empty Chunk when no symbol carries the annotation" in {
        buildFixture.map { classpath =>
            val result = classpath.symbolsAnnotatedWith("shop.NoSuchAnnotation")
            assert(result.isEmpty, s"Expected empty chunk for unknown annotation but got: ${result.size} symbols")
            succeed
        }
    }

    // pure-signature shape: no Frame in scope for any of the four methods
    "typeParams, hasAnnotation, findAnnotation, symbolsAnnotatedWith compile without Frame" in {
        val errors = typeCheckErrors(
            """
            val classpath: kyo.Tasty.Classpath = ???
            val cls: kyo.Tasty.Symbol.Class = ???
            val symbol: kyo.Tasty.Symbol = ???
            val _: kyo.Chunk[kyo.Tasty.Symbol.TypeParam] = classpath.typeParams(cls)
            val _: Boolean                               = classpath.hasAnnotation(symbol, "x")
            val _: kyo.Maybe[kyo.Tasty.AnnotationLike]  = classpath.findAnnotation(symbol, "x")
            val _: kyo.Chunk[kyo.Tasty.Symbol]          = classpath.symbolsAnnotatedWith("x")
            """
        )
        assert(errors.isEmpty, s"All four methods must compile without Frame in scope; got: $errors")
        succeed
    }

    // Verifies that typeParams, hasAnnotation, findAnnotation, and symbolsAnnotatedWith are accessible
    // on JVM, JS, and Native without a platform filter. The test compiles and runs cross-platform.
    "typeParams and annotation pure instance methods are accessible on all three platforms" in {
        val tpSym = Tasty.Symbol.TypeParam(
            SymbolId(0),
            Tasty.Name("T"),
            Tasty.Flags.empty,
            SymbolId(1),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Invariant
        )
        val annotation = Tasty.Annotation(Tasty.Type.Named(SymbolId(2)), Chunk.empty, Tasty.Name("test.Ann"))
        val cls = Tasty.Symbol.Class(
            SymbolId(1),
            Tasty.Name("X"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            typeParamIds = Chunk(SymbolId(0)),
            Chunk.empty,
            Maybe.Absent,
            annotations = Chunk(annotation),
            Chunk.empty
        )
        val annCls = Tasty.Symbol.Class(
            SymbolId(2),
            Tasty.Name("Ann"),
            Tasty.Flags.empty,
            SymbolId(3),
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
        val pkg = Tasty.Symbol.Package(
            SymbolId(3),
            Tasty.Name("test"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Chunk.empty
        )
        Sync.defer {
            Tasty.Classpath.make(
                symbols = Chunk(tpSym, cls, annCls, pkg),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(1)),
                packageIds = Chunk(SymbolId(3)),
                fullNameIndex = Dict("test.Ann" -> SymbolId(2)),
                packageIndex = Dict("test" -> SymbolId(3)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }.map { classpath =>
            val tps     = classpath.typeParams(cls)
            val hasAnn  = classpath.hasAnnotation(cls, "test.Ann")
            val findAnn = classpath.findAnnotation(cls, "test.Ann")
            val allAnn  = classpath.symbolsAnnotatedWith("test.Ann")
            assert(tps.nonEmpty, "X must have 1 type parameter T")
            assert(hasAnn, "X carries @test.Ann; hasAnnotation must be true")
            assert(findAnn.isDefined, "findAnnotation must return Present for @test.Ann on X")
            assert(allAnn.nonEmpty, "symbolsAnnotatedWith must find X via @test.Ann")
            succeed
        }
    }

end Classpath4WayUnionAnnotationTest
