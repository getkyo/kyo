package kyo

import kyo.Schema.*
import kyo.schema.*

final class wireFormat(val codec: String) extends SchemaAnnotation

final class sampleAnn() extends scala.annotation.StaticAnnotation

final class tagged(val note: String) extends SchemaAnnotation

object NC13Support:
    def runtimeValue: Int = 42

final class computed13(val n: Int) extends scala.annotation.StaticAnnotation

case class SA7Msg(@wireFormat("hex") id: String) derives Schema
case class SA9Msg(@sampleAnn() x: Int) derives Schema
case class SA10Msg(@annotation.nowarn() x: Int) derives Schema
case class SA13Msg(@computed13(NC13Support.runtimeValue) x: Int) derives Schema
case class SA14Msg(@wireFormat("a") @sampleAnn() x: Int) derives Schema

object SA8Scope:
    inline given AnnotationPolicy = AnnotationPolicy.markersOnly
    case class Msg(@wireFormat("hex") id: String) derives Schema

object SA11Scope:
    inline given AnnotationPolicy = AnnotationPolicy.markersOnly
    case class Msg(@wireFormat("wf") @sampleAnn() x: Int) derives Schema

object SA12Scope:
    inline given AnnotationPolicy = AnnotationPolicy(include = Chunk.empty)
    case class Msg(@sampleAnn() x: Int) derives Schema

@tagged("p") case class SA14bProd(x: Int) derives Schema

@tagged("s") sealed trait SA14bSum derives Schema
@tagged("v") case class SA14bV1(y: Int) extends SA14bSum derives Schema
case class SA14bV2(z: Int)              extends SA14bSum derives Schema

// SA15: @rename on a product field
case class SA15Prod(@rename("first_name") firstName: String) derives Schema

// SA16: @discriminator on sealed trait, @rename on a variant
@discriminator("type") sealed trait SA16Sum derives Schema
@rename("para") case class SA16V1(x: Int) extends SA16Sum derives Schema
case class SA16V2(y: Int)                 extends SA16Sum derives Schema

// SA17: @transient on a field with a Scala default
case class SA17Prod(a: Int, @transient b: Int = 7) derives Schema

// SA18: @discriminator with a custom tag key
@discriminator("kind") sealed trait SA18Sum derives Schema
case class SA18A(x: Int)    extends SA18Sum derives Schema
case class SA18B(y: String) extends SA18Sum derives Schema

// SA19: @adjacent representation
@adjacent("t", "c") sealed trait SA19Sum derives Schema
case class SA19A(x: Int)    extends SA19Sum derives Schema
case class SA19B(y: String) extends SA19Sum derives Schema

// SA20: @untagged representation (structurally distinct variants)
@untagged() sealed trait SA20Sum derives Schema
case class SA20A(x: Int)    extends SA20Sum derives Schema
case class SA20B(y: String) extends SA20Sum derives Schema

// SA21: @doc on type and on a field
@doc("a documented product") case class SA21Prod(@doc("identifier field") id: Int) derives Schema

// SA22: @alias on a field combined with @rename
// Field Scala name is "fieldName" (not "name") to avoid resolveTarget self-loop when source==wire.
case class SA22Prod(@rename("name") @alias("fullName", "n") fieldName: String) derives Schema

// SA23: @alias on a variant (routes onto variantAliases, not fieldAliases)
@discriminator("type") sealed trait SA23Sum derives Schema
@rename("p") @alias("paragraph") case class SA23V1(x: Int) extends SA23Sum derives Schema
case class SA23V2(y: Int)                                  extends SA23Sum derives Schema

// SA24: @omit no-arg on a Maybe field (Absent mode -> WhenNone)
case class SA24Prod(@omit a: Maybe[Int]) derives Schema

// SA25: @omit(omit.WhenNone) on a Maybe field
case class SA25Prod(@omit(schema.omit.WhenNone) a: Maybe[Int]) derives Schema

// SA26: @omit(omit.WhenEmpty) on a Chunk field
case class SA26Prod(@omit(schema.omit.WhenEmpty) xs: Chunk[Int]) derives Schema

// SA27: @omit(omit.WhenDefault) on an Int field with a Scala default
case class SA27Prod(@omit(schema.omit.WhenDefault) n: Int = 0) derives Schema

// SA28: @omit composes with @rename
case class SA28Prod(@rename("a_field") @omit(schema.omit.WhenNone) a: Maybe[Int]) derives Schema

// SA29: used for both SA29 and SA30 tests
case class SA29Prod(@rename("w") v: Int) derives Schema

// SA31: per-field @omit(WhenDefault) alongside schema-wide omitNone
case class SA31Prod(@omit(schema.omit.WhenDefault) x: Int = 0, b: Maybe[Int]) derives Schema

// SA32: wide annotated class (24 fields) - compile check
case class SA32Wide(
    @rename("w_01") f01: Int = 0,
    @alias("f02a") f02: Int = 0,
    @doc("field f03") f03: Int = 0,
    @omit(schema.omit.WhenDefault) f04: Int = 0,
    @omit(schema.omit.WhenNone) f05: Maybe[Int] = Maybe.empty,
    @omit(schema.omit.WhenEmpty) f06: Chunk[Int] = Chunk.empty,
    @transient f07: Int = 0,
    @rename("w_08") @alias("f08a") f08: Int = 0,
    @rename("w_09") @doc("field f09") f09: Int = 0,
    @rename("w_10") @omit(schema.omit.WhenNone) f10: Maybe[Int] = Maybe.empty,
    @alias("f11a") @doc("field f11") f11: Int = 0,
    @doc("field f12") @omit(schema.omit.WhenDefault) f12: Int = 0,
    f13: Int = 0,
    f14: Int = 0,
    f15: Int = 0,
    f16: Int = 0,
    f17: Int = 0,
    f18: Int = 0,
    f19: Int = 0,
    f20: Int = 0,
    f21: Int = 0,
    f22: Int = 0,
    f23: Int = 0,
    f24: Int = 0
) derives Schema

// SA34: correctly-placed @discriminator
@discriminator("k") sealed trait SA34Sum derives Schema
case class SA34V1(v: Int)    extends SA34Sum derives Schema
case class SA34V2(w: String) extends SA34Sum derives Schema

// SA35: @alias colliding with another field's wire name.
// No `derives Schema` here; schema construction fires inside the test body so the
// FieldNameCollisionException propagates directly rather than via ExceptionInInitializerError.
case class SA35Prod(@alias("id") a: Int, @rename("id") b: Int)

// SA36: variant alias collision (two variants register the same alias).
// No `derives Schema` here; same reason as SA35.
@discriminator("type") sealed trait SA36Sum
@alias("X") case class SA36V1(v: Int) extends SA36Sum
@alias("X") case class SA36V2(w: Int) extends SA36Sum

// SA37: unannotated field alongside an annotated field
case class SA37Prod(a: Int, @omit b: Maybe[Int]) derives Schema

// SA38/SA39: @discriminator sum used for well-formed and hostile tag tests
@discriminator("type") sealed trait SA38Sum derives Schema
case class SA38Para(text: String) extends SA38Sum derives Schema
case class SA38Other(code: Int)   extends SA38Sum derives Schema

// SA41: @omit with a reason string on a Maybe field - reason is documentation only
case class SA41Prod(@omit(reason = "deprecated, use newField instead") a: Maybe[Int]) derives Schema

// SA42: @omit (Absent mode) on a plain scalar compiles; no omit policy applied, field always on wire
case class SA42Prod(@omit(reason = "internal, do not serialize") x: Int) derives Schema

// SA43: @omit(reason = "legacy") on a Maybe field - annotation captured with reason in Structure.Field.annotations
case class SA43Prod(@omit(reason = "legacy") f: Maybe[Int]) derives Schema

// SA44: @omit(omit.WhenEmpty, reason = "skip") on a List field - both when and reason captured
case class SA44Prod(@omit(schema.omit.WhenEmpty, reason = "skip") xs: List[Int]) derives Schema

// SA45: @omit(omit.WhenNone) on a Maybe field - positional, captured with when == omit.WhenNone
case class SA45Prod(@omit(schema.omit.WhenNone) g: Maybe[Int]) derives Schema

// SA46: SchemaAnnotation with a defaulted constructor param; @marked() uses the default
final class marked(val tag: String = "x") extends SchemaAnnotation
case class SA46Prod(@marked() h: Int) derives Schema

// SA47: @alias on a variant colliding with another variant's primary tag.
// No `derives Schema` here; schema construction is deferred to the test body via
// Schema.derived[SA47Sum] inside interceptThrown (same pattern as SA35/SA36).
@discriminator("t") sealed trait SA47Sum
case class SA47Circle(r: Int)                      extends SA47Sum
@alias("SA47Circle") case class SA47Square(s: Int) extends SA47Sum

// ProbeUpperCase is a stable module reference passed as the @transform argument.
// The derivation macro reifies the term to a runtime Transformer.Full[String]
// instance whose write and read are invoked by SchemaSerializer at encode/decode time.
object ProbeUpperCase extends Transformer.Full[String]:
    def write(value: String, writer: kyo.Codec.Writer): Unit = writer.string(value.toUpperCase)
    def read(reader: kyo.Codec.Reader): String               = reader.string().toLowerCase

case class ProbeMsg(@transform(ProbeUpperCase) code: String) derives Schema

// SA48: WriteOnly transform -- custom write only; derived codec used on decode.
object SA48WriteDouble extends Transformer.WriteOnly[Int]:
    def write(value: Int, writer: kyo.Codec.Writer): Unit = writer.int(value * 2)
case class SA48Prod(@transform(SA48WriteDouble) x: Int) derives Schema

// SA49: ReadOnly transform -- derived codec used on encode; custom read on decode.
object SA49ReadUpper extends Transformer.ReadOnly[String]:
    def read(reader: kyo.Codec.Reader): String = reader.string().toUpperCase
case class SA49Prod(@transform(SA49ReadUpper) s: String) derives Schema

// SA50: omit.When with a named predicate object.
// count has a Scala default so decode succeeds when the field is absent from the wire.
object SA50NegativePred extends OmitPredicate:
    def test(value: kyo.Structure.Value): Boolean = value match
        case kyo.Structure.Value.Integer(n) => n < 0
        case _                              => false
end SA50NegativePred
case class SA50Prod(@omit(schema.omit.When(SA50NegativePred)) count: Int = 0) derives Schema

// SA51: Nested object reference -- transformer lives inside a containing object.
object SA51Outer:
    object Inner extends Transformer.Full[Int]:
        def write(value: Int, writer: kyo.Codec.Writer): Unit = writer.int(value + 100)
        def read(reader: kyo.Codec.Reader): Int               = reader.int() - 100
end SA51Outer
case class SA51Prod(@transform(SA51Outer.Inner) n: Int) derives Schema

// SA52: joint fixture with @transform on one field and @omit(omit.When) on another.
// Both annotations use the same object-reference lift path in the derivation macro,
// so deriving Schema exercises both annotation-handling paths on a single type.
case class SA52Joint(
    @transform(ProbeUpperCase) code: String,
    @omit(schema.omit.When(SA50NegativePred)) count: Int = 0
) derives Schema

// @proto.fieldNumber pins a Protobuf field number, desugaring onto a single-segment
// fieldId override during derivation so encode, decode, protoSchema, and fieldNumberAudit honor it.
case class PFNPinned(@proto.fieldNumber(5) x: Int, y: String) derives Schema, CanEqual
case class PFNTwoPins(@proto.fieldNumber(11) a: Int, @proto.fieldNumber(22) b: String) derives Schema, CanEqual
case class PFNRenameCompose(@proto.fieldNumber(4) id: Int, @rename("wire_label") label: String) derives Schema, CanEqual
case class PFNRenameSamePin(@proto.fieldNumber(7) @rename("wire_x") x: Int, y: String) derives Schema, CanEqual

class SchemaAnnotationTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "a custom marker is captured and readable by type" in {
        val fields = summon[Schema[SA7Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields
        val result = fields.head.annotations.collectFirst { case w: wireFormat => w.codec }
        assert(result == Some("hex"), s"wireFormat codec must be 'hex'; got $result")
    }

    "a marker is captured regardless of a markers-only policy" in {
        val fields = summon[Schema[SA8Scope.Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields
        val result = fields.head.annotations.collectFirst { case w: wireFormat => w.codec }
        assert(result == Some("hex"), s"marker must bypass empty-include policy; got $result")
    }

    "a non-marker is captured when include admits and exclude does not" in {
        val fields = summon[Schema[SA9Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields
        val annots = fields.head.annotations
        assert(
            annots.exists(_.isInstanceOf[sampleAnn]),
            s"sampleAnn must be captured under default policy (include=*); annotations=$annots"
        )
    }

    "a default-excluded compiler annotation is absent from the captured annotations" in {
        val fields = summon[Schema[SA10Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields
        val annots = fields.head.annotations
        assert(
            !annots.exists(_.isInstanceOf[scala.annotation.nowarn]),
            s"nowarn must be excluded by defaultExclusions; annotations=$annots"
        )
    }

    "markersOnly drops a non-marker while keeping a marker on the same field" in {
        val fields = summon[Schema[SA11Scope.Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields
        val annots = fields.head.annotations
        assert(
            annots.exists(_.isInstanceOf[wireFormat]),
            s"wireFormat (marker) must be present under markersOnly; annotations=$annots"
        )
        assert(
            !annots.exists(_.isInstanceOf[sampleAnn]),
            s"sampleAnn (non-marker) must be absent under markersOnly; annotations=$annots"
        )
    }

    "a user-summoned given overrides the default capture policy" in {
        val defaultAnnots = summon[Schema[SA9Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields.head.annotations
        assert(
            defaultAnnots.exists(_.isInstanceOf[sampleAnn]),
            s"default policy must capture sampleAnn; annotations=$defaultAnnots"
        )
        val emptyAnnots = summon[Schema[SA12Scope.Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields.head.annotations
        assert(
            !emptyAnnots.exists(_.isInstanceOf[sampleAnn]),
            s"empty-include policy must drop sampleAnn; annotations=$emptyAnnots"
        )
    }

    "an INCLUDED non-reifiable annotation is gracefully skipped" in {
        val fields = summon[Schema[SA13Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields
        val annots = fields.head.annotations
        assert(
            annots.isEmpty,
            s"non-liftable annotation (def-arg) must be gracefully skipped; annotations=$annots"
        )
        assert(
            scala.compiletime.testing.typeChecks(
                """
                final class c13b(val n: Int) extends scala.annotation.StaticAnnotation
                object C13bHelper { def dyn: Int = 42 }
                case class NC13b(@c13b(C13bHelper.dyn) x: Int) derives kyo.Schema
                """
            ),
            "derivation with non-constant annotation arg must compile without error"
        )
    }

    "captured annotations preserve source-declaration order" in {
        val fields = summon[Schema[SA14Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields
        val annots = fields.head.annotations
        assert(annots.size == 2, s"expected 2 annotations; got ${annots.size}: $annots")
        assert(annots(0).isInstanceOf[wireFormat], s"first annotation must be wireFormat; got ${annots(0)}")
        assert(annots(1).isInstanceOf[sampleAnn], s"second annotation must be sampleAnn; got ${annots(1)}")
        val annots2 = summon[Schema[SA14Msg]].structure
            .asInstanceOf[Structure.Type.Product].fields.head.annotations
        assert(annots2(0).isInstanceOf[wireFormat], "order must be deterministic across re-derivation")
        assert(annots2(1).isInstanceOf[sampleAnn], "order must be deterministic across re-derivation")
    }

    "a custom marker is read back off Product-TYPE, Sum-TYPE, and Variant types" in {
        val prodAnnots = summon[Schema[SA14bProd]].structure
            .asInstanceOf[Structure.Type.Product].annotations
        val prodNote = prodAnnots.collectFirst { case t: tagged => t.note }
        assert(prodNote == Some("p"), s"product-type annotations must yield 'p'; got $prodNote")

        summon[Schema[SA14bSum]].structure match
            case Structure.Type.Sum(_, _, _, variants, _, sumAnnots) =>
                val sumNote = sumAnnots.collectFirst { case t: tagged => t.note }
                assert(sumNote == Some("s"), s"sum-type annotations must yield 's'; got $sumNote")
                val v1 = variants.find(_.name == "SA14bV1").getOrElse(
                    fail(s"variant SA14bV1 not found; variants=${variants.map(_.name)}")
                )
                val v1Note = v1.annotations.collectFirst { case t: tagged => t.note }
                assert(v1Note == Some("v"), s"V1 variant annotations must yield 'v'; got $v1Note")
                val v2 = variants.find(_.name == "SA14bV2").getOrElse(
                    fail(s"variant SA14bV2 not found; variants=${variants.map(_.name)}")
                )
                assert(v2.annotations.isEmpty, s"V2 variant must have empty annotations; got ${v2.annotations}")
            case other =>
                fail(s"Expected Structure.Type.Sum for SA14bSum, got $other")
        end match
    }

    "@rename on a field changes the wire key" in {
        val enc = Json.encode(SA15Prod("ada"))
        assert(enc == """{"first_name":"ada"}""", s"@rename must emit the wire name: $enc")
        val dec = Json.decode[SA15Prod]("""{"first_name":"ada"}""")
        assert(dec == Result.succeed(SA15Prod("ada")), s"round-trip must succeed: $dec")
    }

    "@rename on a variant changes the wire tag" in {
        val enc = Json.encode[SA16Sum](SA16V1(1))
        assert(enc == """{"type":"para","x":1}""", s"renamed variant tag must be 'para': $enc")
        val dec = Json.decode[SA16Sum]("""{"type":"para","x":1}""")
        assert(dec == Result.succeed(SA16V1(1): SA16Sum), s"decode with renamed tag must yield SA16V1(1): $dec")
    }

    "@transient drops a field on encode and reconstructs on decode" in {
        val enc = Json.encode(SA17Prod(1, 9))
        assert(!enc.contains("\"b\""), s"@transient field must not appear on the wire: $enc")
        assert(enc == """{"a":1}""", s"only 'a' must appear: $enc")
        val dec = Json.decode[SA17Prod]("""{"a":1}""")
        assert(dec == Result.succeed(SA17Prod(1, 7)), s"dropped field must reconstruct from Scala default (7): $dec")
    }

    "@discriminator produces an internally-tagged sum round-trip" in {
        val encA = Json.encode[SA18Sum](SA18A(5))
        assert(encA == """{"kind":"SA18A","x":5}""", s"SA18A encode: $encA")
        val decA = Json.decode[SA18Sum]("""{"kind":"SA18A","x":5}""")
        assert(decA == Result.succeed(SA18A(5): SA18Sum), s"SA18A round-trip: $decA")
        val encB = Json.encode[SA18Sum](SA18B("hi"))
        assert(encB == """{"kind":"SA18B","y":"hi"}""", s"SA18B encode: $encB")
        val decB = Json.decode[SA18Sum]("""{"kind":"SA18B","y":"hi"}""")
        assert(decB == Result.succeed(SA18B("hi"): SA18Sum), s"SA18B round-trip: $decB")
    }

    "@adjacent produces an adjacently-tagged sum round-trip" in {
        val encA = Json.encode[SA19Sum](SA19A(3))
        assert(encA == """{"t":"SA19A","c":{"x":3}}""", s"SA19A adjacent encode: $encA")
        val decA = Json.decode[SA19Sum]("""{"t":"SA19A","c":{"x":3}}""")
        assert(decA == Result.succeed(SA19A(3): SA19Sum), s"SA19A adjacent round-trip: $decA")
        val encB = Json.encode[SA19Sum](SA19B("world"))
        assert(encB == """{"t":"SA19B","c":{"y":"world"}}""", s"SA19B adjacent encode: $encB")
        val decB = Json.decode[SA19Sum]("""{"t":"SA19B","c":{"y":"world"}}""")
        assert(decB == Result.succeed(SA19B("world"): SA19Sum), s"SA19B adjacent round-trip: $decB")
    }

    "@untagged produces an untagged sum round-trip" in {
        val encA = Json.encode[SA20Sum](SA20A(7))
        assert(encA == """{"x":7}""", s"SA20A untagged encode must be bare payload: $encA")
        val encB = Json.encode[SA20Sum](SA20B("kyo"))
        assert(encB == """{"y":"kyo"}""", s"SA20B untagged encode must be bare payload: $encB")
        val decA = Json.decode[SA20Sum]("""{"x":7}""")
        assert(decA == Result.succeed(SA20A(7): SA20Sum), s"SA20A untagged decode: $decA")
        val decB = Json.decode[SA20Sum]("""{"y":"kyo"}""")
        assert(decB == Result.succeed(SA20B("kyo"): SA20Sum), s"SA20B untagged decode: $decB")
    }

    "@doc attaches documentation metadata (field and type sub-cases)" in {
        val s = summon[Schema[SA21Prod]]
        assert(
            s.documentation == Maybe("a documented product"),
            s"type-level @doc must set schema.documentation; got ${s.documentation}"
        )
        val fieldDoc = s.fieldDocs.get(Seq("id"))
        assert(
            fieldDoc == Some("identifier field"),
            s"field-level @doc must populate fieldDocs at Seq(wireName); got $fieldDoc"
        )
    }

    "@alias on a field decodes from the alias name" in {
        val enc = Json.encode(SA22Prod("ada"))
        assert(enc == """{"name":"ada"}""", s"encode must use the renamed wire key 'name': $enc")
        val decPrimary = Json.decode[SA22Prod]("""{"name":"ada"}""")
        assert(decPrimary == Result.succeed(SA22Prod("ada")), s"primary wire name must decode: $decPrimary")
        val decAlias1 = Json.decode[SA22Prod]("""{"fullName":"ada"}""")
        assert(decAlias1 == Result.succeed(SA22Prod("ada")), s"alias 'fullName' must decode: $decAlias1")
        val decAlias2 = Json.decode[SA22Prod]("""{"n":"ada"}""")
        assert(decAlias2 == Result.succeed(SA22Prod("ada")), s"alias 'n' must decode: $decAlias2")
    }

    "@alias on a variant decodes from the variant alias (routing absence from fieldAliases)" in {
        val schema = summon[Schema[SA23Sum]]
        assert(
            schema.variantNaming.variantAliases.nonEmpty,
            "variant-level @alias must populate variantNaming.variantAliases"
        )
        assert(
            schema.variantNaming.fieldAliases.isEmpty,
            "variant-level @alias must NOT pollute variantNaming.fieldAliases"
        )
        val dec = Json.decode[SA23Sum]("""{"type":"paragraph","x":42}""")
        assert(
            dec == Result.succeed(SA23V1(42): SA23Sum),
            s"decode via variant alias 'paragraph' must yield SA23V1(42): $dec"
        )
    }

    "@omit (Absent) omits an absent optional and reconstructs on decode" in {
        val encEmpty = Json.encode(SA24Prod(Maybe.empty))
        assert(encEmpty == "{}", s"Maybe.empty field must be omitted under @omit Absent: $encEmpty")
        val encPresent = Json.encode(SA24Prod(Maybe(5)))
        assert(encPresent == """{"a":5}""", s"Maybe(5) must be emitted: $encPresent")
        val decEmpty = Json.decode[SA24Prod]("{}")
        assert(
            decEmpty == Result.succeed(SA24Prod(Maybe.empty)),
            s"absent field must reconstruct as Maybe.empty: $decEmpty"
        )
    }

    "@omit(Omit.WhenNone) omits a None field" in {
        val encEmpty = Json.encode(SA25Prod(Maybe.empty))
        assert(encEmpty == "{}", s"Maybe.empty must be omitted under WhenNone: $encEmpty")
        val encPresent = Json.encode(SA25Prod(Maybe(5)))
        assert(encPresent == """{"a":5}""", s"Maybe(5) must be emitted: $encPresent")
    }

    "@omit(Omit.WhenEmpty) omits an empty collection" in {
        val encEmpty = Json.encode(SA26Prod(Chunk.empty))
        assert(encEmpty == "{}", s"Chunk.empty must be omitted under WhenEmpty: $encEmpty")
        val encPresent = Json.encode(SA26Prod(Chunk(1)))
        assert(encPresent == """{"xs":[1]}""", s"Chunk(1) must be emitted: $encPresent")
        val decEmpty = Json.decode[SA26Prod]("{}")
        assert(
            decEmpty == Result.succeed(SA26Prod(Chunk.empty)),
            s"absent collection must reconstruct as Chunk.empty: $decEmpty"
        )
    }

    "@omit(Omit.WhenDefault) omits a default-valued field" in {
        val encDefault = Json.encode(SA27Prod(0))
        assert(encDefault == "{}", s"n=0 (default) must be omitted under WhenDefault: $encDefault")
        val encNonDefault = Json.encode(SA27Prod(3))
        assert(encNonDefault == """{"n":3}""", s"n=3 (non-default) must be emitted: $encNonDefault")
        val decFromEmpty = Json.decode[SA27Prod]("{}")
        assert(
            decFromEmpty == Result.succeed(SA27Prod(0)),
            s"absent field must reconstruct from Scala default (0): $decFromEmpty"
        )
    }

    "@omit composes with @rename" in {
        val encPresent = Json.encode(SA28Prod(Maybe(5)))
        assert(encPresent == """{"a_field":5}""", s"present renamed+omit field must emit renamed key: $encPresent")
        val encEmpty = Json.encode(SA28Prod(Maybe.empty))
        assert(encEmpty == "{}", s"absent renamed+omit field must be omitted: $encEmpty")
    }

    "an annotation applies with no programmatic call" in {
        val enc = Json.encode(SA29Prod(1))
        assert(enc == """{"w":1}""", s"@rename annotation must emit wire name 'w' without any programmatic call: $enc")
    }

    "a programmatic builder call overrides the annotation" in {
        val annotationEnc = Json.encode(SA29Prod(1))
        assert(annotationEnc == """{"w":1}""", s"annotation gives wire name 'w': $annotationEnc")
        val overridden    = Schema[SA29Prod].rename("v", "z")
        val overriddenEnc = Json.encode(SA29Prod(1))(using overridden.asInstanceOf[Schema[SA29Prod]])
        assert(overriddenEnc == """{"z":1}""", s"programmatic rename must override annotation: $overriddenEnc")
    }

    "a per-field omit overrides a schema-wide omit" in {
        val schema = summon[Schema[SA31Prod]].omitNone

        val enc0Empty = Json.encode(SA31Prod(0, Maybe.empty))(using schema)
        assert(!enc0Empty.contains("\"x\""), s"x=default must be omitted by per-field WhenDefault: $enc0Empty")
        assert(!enc0Empty.contains("\"b\""), s"b=None must be omitted by schema-wide omitNone: $enc0Empty")

        val enc3Empty = Json.encode(SA31Prod(3, Maybe.empty))(using schema)
        assert(enc3Empty.contains("\"x\":3"), s"x=3 (non-default) must be emitted even under omitNone: $enc3Empty")
        assert(!enc3Empty.contains("\"b\""), s"b=None must still be omitted by schema-wide omitNone: $enc3Empty")

        val enc0Some = Json.encode(SA31Prod(0, Maybe(5)))(using schema)
        assert(!enc0Some.contains("\"x\""), s"x=default must be omitted by per-field WhenDefault: $enc0Some")
        assert(enc0Some.contains("\"b\":5"), s"b=Some(5) must be emitted (not None, schema-wide omitNone skips it): $enc0Some")
    }

    "a wide annotated case class compiles within the class-file limit" in {
        val schema = summon[Schema[SA32Wide]]
        assert(schema.sourceFields.nonEmpty, "wide annotated class must compile and derive a non-empty schema")
        assert(
            schema.sourceFields.size == 24,
            s"expected 24 source fields in SA32Wide; got ${schema.sourceFields.size}"
        )
    }

    "a sum-representation annotation on a product fails to compile (three sub-cases)" in {
        typeCheckFailure(
            """@kyo.schema.discriminator("disc") case class P33a(x: Int) derives kyo.Schema"""
        )("sum-representation annotation")
        typeCheckFailure(
            """@kyo.schema.adjacent("t","c") case class P33b(x: Int) derives kyo.Schema"""
        )("sum-representation annotation")
        typeCheckFailure(
            """@kyo.schema.untagged() case class P33c(x: Int) derives kyo.Schema"""
        )("sum-representation annotation")
    }

    "a correctly-placed @discriminator compiles and behaves" in {
        val _: Schema[SA34Sum] = Schema.derived[SA34Sum]
        val enc1               = Json.encode[SA34Sum](SA34V1(5))
        assert(enc1 == """{"k":"SA34V1","v":5}""", s"SA34V1 encode with 'k' discriminator: $enc1")
        val dec1 = Json.decode[SA34Sum]("""{"k":"SA34V1","v":5}""")
        assert(dec1 == Result.succeed(SA34V1(5): SA34Sum), s"SA34V1 decode: $dec1")
        val enc2 = Json.encode[SA34Sum](SA34V2("hello"))
        assert(enc2 == """{"k":"SA34V2","w":"hello"}""", s"SA34V2 encode: $enc2")
        val dec2 = Json.decode[SA34Sum]("""{"k":"SA34V2","w":"hello"}""")
        assert(dec2 == Result.succeed(SA34V2("hello"): SA34Sum), s"SA34V2 decode: $dec2")
    }

    "a field alias colliding with another field's wire name raises FieldNameCollisionException at schema construction" in {
        // @alias("id") on field a and @rename("id") on field b: alias "id" duplicates b's
        // renamed primary wire name "id". Schema.init runs checkFieldAliases and throws
        // FieldNameCollisionException before returning the schema object.
        interceptThrown[FieldNameCollisionException] {
            Schema.derived[SA35Prod]
        }
    }

    "a variant alias collision raises VariantNameCollisionException at schema construction" in {
        // Both SA36V1 and SA36V2 register alias "X". Schema.init runs checkVariantAliases
        // and detects that alias "X" maps to two distinct variant wire names, throwing
        // VariantNameCollisionException before returning the schema object.
        interceptThrown[VariantNameCollisionException] {
            Schema.derived[SA36Sum]
        }
    }

    "a variant @alias colliding with another variant's primary tag raises VariantNameCollisionException" in {
        // SA47Square registers alias "SA47Circle" which equals SA47Circle's primary wire name.
        // Schema.init uses the compile-time-baked effective-primaries set, catching this
        // alias-vs-primary collision at construction rather than silently producing a dead alias.
        interceptThrown[VariantNameCollisionException] {
            Schema.derived[SA47Sum]
        }
    }

    "an unannotated field is never omitted, renamed, or dropped" in {
        val encABsent = Json.encode(SA37Prod(1, Maybe.empty))
        assert(encABsent == """{"a":1}""", s"unannotated 'a' must be present; annotated 'b' (empty) omitted: $encABsent")
        val encAPresent = Json.encode(SA37Prod(1, Maybe(5)))
        assert(
            encAPresent == """{"a":1,"b":5}""",
            s"unannotated 'a' must be present; annotated 'b' (non-empty) must also be present: $encAPresent"
        )
    }

    "a well-formed discriminator tag decodes to its variant" in {
        val result  = Json.decode[SA38Sum]("""{"type":"SA38Para","text":"hello"}""")
        val decoded = result.getOrThrow
        assert(
            decoded == SA38Para("hello"),
            s"well-formed discriminator tag must decode to SA38Para(hello); got $decoded"
        )
    }

    "a hostile unknown discriminator tag yields a typed Result failure (parameterized)" in {
        val hostileTags = List("ZZZ", "", "x" * 500, "非ASCII")
        hostileTags.foreach { tag =>
            val input  = s"""{"type":"$tag","x":1}"""
            val result = Json.decode[SA38Sum](input)
            assert(result.isFailure, s"hostile tag '$tag' must yield isFailure; got $result")
            result match
                case Result.Failure(_: UnknownVariantException) => ()
                case other =>
                    fail(s"expected UnknownVariantException for hostile tag '$tag'; got $other")
            end match
        }
    }

    "a sum-representation annotation on a union type alias fails to compile" in {
        typeCheckFailure("""
            @kyo.schema.discriminator("type") type SA40U = Int | String
            val _ = kyo.Schema.derived[SA40U]
        """)("sum-representation annotation")
    }

    "@omit with a reason string still omits the optional field (Absent -> WhenNone)" in {
        val schema = summon[Schema[SA41Prod]]
        val enc0   = Json.encode(SA41Prod(Maybe.empty))
        assert(enc0 == "{}", s"SA41: empty Maybe with reason must still omit: $enc0")
        val enc1 = Json.encode(SA41Prod(Maybe(5)))
        assert(enc1 == """{"a":5}""", s"SA41: present Maybe with reason must encode: $enc1")
        val dec0 = Json.decode[SA41Prod]("{}")
        assert(dec0 == Result.succeed(SA41Prod(Maybe.empty)), s"SA41: decode empty: $dec0")
    }

    "@omit (Absent) on a plain scalar compiles and leaves the field always on wire" in {
        // Omit.WhenAbsent on a non-optional, non-collection field applies no omit policy;
        // the field is always present on the wire.
        val enc = Json.encode(SA42Prod(42))
        assert(enc == """{"x":42}""", s"SA42: scalar always on wire: $enc")
        val dec = Json.decode[SA42Prod]("""{"x":7}""")
        assert(dec == Result.succeed(SA42Prod(7)), s"SA42: decode scalar: $dec")
        val schema = summon[Schema[SA42Prod]]
        assert(schema.omitPolicies.isEmpty, s"SA42: no omit policy for scalar with Absent: ${schema.omitPolicies}")
    }

    "@omit(reason) annotation is captured in Structure.Field.annotations with reason intact" in {
        val fields = summon[Schema[SA43Prod]].structure
            .asInstanceOf[Structure.Type.Product].fields
        fields.head.annotations.collectFirst { case o: omit => o } match
            case Some(ann) =>
                assert(ann.reason == "legacy", s"SA43: captured reason must be 'legacy'; got ${ann.reason}")
                assert(ann.when == schema.omit.WhenAbsent, s"SA43: captured when must be omit.WhenAbsent (default); got ${ann.when}")
            case None =>
                assert(false, s"SA43: omit annotation must be captured in Structure.Field.annotations; got ${fields.head.annotations}")
        end match
        val encEmpty = Json.encode(SA43Prod(Maybe.empty))
        assert(encEmpty == "{}", s"SA43: omit wire behavior unchanged - Maybe.empty omitted: $encEmpty")
        val encPresent = Json.encode(SA43Prod(Maybe(9)))
        assert(encPresent == """{"f":9}""", s"SA43: omit wire behavior unchanged - Maybe present emitted: $encPresent")
    }

    "@omit(omit.WhenEmpty, reason) on a List field is captured with both when and reason" in {
        val fields = summon[Schema[SA44Prod]].structure
            .asInstanceOf[Structure.Type.Product].fields
        fields.head.annotations.collectFirst { case o: omit => o } match
            case Some(ann) =>
                assert(ann.when == schema.omit.WhenEmpty, s"SA44: captured when must be omit.WhenEmpty; got ${ann.when}")
                assert(ann.reason == "skip", s"SA44: captured reason must be 'skip'; got ${ann.reason}")
            case None =>
                assert(false, s"SA44: omit annotation must be captured; got ${fields.head.annotations}")
        end match
        val encEmpty = Json.encode(SA44Prod(List.empty))
        assert(encEmpty == "{}", s"SA44: empty list omitted on wire: $encEmpty")
        val encPresent = Json.encode(SA44Prod(List(1, 2)))
        assert(encPresent == """{"xs":[1,2]}""", s"SA44: non-empty list emitted: $encPresent")
    }

    "@omit(omit.WhenNone) positional is captured with when == omit.WhenNone" in {
        val fields = summon[Schema[SA45Prod]].structure
            .asInstanceOf[Structure.Type.Product].fields
        fields.head.annotations.collectFirst { case o: omit => o } match
            case Some(ann) =>
                assert(ann.when == schema.omit.WhenNone, s"SA45: captured when must be omit.WhenNone; got ${ann.when}")
            case None =>
                assert(false, s"SA45: omit annotation must be captured; got ${fields.head.annotations}")
        end match
        val encEmpty = Json.encode(SA45Prod(Maybe.empty))
        assert(encEmpty == "{}", s"SA45: Maybe.empty omitted under WhenNone: $encEmpty")
        val encPresent = Json.encode(SA45Prod(Maybe(3)))
        assert(encPresent == """{"g":3}""", s"SA45: Maybe present emitted: $encPresent")
    }

    "a SchemaAnnotation with a defaulted constructor param is captured when param uses default" in {
        val fields = summon[Schema[SA46Prod]].structure
            .asInstanceOf[Structure.Type.Product].fields
        fields.head.annotations.collectFirst { case m: marked => m } match
            case Some(ann) =>
                assert(ann.tag == "x", s"SA46: default tag value must be 'x'; got ${ann.tag}")
            case None =>
                assert(false, s"SA46: marked annotation must be captured; got ${fields.head.annotations}")
        end match
    }

    "an object-reference transform round-trips through encode and decode" in {
        // Encode: the write transform must uppercase the field value on the wire.
        val encoded = Json.encode(ProbeMsg("ab"))
        assert(
            encoded == """{"code":"AB"}""",
            s"write transform must uppercase 'ab' to 'AB' on wire; got $encoded"
        )
        // Decode: the read transform must lowercase the wire value back to Scala side.
        val decoded = Json.decode[ProbeMsg]("""{"code":"AB"}""")
        assert(
            decoded == Result.succeed(ProbeMsg("ab")),
            s"read transform must lowercase 'AB' back to 'ab'; got $decoded"
        )
    }

    "WriteOnly transform applies on encode, derived codec used on decode (SA48)" in {
        val enc = Json.encode(SA48Prod(21))
        assert(enc == """{"x":42}""", s"WriteOnly.write must double 21 to 42 on wire: $enc")
        val dec = Json.decode[SA48Prod]("""{"x":7}""")
        assert(dec == Result.succeed(SA48Prod(7)), s"WriteOnly uses derived read, no transform on decode: $dec")
    }

    "ReadOnly transform: derived codec on encode, custom read applied on decode (SA49)" in {
        val enc = Json.encode(SA49Prod("hello"))
        assert(enc == """{"s":"hello"}""", s"ReadOnly uses derived write, no transform on encode: $enc")
        val dec = Json.decode[SA49Prod]("""{"s":"hello"}""")
        assert(dec == Result.succeed(SA49Prod("HELLO")), s"ReadOnly.read must uppercase on decode: $dec")
    }

    "omit.When predicate omits field when predicate returns true (SA50)" in {
        val encNeg = Json.encode(SA50Prod(-1))
        assert(encNeg == "{}", s"negative count must be omitted by predicate: $encNeg")
        val encPos = Json.encode(SA50Prod(5))
        assert(encPos == """{"count":5}""", s"positive count must appear on wire: $encPos")
        val schema = summon[Schema[SA50Prod]]
        assert(schema.omitPolicies.nonEmpty, s"omit.When must produce an omit policy entry: ${schema.omitPolicies}")
    }

    "omit.When: absent field decodes using Scala default (SA50)" in {
        val dec = Json.decode[SA50Prod]("""{"count":3}""")
        assert(dec == Result.succeed(SA50Prod(3)), s"present field round-trips: $dec")
        val decAbsent = Json.decode[SA50Prod]("{}")
        assert(decAbsent == Result.succeed(SA50Prod(0)), s"absent field uses Scala default 0: $decAbsent")
    }

    "Transformer.WriteOnly has no read member (illegal state unrepresentable)" in {
        typeCheckFailure("""
            object W extends kyo.schema.Transformer.WriteOnly[Int]:
                def write(value: Int, writer: kyo.Codec.Writer): Unit = writer.int(value)
            val _ = W.read
        """)("read is not a member")
    }

    "Transformer.ReadOnly has no write member (illegal state unrepresentable)" in {
        typeCheckFailure("""
            object R extends kyo.schema.Transformer.ReadOnly[Int]:
                def read(reader: kyo.Codec.Reader): Int = reader.int()
            val _ = R.write
        """)("write is not a member")
    }

    "@transform type mismatch is a compile error" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.schema.*
            object IntTx extends Transformer.Full[Int]:
                def write(value: Int, writer: kyo.Codec.Writer): Unit = writer.int(value)
                def read(reader: kyo.Codec.Reader): Int = reader.int()
            case class Bad(@transform(IntTx) s: String) derives Schema
        """)("Type mismatch")
    }

    "nested object reference transformer compiles and round-trips (SA51)" in {
        val enc = Json.encode(SA51Prod(5))
        assert(enc == """{"n":105}""", s"write transform must add 100: $enc")
        val dec = Json.decode[SA51Prod]("""{"n":105}""")
        assert(dec == Result.succeed(SA51Prod(5)), s"read transform must subtract 100: $dec")
    }

    "programmatic omit.when overrides annotation-derived omit.When policy (SA50)" in {
        val base = Schema[SA50Prod]
        val overridden = base.omit(_.count).when(v =>
            v match
                case Structure.Value.Integer(n) => n > 10
                case _                          => false
        )
        val encLow  = Json.encode(SA50Prod(5))(using overridden)
        val encHigh = Json.encode(SA50Prod(15))(using overridden)
        assert(encLow == """{"count":5}""", s"count=5 below threshold, must appear on wire: $encLow")
        assert(encHigh == "{}", s"count=15 above threshold, must be omitted: $encHigh")
    }

    "joint @transform and @omit(omit.When) on one case class both apply correctly (SA52)" in {
        // encode: write transform uppercases code; negative count is omitted by predicate.
        val encOmit = Json.encode(SA52Joint("ab", -1))
        assert(
            encOmit == """{"code":"AB"}""",
            s"write transform must uppercase 'ab'; negative count must be omitted by predicate: $encOmit"
        )
        // encode: positive count is not omitted; both fields appear on the wire.
        val encBoth = Json.encode(SA52Joint("ab", 5))
        assert(
            encBoth == """{"code":"AB","count":5}""",
            s"write transform must uppercase 'ab'; positive count must appear on wire: $encBoth"
        )
        // decode: read transform lowercases wire value; absent count uses Scala default 0.
        val decAbsent = Json.decode[SA52Joint]("""{"code":"AB"}""")
        assert(
            decAbsent == Result.succeed(SA52Joint("ab", 0)),
            s"read transform must lowercase 'AB' back to 'ab'; absent count must default to 0: $decAbsent"
        )
    }

    "@omit(omit.When(obj)) is captured in Structure.Field.annotations (SA50 annotation-capture)" in {
        val fields = summon[Schema[SA50Prod]].structure
            .asInstanceOf[Structure.Type.Product].fields
        val countField = fields.find(_.name == "count").getOrElse(
            fail("SA50Prod must have a 'count' field")
        )
        assert(
            countField.annotations.nonEmpty,
            s"SA50: count field must carry the @omit annotation in Structure.Field.annotations; got empty"
        )
        countField.annotations.collectFirst { case o: omit => o } match
            case Some(ann) =>
                assert(
                    ann.when == schema.omit.When(SA50NegativePred),
                    s"SA50: captured omit.when must be omit.When(SA50NegativePred); got ${ann.when}"
                )
            case None =>
                fail(s"SA50: omit annotation must be present in count field annotations; got ${countField.annotations}")
        end match
    }

    "@transform and @omit(omit.When) are both captured in Structure.Field.annotations (SA52)" in {
        val fields = summon[Schema[SA52Joint]].structure
            .asInstanceOf[Structure.Type.Product].fields
        val codeField = fields.find(_.name == "code").getOrElse(
            fail("SA52Joint must have a 'code' field")
        )
        val countField = fields.find(_.name == "count").getOrElse(
            fail("SA52Joint must have a 'count' field")
        )
        assert(
            codeField.annotations.exists(_.isInstanceOf[transform]),
            s"SA52: code field must carry the @transform annotation; got ${codeField.annotations}"
        )
        assert(
            countField.annotations.exists(_.isInstanceOf[omit]),
            s"SA52: count field must carry the @omit annotation; got ${countField.annotations}"
        )
        countField.annotations.collectFirst { case o: omit => o } match
            case Some(ann) =>
                assert(
                    ann.when == schema.omit.When(SA50NegativePred),
                    s"SA52: captured omit.when must be omit.When(SA50NegativePred); got ${ann.when}"
                )
            case None =>
                fail(s"SA52: omit annotation must be present in count field annotations; got ${countField.annotations}")
        end match
    }

    "a closure cannot be supplied as a @transform argument (only object references compile)" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.schema.*
            case class Bad(@transform((v: Int, w: kyo.Codec.Writer) => w.int(v)) n: Int) derives Schema
        """)("Transformer")
    }

    "Product.annotationsOf returns all matching annotations in order and Chunk.empty when none match" in {
        val prod = Structure.Type.Product(
            "TestProd",
            Tag[Any],
            Chunk.empty,
            Chunk.empty,
            Chunk(new tagged("p1"), new tagged("p2"), new sampleAnn())
        )
        val taggedAnns = prod.annotationsOf[tagged].map(_.note)
        assert(taggedAnns == Chunk("p1", "p2"), s"Product.annotationsOf[tagged] must be Chunk(p1, p2) in order: $taggedAnns")

        val emptyProd = Structure.Type.Product("E", Tag[Any], Chunk.empty, Chunk.empty)
        val emptyAnns = emptyProd.annotationsOf[tagged]
        assert(emptyAnns == Chunk.empty, s"Product.annotationsOf on annotation-free product must be Chunk.empty: $emptyAnns")
    }

    "Sum.annotationOf returns the first annotation of type A or Maybe.empty when absent" in {
        val sum     = Schema[SA14bSum].structure.asInstanceOf[Structure.Type.Sum]
        val present = sum.annotationOf[tagged].map(_.note)
        assert(present == Maybe("s"), s"Sum.annotationOf[tagged] on SA14bSum must be Maybe(\"s\"): $present")

        val absent = sum.annotationOf[sampleAnn]
        assert(absent == Maybe.empty, s"Sum.annotationOf[sampleAnn] when absent must be Maybe.empty: $absent")
    }

    "Sum.annotationsOf returns all matching annotations in order and Chunk.empty when none match" in {
        val multiSum = Structure.Type.Sum(
            "TestSum",
            Tag[Any],
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk(new tagged("s1"), new tagged("s2"))
        )
        val taggedAnns = multiSum.annotationsOf[tagged].map(_.note)
        assert(taggedAnns == Chunk("s1", "s2"), s"Sum.annotationsOf[tagged] must be Chunk(s1, s2) in order: $taggedAnns")

        val emptySum  = Structure.Type.Sum("E", Tag[Any], Chunk.empty, Chunk.empty, Chunk.empty)
        val emptyAnns = emptySum.annotationsOf[tagged]
        assert(emptyAnns == Chunk.empty, s"Sum.annotationsOf on annotation-free sum must be Chunk.empty: $emptyAnns")
    }

    "Field.annotationOf returns the first annotation of type A or Maybe.empty when absent" in {
        val carrying = Structure.Field("f", Schema[Int].structure, Maybe.empty, Maybe.empty, false, Chunk(new tagged("t1")))
        val present  = carrying.annotationOf[tagged].map(_.note)
        assert(present == Maybe("t1"), s"Field.annotationOf[tagged] must be Maybe(\"t1\"): $present")

        val absent = carrying.annotationOf[sampleAnn]
        assert(absent == Maybe.empty, s"Field.annotationOf[sampleAnn] when absent must be Maybe.empty: $absent")
    }

    "Variant.annotationOf returns the first annotation of type A or Maybe.empty when absent" in {
        val carrying = Structure.Variant("V", Schema[Int].structure, Chunk(new tagged("v1")))
        val present  = carrying.annotationOf[tagged].map(_.note)
        assert(present == Maybe("v1"), s"Variant.annotationOf[tagged] must be Maybe(\"v1\"): $present")

        val absent = carrying.annotationOf[sampleAnn]
        assert(absent == Maybe.empty, s"Variant.annotationOf[sampleAnn] when absent must be Maybe.empty: $absent")
    }

    "Variant.annotationsOf returns all matching annotations in order and Chunk.empty when none match" in {
        val multi      = Structure.Variant("V", Schema[Int].structure, Chunk(new tagged("v1"), new tagged("v2"), new sampleAnn()))
        val taggedAnns = multi.annotationsOf[tagged].map(_.note)
        assert(taggedAnns == Chunk("v1", "v2"), s"Variant.annotationsOf[tagged] must be Chunk(v1, v2) in order: $taggedAnns")

        val empty     = Structure.Variant("E", Schema[Int].structure)
        val emptyAnns = empty.annotationsOf[tagged]
        assert(emptyAnns == Chunk.empty, s"Variant.annotationsOf on annotation-free variant must be Chunk.empty: $emptyAnns")
    }

    "Type.Annotated matches a Product and a Sum but is refutable on a Primitive" in {
        val prodTpe = Schema[SA14bProd].structure // @tagged("p") product
        val sumTpe  = Schema[SA14bSum].structure  // @tagged("s") sum
        val primTpe = Schema[Int].structure       // primitive, no annotations

        val prodMatched = prodTpe match
            case Structure.Type.Annotated(name, anns) =>
                assert(name == "SA14bProd", s"product name must be SA14bProd: $name")
                assert(anns.size == 1, s"product must carry exactly one annotation: $anns")
                assert(anns.head.isInstanceOf[tagged], s"product annotation must be a tagged: ${anns.head}")
                assert(anns.head.asInstanceOf[tagged].note == "p", s"product annotation note must be 'p': ${anns.head}")
                true
            case _ => false
        assert(prodMatched, "Type.Annotated must match SA14bProd (a Product)")

        val sumMatched = sumTpe match
            case Structure.Type.Annotated(name, anns) =>
                assert(name == "SA14bSum", s"sum name must be SA14bSum: $name")
                assert(anns.size == 1, s"sum must carry exactly one annotation: $anns")
                assert(anns.head.isInstanceOf[tagged], s"sum annotation must be a tagged: ${anns.head}")
                assert(anns.head.asInstanceOf[tagged].note == "s", s"sum annotation note must be 's': ${anns.head}")
                true
            case _ => false
        assert(sumMatched, "Type.Annotated must match SA14bSum (a Sum)")

        val primMatched = primTpe match
            case Structure.Type.Annotated(_, _) => true
            case _                              => false
        assert(!primMatched, "Type.Annotated must NOT match a Primitive type")
    }

    "Field.Annotated and Variant.Annotated project name and annotations irrefutably" in {
        val fieldAnn   = new tagged("f")
        val variantAnn = new tagged("v")
        val field      = Structure.Field("myField", Schema[Int].structure, Maybe.empty, Maybe.empty, false, Chunk(fieldAnn))
        val variant    = Structure.Variant("MyVariant", Schema[Int].structure, Chunk(variantAnn))

        val fieldResult = (field: @unchecked) match
            case Structure.Field.Annotated(name, anns) => (name, anns)
        assert(fieldResult._1 == "myField", s"field name must be 'myField': ${fieldResult._1}")
        assert(fieldResult._2.size == 1, s"field must carry exactly one annotation: ${fieldResult._2}")
        assert(fieldResult._2.head.isInstanceOf[tagged], s"field annotation must be a tagged: ${fieldResult._2.head}")
        assert(fieldResult._2.head.asInstanceOf[tagged].note == "f", s"field annotation note must be 'f': ${fieldResult._2.head}")

        val variantResult = (variant: @unchecked) match
            case Structure.Variant.Annotated(name, anns) => (name, anns)
        assert(variantResult._1 == "MyVariant", s"variant name must be 'MyVariant': ${variantResult._1}")
        assert(variantResult._2.size == 1, s"variant must carry exactly one annotation: ${variantResult._2}")
        assert(variantResult._2.head.isInstanceOf[tagged], s"variant annotation must be a tagged: ${variantResult._2.head}")
        assert(variantResult._2.head.asInstanceOf[tagged].note == "v", s"variant annotation note must be 'v': ${variantResult._2.head}")
    }

    "annotationOf returns the first marker on a carrying node and Maybe.empty on a non-carrying node" in {
        val carrying = Schema[SA14bProd].structure.asInstanceOf[Structure.Type.Product] // @tagged("p")
        val plain    = Schema[SA15Prod].structure.asInstanceOf[Structure.Type.Product]  // no type annotation

        val present = carrying.annotationOf[tagged].map(_.note)
        assert(present == Maybe("p"), s"carrying product annotationOf[tagged] note must be Maybe(\"p\"): $present")

        val absent = plain.annotationOf[tagged]
        assert(absent == Maybe.empty, s"plain product annotationOf[tagged] must be Maybe.empty: $absent")
    }

    "annotationsOf returns every matching instance in order and Chunk.empty when none match" in {
        final class other() extends scala.annotation.StaticAnnotation
        val field = Structure.Field(
            "f",
            Schema[Int].structure,
            Maybe.empty,
            Maybe.empty,
            false,
            Chunk(new tagged("a"), new tagged("b"), new other())
        )
        val emptyField = Structure.Field("g", Schema[Int].structure, Maybe.empty, Maybe.empty, false)

        val taggedAnns = field.annotationsOf[tagged].map(_.note)
        assert(taggedAnns == Chunk("a", "b"), s"annotationsOf[tagged] must be Chunk(\"a\",\"b\") in order: $taggedAnns")

        val otherAnns = field.annotationsOf[other]
        assert(otherAnns.size == 1, s"annotationsOf[other] must yield exactly one: $otherAnns")
        assert(otherAnns.head.isInstanceOf[other], s"annotationsOf[other] must contain an other instance: $otherAnns")

        val emptyAnns = emptyField.annotationsOf[tagged]
        assert(emptyAnns == Chunk.empty, s"annotationsOf on empty-annotation field must be Chunk.empty: $emptyAnns")
    }

    "fieldsWith and variantsWith return exactly the carrying elements paired with their annotation" in {
        val f0     = Structure.Field("f0", Schema[Int].structure, Maybe.empty, Maybe.empty, false, Chunk(new tagged("x")))
        val f1     = Structure.Field("f1", Schema[Int].structure, Maybe.empty, Maybe.empty, false)
        val f2     = Structure.Field("f2", Schema[Int].structure, Maybe.empty, Maybe.empty, false, Chunk(new tagged("z")))
        val fields = Chunk(f0, f1, f2)

        val v0       = Structure.Variant("V0", Schema[Int].structure)
        val v1       = Structure.Variant("V1", Schema[Int].structure, Chunk(new tagged("w")))
        val variants = Chunk(v0, v1)

        val fw = fields.fieldsWith[tagged]
        assert(fw.size == 2, s"fieldsWith must return exactly 2 entries (f0 and f2): ${fw.size}")
        assert(fw(0)._1.name == "f0", s"first entry must be f0: ${fw(0)._1.name}")
        assert(fw(0)._2.note == "x", s"first annotation note must be 'x': ${fw(0)._2.note}")
        assert(fw(1)._1.name == "f2", s"second entry must be f2: ${fw(1)._1.name}")
        assert(fw(1)._2.note == "z", s"second annotation note must be 'z': ${fw(1)._2.note}")

        val vw = variants.variantsWith[tagged]
        assert(vw.size == 1, s"variantsWith must return exactly 1 entry (V1): ${vw.size}")
        assert(vw(0)._1.name == "V1", s"variant entry must be V1: ${vw(0)._1.name}")
        assert(vw(0)._2.note == "w", s"variant annotation note must be 'w': ${vw(0)._2.note}")
    }

    "programmatic transformField overrides annotation-derived @transform on ProbeMsg" in {
        val base = Schema[ProbeMsg]
        val overridden = base.transformField(_.code)((v, w) => w.string(v.toLowerCase))(reader =>
            discard(reader.string()); "PROGRAMMATIC"
        )
        val enc = Json.encode(ProbeMsg("Hello"))(using overridden)
        assert(enc == """{"code":"hello"}""", s"programmatic write (lowercase) must win over @transform (uppercase): $enc")
        val dec =
            given Schema[ProbeMsg] = overridden
            Json.decode[ProbeMsg]("""{"code":"X"}""")
        assert(dec == Result.succeed(ProbeMsg("PROGRAMMATIC")), s"programmatic read must win over @transform: $dec")
    }

    "@proto.fieldNumber pins the field number on the derived schema, overriding the hash default (PFN)" in {
        val schema = Schema[PFNPinned]
        assert(
            5 != kyo.internal.CodecMacro.fieldId("x"),
            s"test premise: pinned 5 must differ from the hash default ${kyo.internal.CodecMacro.fieldId("x")}"
        )
        assert(schema.fieldId("x") == 5, s"x must be pinned to 5: ${schema.fieldId("x")}")
        assert(
            schema.fieldId("y") == kyo.internal.CodecMacro.fieldId("y"),
            s"un-annotated y must keep the hash default ${kyo.internal.CodecMacro.fieldId("y")}: ${schema.fieldId("y")}"
        )
    }

    "@proto.fieldNumber round-trips through Protobuf with the pinned number on the wire (PFN)" in {
        val value = PFNPinned(7, "hi")
        val bytes = Protobuf.encode(value)
        // The first field written is x; its wire tag must carry the pinned number 5, not the hash.
        val reader     = new kyo.internal.ProtobufReader(bytes.toArray)
        val firstField = reader.field()
        assert(firstField == "5", s"x must be written with the pinned wire number 5, got '$firstField'")
        val result = Protobuf.decode[PFNPinned](bytes)
        assert(result == Result.Success(value), s"pinned-field round-trip failed: $result")
    }

    "Protobuf.fieldNumberAudit reports pinned=true for an @proto.fieldNumber field and false otherwise (PFN)" in {
        val rows = Protobuf.fieldNumberAudit[PFNPinned]
        val xRow = rows.find(_.name == "x").getOrElse(fail(s"missing 'x' row: $rows"))
        val yRow = rows.find(_.name == "y").getOrElse(fail(s"missing 'y' row: $rows"))
        assert(xRow.pinned == true, s"x must be pinned: $xRow")
        assert(xRow.number == 5, s"x number must be the pinned 5: $xRow")
        assert(yRow.pinned == false, s"un-annotated y must not be pinned: $yRow")
        assert(
            yRow.number == kyo.internal.CodecMacro.fieldId("y"),
            s"y number must be the hash default ${kyo.internal.CodecMacro.fieldId("y")}: $yRow"
        )
    }

    "programmatic .fieldId wins over @proto.fieldNumber (PFN precedence)" in {
        given overridden: Schema[PFNPinned] = Schema[PFNPinned].fieldId(_.x)(9)
        assert(overridden.fieldId("x") == 9, s"programmatic 9 must win over annotation 5: ${overridden.fieldId("x")}")
        val rows = Protobuf.fieldNumberAudit[PFNPinned]
        val xRow = rows.find(_.name == "x").getOrElse(fail(s"missing 'x' row: $rows"))
        assert(xRow.number == 9, s"audit must report the programmatic 9: $xRow")
        assert(xRow.pinned == true, s"x must still be pinned: $xRow")
        val value  = PFNPinned(3, "z")
        val bytes  = Protobuf.encode(value)
        val result = Protobuf.decode[PFNPinned](bytes)
        assert(result == Result.Success(value), s"programmatic-override round-trip failed: $result")
    }

    "two distinct @proto.fieldNumber pins round-trip with their pinned numbers (PFN)" in {
        val schema = Schema[PFNTwoPins]
        assert(schema.fieldId("a") == 11, s"a must be pinned to 11: ${schema.fieldId("a")}")
        assert(schema.fieldId("b") == 22, s"b must be pinned to 22: ${schema.fieldId("b")}")
        val value  = PFNTwoPins(100, "two")
        val bytes  = Protobuf.encode(value)
        val reader = new kyo.internal.ProtobufReader(bytes.toArray)
        assert(reader.field() == "11", "first field a must carry pinned wire number 11")
        val result = Protobuf.decode[PFNTwoPins](bytes)
        assert(result == Result.Success(value), s"two-pin round-trip failed: $result")
    }

    "@proto.fieldNumber composes with a @rename'd sibling field on the Protobuf codec (PFN)" in {
        val schema = Schema[PFNRenameCompose]
        assert(schema.fieldId("id") == 4, s"id must be pinned to 4: ${schema.fieldId("id")}")
        val value  = PFNRenameCompose(42, "hello")
        val bytes  = Protobuf.encode(value)
        val result = Protobuf.decode[PFNRenameCompose](bytes)
        assert(result == Result.Success(value), s"pin + rename compose round-trip failed: $result")
    }

    "Protobuf.fieldNumberAudit reports a renamed-but-unpinned field as pinned=false with its rename-invariant number (PFN)" in {
        val rows     = Protobuf.fieldNumberAudit[PFNRenameCompose]
        val idRow    = rows.find(_.name == "id").getOrElse(fail(s"missing 'id' row: $rows"))
        val labelRow = rows.find(_.name == "label").getOrElse(fail(s"missing 'label' row: $rows"))
        assert(idRow.pinned == true, s"id carries @proto.fieldNumber(4) and must be pinned: $idRow")
        assert(idRow.number == 4, s"id number must be the pinned 4: $idRow")
        assert(labelRow.pinned == false, s"renamed-but-unpinned label must not be pinned: $labelRow")
        assert(
            labelRow.number == kyo.internal.CodecMacro.fieldId("wire_label"),
            s"label number must be the rename-invariant hash ${kyo.internal.CodecMacro.fieldId("wire_label")}: $labelRow"
        )
    }

    "@proto.fieldNumber and @rename on the SAME field: the pin holds on the wire, in the audit, and round-trips (PFN)" in {
        val schema = Schema[PFNRenameSamePin]
        assert(schema.fieldId("x") == 7, s"x must report the pinned number 7 via fieldId: ${schema.fieldId("x")}")
        // The rename's hash default must differ from the pin, or the test proves nothing.
        val renameHash = kyo.internal.CodecMacro.fieldId("wire_x")
        assert(7 != renameHash, s"test premise: pinned 7 must differ from hash(wire_x) $renameHash")

        val value = PFNRenameSamePin(99, "hi")
        val bytes = Protobuf.encode(value)

        // Read every top-level wire tag. The pinned field x must appear with field number 7,
        // and the rename-hash default must NOT appear anywhere.
        val reader      = new kyo.internal.ProtobufReader(bytes.toArray)
        val wireNumbers = scala.collection.mutable.ListBuffer.empty[Int]
        var guard       = 0
        while reader.hasNextField() && guard < 16 do
            wireNumbers += reader.field().toInt
            reader.skip()
            guard += 1
        end while
        assert(
            wireNumbers.contains(7),
            s"x must be written with the pinned wire number 7, got wire field numbers $wireNumbers"
        )
        assert(
            !wireNumbers.contains(renameHash),
            s"the rename-hash default $renameHash must not reach the wire (the pin must win), got $wireNumbers"
        )

        val rows = Protobuf.fieldNumberAudit[PFNRenameSamePin]
        val xRow = rows.find(_.name == "x").getOrElse(fail(s"missing 'x' row: $rows"))
        assert(xRow.number == 7, s"audit must report the pinned number 7 for x: $xRow")
        assert(xRow.pinned == true, s"audit must report x as pinned: $xRow")

        val result = Protobuf.decode[PFNRenameSamePin](bytes)
        assert(result == Result.Success(value), s"pin + rename on the same field round-trip failed: $result")
    }

    "@proto.fieldNumber rejects a non-positive number at derivation (PFN)" in {
        assert(
            !scala.compiletime.testing.typeChecks(
                """
                case class PFNBad(@kyo.schema.proto.fieldNumber(0) x: Int) derives kyo.Schema
                """
            ),
            "deriving Schema with @proto.fieldNumber(0) must be a compile error (proto numbers are >= 1)"
        )
        assert(
            scala.compiletime.testing.typeChecks(
                """
                case class PFNOk(@kyo.schema.proto.fieldNumber(1) x: Int) derives kyo.Schema
                """
            ),
            "deriving Schema with @proto.fieldNumber(1) must compile"
        )
    }

    "@proto.fieldNumber rejects a non-constant number at derivation (PFN)" in {
        assert(
            !scala.compiletime.testing.typeChecks(
                """
                object PFNDynHelper { def dyn: Int = 7 }
                case class PFNDyn(@kyo.schema.proto.fieldNumber(PFNDynHelper.dyn) x: Int) derives kyo.Schema
                """
            ),
            "deriving Schema with a non-constant @proto.fieldNumber arg must be a compile error: the pin must resolve at compile time or wire interop silently breaks"
        )
    }

    "@proto.fieldNumber is captured and introspectable via Field.annotationOf (PFN)" in {
        val fields = Schema[PFNPinned].structure.asInstanceOf[Structure.Type.Product].fields
        val xField = fields.find(_.name == "x").getOrElse(fail(s"missing 'x' field: $fields"))
        val ann    = xField.annotationOf[kyo.schema.proto.fieldNumber]
        assert(ann.map(_.number) == Maybe(5), s"x must carry @proto.fieldNumber(5): ${ann.map(_.number)}")
        val yField = fields.find(_.name == "y").getOrElse(fail(s"missing 'y' field: $fields"))
        assert(
            yField.annotationOf[kyo.schema.proto.fieldNumber] == Maybe.empty,
            s"un-annotated y must carry no @proto.fieldNumber: ${yField.annotationOf[kyo.schema.proto.fieldNumber]}"
        )
    }

end SchemaAnnotationTest
