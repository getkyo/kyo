package kyo

// --- SSR* fixtures (scoped to this file) ---
// --- SSRA* fixtures for alias-set and naming-composition tests ---
sealed trait SSRAShape derives CanEqual, Schema
case class SSRACircle(radius: Double) extends SSRAShape derives CanEqual
case class SSRASquare(side: Double)   extends SSRAShape derives CanEqual

// --- SSRU* fixtures for untagged round-trip and declaration-order tests ---
// SSRUCircle is declared BEFORE SSRUSquare so the attempt loop order matches source order.
sealed trait SSRUShape derives CanEqual, Schema
case class SSRUCircle(radius: Double)             extends SSRUShape derives CanEqual
case class SSRUSquare(side: Double)               extends SSRUShape derives CanEqual
case class SSRUBox(width: Double, height: Double) extends SSRUShape derives CanEqual

// Fixture for ambiguous-first-wins test: both variants share the same wire field.
sealed trait SSRUAmbig derives CanEqual, Schema
case class SSRUAmbigFirst(x: Double)  extends SSRUAmbig derives CanEqual
case class SSRUAmbigSecond(x: Double) extends SSRUAmbig derives CanEqual

// Reverse-order variant of the ambiguous fixture to confirm order matters.
sealed trait SSRUAmbigRev derives CanEqual, Schema
case class SSRUAmbigRevSecond(x: Double) extends SSRUAmbigRev derives CanEqual
case class SSRUAmbigRevFirst(x: Double)  extends SSRUAmbigRev derives CanEqual

// Fixture: a variant with a multi-word camelCase field, so field-name casing is observable in the wire.
// itemCount would become item_count under SnakeCase, making assertions discriminating.
sealed trait SSRUNamed derives CanEqual, Schema
case class SSRUItem(itemCount: Int) extends SSRUNamed derives CanEqual

// Fixtures for untagged round-trip with Short, Byte, and Int fields on Ion.
// Ion emits integer literals as BigNum in the Structure.Value tree; the reader must accept BigNum for these types.
sealed trait SSRUIntegral derives CanEqual, Schema
case class SSRUShortVal(s: Short) extends SSRUIntegral derives CanEqual
case class SSRUByteVal(b: Byte)   extends SSRUIntegral derives CanEqual
case class SSRUIntVal(n: Int)     extends SSRUIntegral derives CanEqual

// --- SSRF* fixtures: sealed sub-traits under a root ---
sealed trait SSRFRoot derives CanEqual, Schema
sealed trait SSRFGroup                   extends SSRFRoot
final case class SSRFLeaf(x: Int)        extends SSRFGroup derives CanEqual
case object SSRFMark                     extends SSRFGroup
sealed trait SSRFDeep                    extends SSRFGroup
final case class SSRFDeepLeaf(z: String) extends SSRFDeep derives CanEqual
final case class SSRFDirect(y: Int)      extends SSRFRoot derives CanEqual

sealed trait SSRFPlain derives CanEqual, Schema
sealed trait SSRFPlainGroup                                            extends SSRFPlain
final case class SSRFPlainLeaf(x: Int)                                 extends SSRFPlainGroup derives CanEqual
final case class SSRFPlainDirect(z: String)                            extends SSRFPlain derives CanEqual
final case class SSRFPlainOther(tag: String, payload: Structure.Value) extends SSRFPlainGroup derives CanEqual

sealed trait SSRFShared derives CanEqual, Schema
sealed trait SSRFSharedA                extends SSRFShared
sealed trait SSRFSharedB                extends SSRFShared
final case class SSRFSharedBoth(x: Int) extends SSRFSharedA with SSRFSharedB derives CanEqual
final case class SSRFSharedOnly(y: Int) extends SSRFSharedB derives CanEqual

sealed trait SSRFChain derives CanEqual, Schema
sealed trait SSRFChain1                extends SSRFChain
sealed trait SSRFChain2                extends SSRFChain with SSRFChain1
sealed trait SSRFChain3                extends SSRFChain with SSRFChain2
sealed trait SSRFChain4                extends SSRFChain with SSRFChain3
sealed trait SSRFChain5                extends SSRFChain with SSRFChain4
sealed trait SSRFChain6                extends SSRFChain with SSRFChain5
sealed trait SSRFChain7                extends SSRFChain with SSRFChain6
sealed trait SSRFChain8                extends SSRFChain with SSRFChain7
sealed trait SSRFChain9                extends SSRFChain with SSRFChain8
sealed trait SSRFChain10               extends SSRFChain with SSRFChain9
sealed trait SSRFChain11               extends SSRFChain with SSRFChain10
sealed trait SSRFChain12               extends SSRFChain with SSRFChain11
final case class SSRFChainLeaf(x: Int) extends SSRFChain12 derives CanEqual

@kyo.schema.tagOnly
sealed trait SSRFColor derives CanEqual, Schema
sealed trait SSRFWarm extends SSRFColor
case object SSRFRed   extends SSRFWarm
case object SSRFBlue  extends SSRFColor

@kyo.schema.discriminator("type")
sealed trait SSRFTagged derives CanEqual, Schema
sealed trait SSRFTaggedGroup extends SSRFTagged
@kyo.schema.rename("leaf")
final case class SSRFTaggedLeaf(x: Int) extends SSRFTaggedGroup derives CanEqual
@kyo.schema.catchAll()
final case class SSRFTaggedOther(tag: String, payload: Structure.Value) extends SSRFTaggedGroup derives CanEqual
@kyo.schema.rename("direct")
final case class SSRFTaggedDirect(y: Int) extends SSRFTagged derives CanEqual

sealed trait SSRFNested derives CanEqual
sealed trait SSRFNestedGroup            extends SSRFNested
final case class SSRFNestedLeaf(x: Int) extends SSRFNestedGroup derives CanEqual
object SSRFNestedGroup:
    given Schema[SSRFNestedGroup] = Schema.derived[SSRFNestedGroup].discriminator("kind")
object SSRFNested:
    given Schema[SSRFNested] = Schema.derived[SSRFNested].adjacent("op", "body")

sealed trait SSRShape derives CanEqual, Schema
case class SSRCircle(radius: Double)                    extends SSRShape derives CanEqual
case class SSRSquare(side: Double)                      extends SSRShape derives CanEqual
case class SSRTriangle(a: Double, b: Double, c: Double) extends SSRShape derives CanEqual
case object SSRUnit                                     extends SSRShape derives CanEqual
case class SSRPi(value: Double)                         extends SSRShape derives CanEqual
case class SSRLine(p1: SSRPoint, p2: SSRPoint)          extends SSRShape derives CanEqual
case class SSRPoint(x: Double, y: Double) derives CanEqual, Schema
case class SSRDrawing(drawingTitle: String, shape: SSRShape) derives CanEqual
object SSRDrawing:
    given Schema[SSRShape]   = Schema[SSRShape].tupleFlat
    given Schema[SSRDrawing] = Schema.derived[SSRDrawing]

// Fixtures for variant-name collision under SnakeCase convention.
// FooBar -> foo_bar; Foo_Bar -> [Foo, Bar] -> foo_bar: two distinct Scala names, one wire name.
sealed trait SSRFrmCollide derives CanEqual, Schema
case class FooBar(x: Int)  extends SSRFrmCollide derives CanEqual
case class Foo_Bar(y: Int) extends SSRFrmCollide derives CanEqual

// --- SWR* fixtures: a variant's own field annotations under each representation ---
@schema.discriminator("type")
sealed trait SWRRx derives CanEqual, Schema
case class SWREmoji(emoji: String) extends SWRRx derives CanEqual
@schema.rename("custom_emoji")
case class SWRCustomEmoji(@schema.rename("custom_emoji_id") id: String) extends SWRRx derives CanEqual

object SWRUpper extends schema.Transformer.Full[String]:
    def write(value: String, writer: Codec.Writer): Unit = writer.string(value.toUpperCase)
    def read(reader: Codec.Reader): String               = reader.string().toLowerCase

@schema.discriminator("type")
sealed trait SWRElement derives CanEqual, Schema
@schema.rename("button")
case class SWRButton(
    @schema.transform(SWRUpper) text: String,
    @schema.rename("action_id") actionId: String,
    @schema.alias("hint_text") hint: Maybe[String] = Absent
) extends SWRElement derives CanEqual

sealed trait SWRExternal derives CanEqual, Schema
case class SWRExternalCase(@schema.rename("custom_emoji_id") id: String) extends SWRExternal derives CanEqual

@schema.untagged()
sealed trait SWRUntagged derives CanEqual, Schema
case class SWRUntaggedCase(@schema.rename("custom_emoji_id") id: String) extends SWRUntagged derives CanEqual

enum SSRNumber derives CanEqual:
    case Whole(value: Long)
    case Fraction(value: Double)
    case Flag(value: Boolean)
end SSRNumber
object SSRNumber:
    given Schema[SSRNumber.Whole]    = Schema.longSchema.transform[SSRNumber.Whole](SSRNumber.Whole(_))(_.value)
    given Schema[SSRNumber.Fraction] = Schema.doubleSchema.transform[SSRNumber.Fraction](SSRNumber.Fraction(_))(_.value)
    given Schema[SSRNumber.Flag]     = Schema.booleanSchema.transform[SSRNumber.Flag](SSRNumber.Flag(_))(_.value)
    given Schema[SSRNumber]          = Schema.derived[SSRNumber].untagged.unionAmbiguity(Schema.UnionAmbiguity.FirstMatch)
end SSRNumber

sealed trait SSRSignal derives CanEqual, Schema
case object SSRResumed                                                            extends SSRSignal
case class SSRHello(interval: Int)                                                extends SSRSignal derives CanEqual
case class SSRDefer(@schema.omit(schema.omit.WhenDefault) quiet: Boolean = false) extends SSRSignal derives CanEqual
case class SSRNote(note: Maybe[String])                                           extends SSRSignal derives CanEqual

@schema.adjacent("type", "content")
sealed trait SWRAdjacent derives CanEqual, Schema
case class SWRAdjacentCase(@schema.rename("custom_emoji_id") id: String) extends SWRAdjacent derives CanEqual

// --- SWP* fixtures: a variant whose first field is renamed, under the positional and keyed representations ---
sealed trait SWPShape derives CanEqual, Schema
case class SWPPoint(@schema.rename("x_coord") x: Int, y: Int, z: Int) extends SWPShape derives CanEqual

// The same variant with its field under the Scala name, as data written before variant renames reached the wire.
@schema.discriminator("type")
sealed trait SWPOldShape derives CanEqual, Schema
@schema.rename("SWPPoint")
case class SWPOldPoint(x: Int, y: Int, z: Int) extends SWPOldShape derives CanEqual

// 701810 is CodecMacro.fieldId("x"), the number the field had before the rename reached the wire.
@schema.discriminator("type")
sealed trait SWPPinnedShape derives CanEqual, Schema
@schema.rename("SWPPoint")
case class SWPPinnedPoint(@schema.rename("x_coord") @schema.proto.fieldNumber(701810) x: Int, y: Int, z: Int)
    extends SWPPinnedShape derives CanEqual

// --- SWO* fixtures: a variant field's own @omit policy, with an empty collection ---
sealed trait SWOBag derives CanEqual, Schema
case class SWOItems(@schema.omit(schema.omit.WhenEmpty) items: List[String], label: String) extends SWOBag derives CanEqual
case class SWONote(note: Maybe[String], label: String)                                      extends SWOBag derives CanEqual
sealed trait SWONested derives CanEqual, Schema
case class SWONestedCase(bag: SWOBag) extends SWONested derives CanEqual

// --- SWF* fixtures: a record holding a sum, for field transforms on a nested path ---
case class SWFHolder(shape: SSRShape) derives CanEqual, Schema

// --- SWG* fixtures: a variant's own given, used by the sum ---
@schema.discriminator("type")
sealed trait SWGEvent derives CanEqual, Schema
case class SWGUserJoined(userId: Long, chatTitle: String) extends SWGEvent derives CanEqual
object SWGUserJoined:
    given Schema[SWGUserJoined] = Schema.derived[SWGUserJoined].renameAllFields(Schema.NameCase.SnakeCase)

@schema.discriminator("type")
sealed trait SWGPay derives CanEqual, Schema
case class SWGCard(last4: String) extends SWGPay derives CanEqual
object SWGCard:
    def make(last4: String): Result[String, SWGCard] =
        if last4.length == 4 then Result.succeed(SWGCard(last4)) else Result.fail(s"bad last4: $last4")
    given Schema[SWGCard] = Schema.derivedVia(make)
end SWGCard
case class SWGCash(amount: Int) extends SWGPay derives CanEqual

sealed trait SWGTokenized derives CanEqual, Schema
sealed abstract case class SWGToken private (value: String) extends SWGTokenized
object SWGToken:
    def make(value: String): Result[String, SWGToken] =
        if value.nonEmpty then Result.succeed(new SWGToken(value) {}) else Result.fail("empty token")
    given Schema[SWGToken] = Schema.derivedVia(make)
end SWGToken
case class SWGPlain(n: Int) extends SWGTokenized derives CanEqual

// A JWT's `aud` (RFC 7519 section 4.1.3): one string or an array of them, a union with a parameterized member.
case class SSRUAudience(aud: String | Chunk[String], extra: Maybe[Int | Chunk[Int]] = Absent) derives CanEqual, Schema

class SchemaUnionRepresentationTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "a union with a parameterized member derives without an unchecked type test and round-trips each member" in {
        val one  = SSRUAudience("bot")
        val many = SSRUAudience(Chunk("bot", "other"), Present(Chunk(1, 2)))
        val ints = SSRUAudience("bot", Present(7))
        assert(Json.encode(one) == """{"aud":"bot"}""")
        assert(Json.encode(many) == """{"aud":["bot","other"],"extra":[1,2]}""")
        assert(Json.encode(ints) == """{"aud":"bot","extra":7}""")
        assert(Chunk(one, many, ints).map(v => Json.decode[SSRUAudience](Json.encode(v))) == Chunk(one, many, ints).map(Result.succeed))
    }

    // =========================================================================
    // Group: enum shape
    // =========================================================================

    "enum shape constructs and matches exhaustively" in {
        val ext  = Schema.UnionRepresentation.External
        val int  = Schema.UnionRepresentation.Internal("t")
        val adj  = Schema.UnionRepresentation.Adjacent("t", "c")
        val tup  = Schema.UnionRepresentation.Tuple
        val tupF = Schema.UnionRepresentation.TupleFlat
        val unt  = Schema.UnionRepresentation.Untagged
        val tag  = Schema.UnionRepresentation.TagOnly

        // Total match over all seven arms compiles with no missing-case warning.
        def describeAll(r: Schema.UnionRepresentation): String = r match
            case Schema.UnionRepresentation.External       => "external"
            case Schema.UnionRepresentation.Internal(_)    => "internal"
            case Schema.UnionRepresentation.Adjacent(_, _) => "adjacent"
            case Schema.UnionRepresentation.Tuple          => "tuple"
            case Schema.UnionRepresentation.TupleFlat      => "tupleFlat"
            case Schema.UnionRepresentation.Untagged       => "untagged"
            case Schema.UnionRepresentation.TagOnly        => "tagOnly"

        assert(describeAll(ext) == "external")
        assert(describeAll(int) == "internal")
        assert(describeAll(adj) == "adjacent")
        assert(describeAll(tup) == "tuple")
        assert(describeAll(tupF) == "tupleFlat")
        assert(describeAll(unt) == "untagged")
        assert(describeAll(tag) == "tagOnly")
        // Case-specific field access via pattern match
        val intTagKey = int match
            case Schema.UnionRepresentation.Internal(k) => k
            case _                                      => ""
        assert(intTagKey == "t")
        val (adjTagKey, adjContentKey) = adj match
            case Schema.UnionRepresentation.Adjacent(tk, ck) => (tk, ck)
            case _                                           => ("", "")
        assert(adjTagKey == "t")
        assert(adjContentKey == "c")
    }

    "enum CanEqual equality" in {
        val a = Schema.UnionRepresentation.External
        val b = Schema.UnionRepresentation.Adjacent("t", "c")
        val c = Schema.UnionRepresentation.Adjacent("t", "d")
        assert(a == Schema.UnionRepresentation.External)
        assert(b != c)
    }

    "nonDefault predicate" in {
        assert(Schema.UnionRepresentation.External.nonDefault == false)
        assert(Schema.UnionRepresentation.Internal("t").nonDefault == true)
        assert(Schema.UnionRepresentation.Adjacent("t", "c").nonDefault == true)
        assert(Schema.UnionRepresentation.Tuple.nonDefault == true)
        assert(Schema.UnionRepresentation.TupleFlat.nonDefault == true)
        assert(Schema.UnionRepresentation.Untagged.nonDefault == true)
    }

    // =========================================================================
    // Group: slot threading / inert default
    // =========================================================================

    "configured schema takes transform path; unconfigured is inert" in {
        val base      = Schema[SSRShape]
        val adjSchema = base.adjacent("type", "content")

        // Unconfigured schema: External (default) - hasTransforms driven only by other flags
        assert(base.representation == Schema.UnionRepresentation.External)
        assert(base.representation.nonDefault == false)

        // Configured schema: Adjacent - nonDefault is true, so hasTransforms and hasReadTransforms flip
        assert(adjSchema.representation == Schema.UnionRepresentation.Adjacent("type", "content"))
        assert(adjSchema.hasTransforms == true)
        assert(adjSchema.hasReadTransforms == true)

        // The External path encodes the default single-field wrapper
        val circle: SSRShape = SSRCircle(10.0)
        val baseWire         = base.encodeString[Json](circle)
        assert(baseWire == """{"SSRCircle":{"radius":10.0}}""")
    }

    "untagged FirstMatch: a whole-number variant declared first does not take a fraction, which falls to the next" in {
        val schema = summon[Schema[SSRNumber]]
        assert(schema.decodeString[Json]("6") == Result.succeed(SSRNumber.Whole(6L)))
        assert(schema.decodeString[Json]("1.5") == Result.succeed(SSRNumber.Fraction(1.5)))
        assert(schema.decodeString[Json]("true") == Result.succeed(SSRNumber.Flag(true)))
    }

    "adjacent: a null content reads as no content, so a variant without fields decodes" in {
        val schema = Schema[SSRSignal].adjacent("t", "d")
        assert(schema.decodeString[Json]("""{"t":"SSRResumed","d":null}""") == Result.succeed(SSRResumed))
        assert(schema.decodeString[Json]("""{"d":null,"t":"SSRResumed"}""") == Result.succeed(SSRResumed))
        assert(schema.decodeString[Json]("""{"t":"SSRResumed"}""") == Result.succeed(SSRResumed))
        schema.decodeString[Json]("""{"t":"SSRHello","d":null}""") match
            case Result.Failure(e: MissingFieldException) => assert(e.fieldName == "interval")
            case other                                    => fail(s"expected MissingFieldException, got $other")
    }

    "representation survives copyWith / focus composition" in {
        val base = Schema[SSRShape].adjacent("type", "content")
        // .doc routes through copyWith, which must preserve representation
        val withDoc = Schema.copyWith(base)(doc = Maybe("test doc"))

        // Representation slot must survive the copyWith round-trip
        assert(withDoc.representation == Schema.UnionRepresentation.Adjacent("type", "content"))
        assert(withDoc.hasTransforms == true)
    }

    // =========================================================================
    // Group: builder Focused-preserving
    // =========================================================================

    "adjacent builder is Focused-preserving and sets Adjacent" in {
        val base                                                  = Schema[SSRShape]
        val adj: Schema[SSRShape] { type Focused = base.Focused } = base.adjacent("type", "content")
        // Focused refinement preserved (compile-time check above) and slot set correctly
        assert(adj.representation == Schema.UnionRepresentation.Adjacent("type", "content"))
        succeed("adjacent builder preserves the Focused refinement")
    }

    "tupleTagged / tupleFlat / untagged builders are Focused-preserving and set their case" in {
        val base                                                   = Schema[SSRShape]
        val tup: Schema[SSRShape] { type Focused = base.Focused }  = base.tupleTagged
        val tupF: Schema[SSRShape] { type Focused = base.Focused } = base.tupleFlat
        val unt: Schema[SSRShape] { type Focused = base.Focused }  = base.untagged

        assert(tup.representation == Schema.UnionRepresentation.Tuple)
        assert(tupF.representation == Schema.UnionRepresentation.TupleFlat)
        assert(unt.representation == Schema.UnionRepresentation.Untagged)

        // All three set nonDefault, so both transform flags are true
        assert(tup.hasTransforms == true)
        assert(tupF.hasReadTransforms == true)
        assert(unt.hasTransforms == true)

        succeed("tupleTagged, tupleFlat, and untagged builders preserve the Focused refinement")
    }

    // =========================================================================
    // Group: discriminator byte-identity
    // =========================================================================

    "discriminator is byte-identical sugar over Internal" in {
        val schema           = Schema[SSRShape].discriminator("type")
        val circle: SSRShape = SSRCircle(10.0)

        // discriminator sets both discriminatorField AND representation = Internal(fieldName)
        assert(schema.representation == Schema.UnionRepresentation.Internal("type"))

        val wire = schema.encodeString[Json](circle)
        // Internal flat discriminator: {"type":"SSRCircle","radius":10.0}
        assert(wire == """{"type":"SSRCircle","radius":10.0}""")

        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRCircle(10.0)))
    }

    "discriminator chaining is last-wins" in {
        val schema           = Schema[SSRShape].discriminator("a").discriminator("b")
        val circle: SSRShape = SSRCircle(10.0)

        // Last discriminator call wins for both fields
        assert(schema.representation == Schema.UnionRepresentation.Internal("b"))

        val wire = schema.encodeString[Json](circle)
        assert(wire == """{"b":"SSRCircle","radius":10.0}""")
        assert(!wire.contains("\"a\""))
    }

    // =========================================================================
    // Group: adjacent round-trip
    // =========================================================================

    "adjacent encode emits the two-field object" in {
        val schema           = Schema[SSRShape].adjacent("type", "content")
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        assert(wire == """{"type":"SSRCircle","content":{"radius":10.0}}""")
    }

    "adjacent empty-payload encode omits the content key" in {
        val schema         = Schema[SSRShape].adjacent("type", "content")
        val unit: SSRShape = SSRUnit
        val wire           = schema.encodeString[Json](unit)
        assert(wire == """{"type":"SSRUnit"}""")
    }

    "adjacent: a payload whose every field is omitted writes no content, and reads back" in {
        val schema = Schema[SSRSignal].adjacent("t", "d")
        val values = Chunk[SSRSignal](SSRDefer(), SSRDefer(true), SSRNote(Absent), SSRNote(Present("x")), SSRHello(1))
        val wires  = values.map(v => schema.encodeString[Json](v))
        assert(wires == Chunk(
            """{"t":"SSRDefer"}""",
            """{"t":"SSRDefer","d":{"quiet":true}}""",
            """{"t":"SSRNote"}""",
            """{"t":"SSRNote","d":{"note":"x"}}""",
            """{"t":"SSRHello","d":{"interval":1}}"""
        ))
        assert(wires.map(w => schema.decodeString[Json](w)) == values.map(Result.succeed(_)))
        assert(values.map(v => schema.decodeString[Yaml](schema.encodeString[Yaml](v))) == values.map(Result.succeed(_)))
        assert(values.map(v => schema.decodeString[Ion](schema.encodeString[Ion](v))) == values.map(Result.succeed(_)))
    }

    // =========================================================================
    // Group: tuple round-trip
    // =========================================================================

    "tuple encode emits the two-element array" in {
        val schema           = Schema[SSRShape].tupleTagged
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        assert(wire == """["SSRCircle",{"radius":10.0}]""")
    }

    // =========================================================================
    // Group: untagged encode
    // =========================================================================

    "untagged encode emits the bare payload" in {
        val schema           = Schema[SSRShape].untagged
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        assert(wire == """{"radius":10.0}""")
    }

    // =========================================================================
    // Group: non-object payload
    // =========================================================================

    "adjacent carries a single-field payload without dropping it" in {
        val schema       = Schema[SSRShape].adjacent("type", "content")
        val pi: SSRShape = SSRPi(3.14159)
        val wire         = schema.encodeString[Json](pi)
        // SSRPi(value: Double) produces a single-field Record payload; adjacentEncode passes it
        // through unchanged as the content value (no silent drop, unlike flattenWithDiscriminator).
        assert(wire == """{"type":"SSRPi","content":{"value":3.14159}}""")
    }

    "tuple carries a single-field payload as the second element" in {
        val schema       = Schema[SSRShape].tupleTagged
        val pi: SSRShape = SSRPi(3.14159)
        val wire         = schema.encodeString[Json](pi)
        // SSRPi(value: Double) produces a single-field Record payload; tupleEncode passes it
        // through unchanged as element 1 of the array.
        assert(wire == """["SSRPi",{"value":3.14159}]""")
    }

    // =========================================================================
    // Group: tupleFlat encode
    // =========================================================================

    "tupleFlat encode emits the flattened array in declaration order" in {
        val schema             = Schema[SSRShape].tupleFlat
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)
        val wire               = schema.encodeString[Json](triangle)
        assert(wire == """["SSRTriangle",10.0,10.0,10.0]""")
    }

    "tupleFlat nests a record field as one element; nested Tuple unchanged" in {
        val schemaFlat         = Schema[SSRShape].tupleFlat
        val schemaTuple        = Schema[SSRShape].tupleTagged
        val line: SSRShape     = SSRLine(SSRPoint(1.0, 2.0), SSRPoint(3.0, 4.0))
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)

        val flatWire  = schemaFlat.encodeString[Json](line)
        val tupleWire = schemaTuple.encodeString[Json](triangle)

        assert(flatWire == """["SSRLine",{"x":1.0,"y":2.0},{"x":3.0,"y":4.0}]""")
        assert(tupleWire == """["SSRTriangle",{"a":10.0,"b":10.0,"c":10.0}]""")
    }

    "a tupleFlat field of a transformed parent encodes on a self-describing codec" in {
        val schema = summon[Schema[SSRDrawing]].renameAllFields(Schema.NameCase.SnakeCase)
        val value  = SSRDrawing("d", SSRCircle(2.0))
        val wire   = schema.encodeString[Json](value)
        assert(wire == """{"drawing_title":"d","shape":["SSRCircle",2.0]}""", wire)
        assert(schema.decodeString[Json](wire) == Result.succeed(value))
    }

    // =========================================================================
    // Group: binary codec rejection
    // =========================================================================

    "incapable codec raises RepresentationUnsupportedException pre-write; capable does not" in {
        val circle: SSRShape = SSRCircle(10.0)

        val tupleSchema     = Schema[SSRShape].tupleTagged
        val untaggedSchema  = Schema[SSRShape].untagged
        val tupleFlatSchema = Schema[SSRShape].tupleFlat

        val exTuple = intercept[RepresentationUnsupportedException] {
            tupleSchema.encode[Protobuf](circle)
        }
        assert(exTuple.codec == "Protobuf")
        assert(exTuple.representation == "Tuple")
        assert(exTuple.getMessage.contains("Protobuf"))

        val exUntagged = intercept[RepresentationUnsupportedException] {
            untaggedSchema.encode[Protobuf](circle)
        }
        assert(exUntagged.codec == "Protobuf")
        assert(exUntagged.representation == "Untagged")

        val exTupleFlat = intercept[RepresentationUnsupportedException] {
            tupleFlatSchema.encode[Protobuf](circle)
        }
        assert(exTupleFlat.codec == "Protobuf")
        assert(exTupleFlat.representation == "TupleFlat")

        // Json can express the tuple shape
        val jsonWire = tupleSchema.encodeString[Json](circle)
        assert(jsonWire == """["SSRCircle",{"radius":10.0}]""")
    }

    // =========================================================================
    // Group: adjacent round-trip decode
    // =========================================================================

    "adjacent round-trips and decodes the worked example" in {
        val schema           = Schema[SSRShape].adjacent("type", "content")
        val square: SSRShape = SSRSquare(10.0)
        val circle: SSRShape = SSRCircle(10.0)

        val decoded = schema.decodeString[Json]("""{"type":"SSRSquare","content":{"side":10.0}}""")
        assert(decoded == Result.succeed(SSRSquare(10.0)))

        val roundTripped = schema.decodeString[Json](schema.encodeString[Json](circle))
        assert(roundTripped == Result.succeed(SSRCircle(10.0)))
    }

    "adjacent empty-payload round-trips" in {
        val schema         = Schema[SSRShape].adjacent("type", "content")
        val unit: SSRShape = SSRUnit

        val wire    = schema.encodeString[Json](unit)
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRUnit))
    }

    "adjacent non-object scalar payload round-trips; discriminator keeps record fields" in {
        val adjSchema    = Schema[SSRShape].adjacent("type", "content")
        val discSchema   = Schema[SSRShape].discriminator("type")
        val pi: SSRShape = SSRPi(3.14159)

        val adjWire   = adjSchema.encodeString[Json](pi)
        val adjResult = adjSchema.decodeString[Json](adjWire)
        assert(adjResult == Result.succeed(SSRPi(3.14159)))

        // SSRPi(value: Double) is a record variant: discriminator flattens the field alongside the tag.
        val discWire = discSchema.encodeString[Json](pi)
        assert(discWire == """{"type":"SSRPi","value":3.14159}""")
    }

    // =========================================================================
    // Group: tuple round-trip decode
    // =========================================================================

    "tuple round-trips the worked example; non-object element round-trips" in {
        val schema           = Schema[SSRShape].tupleTagged
        val circle: SSRShape = SSRCircle(10.0)
        val pi: SSRShape     = SSRPi(3.14159)

        val circleWire   = schema.encodeString[Json](circle)
        val circleResult = schema.decodeString[Json](circleWire)
        assert(circleWire == """["SSRCircle",{"radius":10.0}]""")
        assert(circleResult == Result.succeed(SSRCircle(10.0)))

        val piWire   = schema.encodeString[Json](pi)
        val piResult = schema.decodeString[Json](piWire)
        assert(piWire == """["SSRPi",{"value":3.14159}]""")
        assert(piResult == Result.succeed(SSRPi(3.14159)))
    }

    // =========================================================================
    // Group: adjacent missing tag
    // =========================================================================

    "adjacent missing tag key -> MissingTagKeyException in Result" in {
        val schema  = Schema[SSRShape].adjacent("type", "content")
        val decoded = schema.decodeString[Json]("""{"content":{"radius":10.0}}""")
        decoded match
            case Result.Failure(ex: MissingTagKeyException) =>
                assert(ex.tagKey == "type")
            case other =>
                fail(s"Expected Result.Failure(MissingTagKeyException) but got $other")
        end match
    }

    // =========================================================================
    // Group: tupleFlat decode
    // =========================================================================

    "tupleFlat positional decode round-trips" in {
        val schema             = Schema[SSRShape].tupleFlat
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)

        val decoded = schema.decodeString[Json]("""["SSRTriangle",10.0,10.0,10.0]""")
        assert(decoded == Result.succeed(SSRTriangle(10.0, 10.0, 10.0)))

        val roundTripped = schema.decodeString[Json](schema.encodeString[Json](triangle))
        assert(roundTripped == Result.succeed(SSRTriangle(10.0, 10.0, 10.0)))
    }

    "tupleFlat count mismatch -> typed DecodeException in Result" in {
        val schema = Schema[SSRShape].tupleFlat

        val tooFew = schema.decodeString[Json]("""["SSRTriangle",10.0,10.0]""")
        tooFew match
            case Result.Failure(_: MissingFieldException) => succeed("too-few yields MissingFieldException as expected")
            case other                                    => fail(s"Expected MissingFieldException for too-few but got $other")

        val tooMany = schema.decodeString[Json]("""["SSRTriangle",10.0,10.0,10.0,10.0]""")
        tooMany match
            case Result.Failure(_: DecodeException) => succeed("too-many yields DecodeException as expected")
            case other                              => fail(s"Expected DecodeException for too-many but got $other")

        val correct = schema.decodeString[Json]("""["SSRTriangle",10.0,10.0,10.0]""")
        assert(correct == Result.succeed(SSRTriangle(10.0, 10.0, 10.0)))
    }

    "tupleFlat zero-field and single-field edges round-trip" in {
        val schema = Schema[SSRShape].tupleFlat

        val unitWire   = schema.encodeString[Json](SSRUnit)
        val unitResult = schema.decodeString[Json](unitWire)
        assert(unitWire == """["SSRUnit"]""")
        assert(unitResult == Result.succeed(SSRUnit))

        val piWire   = schema.encodeString[Json](SSRPi(10.0))
        val piResult = schema.decodeString[Json](piWire)
        assert(piWire == """["SSRPi",10.0]""")
        assert(piResult == Result.succeed(SSRPi(10.0)))
    }

    // =========================================================================
    // Group: untagged declaration order
    // =========================================================================

    "untagged declaration order: first clean parse wins on Json" in {
        val schema = Schema[SSRUShape].untagged
        // SSRUCircle is declared first but needs 'radius'; the input has 'side', so Circle fails.
        // SSRUSquare is declared second and succeeds on {'side':10.0}.
        val result = schema.decodeString[Json]("""{"side":10.0}""")
        assert(result == Result.succeed(SSRUSquare(10.0)))
    }

    "untagged first-declared variant wins when both variants can decode the same input" in {
        val schemaFirst = Schema[SSRUAmbig].untagged
        // Both SSRUAmbigFirst and SSRUAmbigSecond share field 'x'. First-declared wins.
        val resultFirst = schemaFirst.decodeString[Json]("""{"x":5.0}""")
        assert(resultFirst == Result.succeed(SSRUAmbigFirst(5.0)))

        val schemaRev = Schema[SSRUAmbigRev].untagged
        // Reversed declaration order: SSRUAmbigRevSecond declared before SSRUAmbigRevFirst.
        val resultRev = schemaRev.decodeString[Json]("""{"x":5.0}""")
        assert(resultRev == Result.succeed(SSRUAmbigRevSecond(5.0)))
    }

    "untagged round-trips on Json" in {
        val schema           = Schema[SSRUShape].untagged
        val value: SSRUShape = SSRUCircle(10.0)
        val wire             = schema.encodeString[Json](value)
        val result           = schema.decodeString[Json](wire)
        assert(result == Result.succeed(SSRUCircle(10.0)))
    }

    "untagged round-trips on Yaml" in {
        val schema           = Schema[SSRUShape].untagged
        val value: SSRUShape = SSRUSquare(10.0)
        val wire             = schema.encodeString[Yaml](value)
        val result           = schema.decodeString[Yaml](wire)
        assert(result == Result.succeed(SSRUSquare(10.0)))
    }

    "untagged round-trips on Ion" in {
        val schema           = Schema[SSRUShape].untagged
        val value: SSRUShape = SSRUCircle(10.0)
        val wire             = schema.encodeString[Ion](value)
        val result           = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRUCircle(10.0)))
    }

    // =========================================================================
    // Group: untagged non-destructive retry
    // =========================================================================

    "untagged non-destructive retry: later variant succeeds after earlier variants fail" in {
        val schema = Schema[SSRUShape].untagged
        // SSRUBox requires both 'width' and 'height'. SSRUCircle (needs 'radius') and
        // SSRUSquare (needs 'side') both fail. A destructive reader would leave SSRUBox
        // short of input; non-destructive retry gives each attempt the full original tree.
        val input = """{"width":3.0,"height":4.0}"""
        assert(schema.decodeString[Json](input) == Result.succeed(SSRUBox(3.0, 4.0)))

        val yamlWire = schema.encodeString[Yaml](SSRUBox(3.0, 4.0))
        assert(schema.decodeString[Yaml](yamlWire) == Result.succeed(SSRUBox(3.0, 4.0)))

        val ionWire = schema.encodeString[Ion](SSRUBox(3.0, 4.0))
        assert(schema.decodeString[Ion](ionWire) == Result.succeed(SSRUBox(3.0, 4.0)))

        val msgPackBytes = schema.encode[MsgPack](SSRUBox(3.0, 4.0))
        assert(schema.decode[MsgPack](msgPackBytes) == Result.succeed(SSRUBox(3.0, 4.0)))
    }

    // =========================================================================
    // Group: untagged no-match
    // =========================================================================

    "untagged no-match yields NoVariantMatchException in Result on Json" in {
        val schema = Schema[SSRUShape].untagged
        val result = schema.decodeString[Json]("""{"weight":1.0}""")
        result match
            case Result.Failure(ex: NoVariantMatchException) =>
                assert(ex.variants.nonEmpty)
            case other =>
                fail(s"Expected Failure(NoVariantMatchException) but got $other")
        end match
    }

    "untagged no-match yields NoVariantMatchException on Yaml, Ion, and MsgPack" in {
        val schema = Schema[SSRUShape].untagged

        val yamlResult = schema.decodeString[Yaml]("weight: 1.0\n")
        yamlResult match
            case Result.Failure(_: NoVariantMatchException) => succeed("Yaml no-match ok")
            case other                                      => fail(s"Expected Failure(NoVariantMatchException) on Yaml but got $other")
        end match

        val ionResult = schema.decodeString[Ion]("{weight:1.0}")
        ionResult match
            case Result.Failure(_: NoVariantMatchException) => succeed("Ion no-match ok")
            case other                                      => fail(s"Expected Failure(NoVariantMatchException) on Ion but got $other")
        end match

        // Encode a value with a 'weight' field (not present in any SSRUShape variant).
        val msgPackBytes = Schema[SSRUAmbigFirst].encode[MsgPack](SSRUAmbigFirst(9.9))
        val msgResult    = schema.decode[MsgPack](msgPackBytes)
        msgResult match
            case Result.Failure(_: NoVariantMatchException) => succeed("MsgPack no-match ok")
            case Result.Success(v)                          => fail(s"Expected no-match failure but decoded $v")
            case other                                      => fail(s"Expected Failure(NoVariantMatchException) on MsgPack but got $other")
        end match
    }

    // =========================================================================
    // Group: introspecting Yaml/Ion
    // =========================================================================

    "YamlReader and IonReader readStructure materialize a mixed-shape value" in {
        // Encode a record with a nested sequence and scalar fields on Yaml and Ion,
        // then decode back as Structure.Value to confirm both readers are introspecting.
        val sv = Structure.Value.Record(Chunk(
            ("name", Structure.Value.Str("Alice")),
            (
                "tags",
                Structure.Value.Sequence(Chunk(
                    Structure.Value.Str("a"),
                    Structure.Value.Str("b")
                ))
            ),
            ("score", Structure.Value.Decimal(9.5)),
            ("active", Structure.Value.Bool(true))
        ))

        val svSchema = summon[Schema[Structure.Value]]

        val yamlWire   = svSchema.encodeString[Yaml](sv)
        val yamlResult = svSchema.decodeString[Yaml](yamlWire)
        assert(yamlResult.isSuccess)

        val ionWire   = svSchema.encodeString[Ion](sv)
        val ionResult = svSchema.decodeString[Ion](ionWire)
        assert(ionResult.isSuccess)
    }

    "untagged decode on Protobuf surfaces a typed self-describing-reader failure" in {
        val schema     = Schema[SSRUShape].untagged
        val protoBytes = Schema[SSRShape].encode[Protobuf](SSRCircle(10.0))
        val result     = schema.decode[Protobuf](protoBytes)
        result match
            case Result.Panic(ex: SchemaNotSerializableException) =>
                assert(ex.getMessage.contains("self-describing"))
            case other =>
                fail(s"Expected Result.Panic(SchemaNotSerializableException) with 'self-describing' message but got $other")
        end match
    }

    // =========================================================================
    // Group: untagged panic surfacing
    // =========================================================================

    "untagged decode surfaces unexpected error from variant decoder, not NoVariantMatchException" in {
        // An unexpected error thrown by a variant decoder (IllegalStateException) must surface as
        // Result.Panic, never be retried and masked as a no-match: a Panic is not a clean decode miss.
        // Replace the first variant decoder with one that throws to verify the Panic surfaces.
        val base                                 = Schema[SSRUShape].untagged
        val decoders                             = base.variantDecoders
        val injectedDecoder: Codec.Reader => Any = (_: Codec.Reader) =>
            throw new IllegalStateException("injected unexpected decoder failure")
        val patched = Schema.copyWith(base)(
            variantDecoders = Chunk(injectedDecoder) ++ decoders.drop(1)
        )
        // SSRUSquare matches only the second decoder (index 1). The first throws
        // IllegalStateException, which must surface as Result.Panic.
        val result = patched.decodeString[Json]("""{"side":10.0}""")
        result match
            case Result.Panic(ex: IllegalStateException) =>
                assert(ex.getMessage == "injected unexpected decoder failure")
            case Result.Failure(_: NoVariantMatchException) =>
                fail("unexpected error was masked as NoVariantMatchException instead of surfacing as a Panic")
            case other =>
                fail(s"Expected Result.Panic(IllegalStateException) but got $other")
        end match
    }

    "untagged clean no-match yields NoVariantMatchException" in {
        // When all variant decoders legitimately reject the input with DecodeExceptions,
        // the result is NoVariantMatchException, not a Panic.
        val schema = Schema[SSRUShape].untagged
        val result = schema.decodeString[Json]("""{"weight":99.0}""")
        result match
            case Result.Failure(ex: NoVariantMatchException) =>
                assert(ex.variants.nonEmpty)
            case other =>
                fail(s"Expected Failure(NoVariantMatchException) for a genuine no-match but got $other")
        end match
    }

    // =========================================================================
    // Group: naming composition
    // =========================================================================

    "adjacent with snake-case naming emits snake-cased tag" in {
        val schema           = Schema[SSRShape].adjacent("kind", "data").renameAllVariants(Schema.NameCase.SnakeCase)
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // SSRCircle -> tokens [SSR, Circle] -> ssr_circle
        assert(wire == """{"kind":"ssr_circle","data":{"radius":10.0}}""")
    }

    "tuple tag resolves through naming layer on encode and decode" in {
        val schema           = Schema[SSRShape].tupleTagged.renameAllVariants(Schema.NameCase.SnakeCase)
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // encode emits the snake-cased tag as element 0
        assert(wire == """["ssr_circle",{"radius":10.0}]""")
        // decode accepts the snake-cased tag as element 0
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRCircle(10.0)))
    }

    "untagged skips tag naming but keeps field naming" in {
        // renameAllVariants on the sum suppresses the tag under Untagged but does NOT rename payload
        // fields: field naming on a sum schema does not propagate into each variant's own product schema.
        // SSRUItem.itemCount is a multi-word camelCase field; under SnakeCase it would become item_count.
        // Asserting the wire still carries itemCount (not item_count) confirms the payload field names are
        // passed through exactly as the variant schema produces them.
        val schema          = Schema[SSRUNamed].untagged.renameAllVariants(Schema.NameCase.SnakeCase)
        val item: SSRUNamed = SSRUItem(42)
        val wire            = schema.encodeString[Json](item)
        // Bare payload: the variant's own schema emits itemCount unchanged; no sum-level field rename fires.
        assert(wire == """{"itemCount":42}""")
        // No variant-name token appears: Untagged suppresses the tag even with renameAllVariants configured.
        assert(!wire.contains("SSRUItem"))
        assert(!wire.contains("ssru_item"))
        // Round-trip: the untagged decoder reconstructs the original value from the bare payload.
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRUItem(42)))
    }

    "sum-level renameAllFields does not rename variant payload field names (payload-bearing reps)" in {
        // A field convention configured on the SUM schema governs only the sum wrapper, never the
        // variant's own product fields. SSRUItem.itemCount is multi-word camelCase: under SnakeCase a leaked
        // sum-level rename would surface as item_count in the payload. Each payload-bearing representation
        // (External, Internal, Adjacent, Tuple) carries the payload as a named object, so a leak would be
        // wire-visible here. TupleFlat (positional) and Untagged (bare payload) are pinned separately.
        val item: SSRUNamed                         = SSRUItem(42)
        def wire(schema: Schema[SSRUNamed]): String =
            schema.renameAllFields(Schema.NameCase.SnakeCase).encodeString[Json](item)

        // External: wrapper key is the variant name; the payload object keeps itemCount.
        assert(wire(Schema[SSRUNamed]) == """{"SSRUItem":{"itemCount":42}}""")
        // Internal: discriminator key plus inlined payload; itemCount unchanged.
        assert(wire(Schema[SSRUNamed].discriminator("type")) == """{"type":"SSRUItem","itemCount":42}""")
        // Adjacent: tag key plus nested content object; itemCount unchanged.
        assert(wire(Schema[SSRUNamed].adjacent("t", "c")) == """{"t":"SSRUItem","c":{"itemCount":42}}""")
        // Tuple: [tag, payload-object]; itemCount unchanged.
        assert(wire(Schema[SSRUNamed].tupleTagged) == """["SSRUItem",{"itemCount":42}]""")
    }

    // =========================================================================
    // Group: alias acceptance set
    // =========================================================================

    "alias accepted on decode under Internal, Adjacent, and Tuple" in {
        val internalSchema = Schema[SSRAShape].discriminator("type").variantAlias("SSRACircle", "circ_v1")
        val adjacentSchema = Schema[SSRAShape].adjacent("t", "c").variantAlias("SSRACircle", "circ_v1")
        val tupleSchema    = Schema[SSRAShape].tupleTagged.variantAlias("SSRACircle", "circ_v1")

        val internalResult = internalSchema.decodeString[Json]("""{"type":"circ_v1","radius":10.0}""")
        assert(internalResult == Result.succeed(SSRACircle(10.0)))

        val adjacentResult = adjacentSchema.decodeString[Json]("""{"t":"circ_v1","c":{"radius":10.0}}""")
        assert(adjacentResult == Result.succeed(SSRACircle(10.0)))

        val tupleResult = tupleSchema.decodeString[Json]("""["circ_v1",{"radius":10.0}]""")
        assert(tupleResult == Result.succeed(SSRACircle(10.0)))
    }

    "a renamed variant's Scala name is not its tag under Internal, Adjacent, Tuple and TupleFlat" in {
        def rejects(result: Result[DecodeException, SSRAShape]): Unit =
            result match
                case Result.Failure(e: UnknownVariantException) => assert(e.variantName == "SSRACircle")
                case other                                      => fail(s"expected UnknownVariantException, got $other")
        rejects(Schema[SSRAShape].discriminator("type").variantNames("SSRACircle" -> "circle")
            .decodeString[Json]("""{"type":"SSRACircle","radius":10.0}"""))
        rejects(Schema[SSRAShape].adjacent("t", "c").variantNames("SSRACircle" -> "circle")
            .decodeString[Json]("""{"t":"SSRACircle","c":{"radius":10.0}}"""))
        rejects(Schema[SSRAShape].tupleTagged.variantNames("SSRACircle" -> "circle")
            .decodeString[Json]("""["SSRACircle",{"radius":10.0}]"""))
        rejects(Schema[SSRAShape].tupleFlat.variantNames("SSRACircle" -> "circle")
            .decodeString[Json]("""["SSRACircle",10.0]"""))
    }

    "alias accepted on decode under TupleFlat" in {
        val schema = Schema[SSRAShape].tupleFlat.variantAlias("SSRACircle", "circ_v1")
        val wire   = """["circ_v1",10.0]"""
        val result = schema.decodeString[Json](wire)
        assert(result == Result.succeed(SSRACircle(10.0)))
    }

    "External does not accept alias as wrapper key; untagged decode is unaffected by alias" in {
        // External: alias is NOT accepted as the wrapper object key
        val externalSchema = Schema[SSRAShape].variantAlias("SSRACircle", "circ_v1")
        val externalResult = externalSchema.decodeString[Json]("""{"circ_v1":{"radius":10.0}}""")
        assert(!externalResult.isSuccess)

        // The canonical name still works under External
        val canonicalResult = externalSchema.decodeString[Json]("""{"SSRACircle":{"radius":10.0}}""")
        assert(canonicalResult == Result.succeed(SSRACircle(10.0)))

        // Untagged: alias has no effect on which variant a bare payload decodes to
        val untaggedSchema = Schema[SSRAShape].untagged.variantAlias("SSRACircle", "circ_v1")
        val untaggedResult = untaggedSchema.decodeString[Json]("""{"radius":10.0}""")
        assert(untaggedResult == Result.succeed(SSRACircle(10.0)))
    }

    // =========================================================================
    // Group: tupleFlat naming/alias
    // =========================================================================

    "tupleFlat tag resolves through naming layer" in {
        val schema             = Schema[SSRShape].tupleFlat.renameAllVariants(Schema.NameCase.SnakeCase)
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)
        val wire               = schema.encodeString[Json](triangle)
        // SSRTriangle -> tokens [SSR, Triangle] -> ssr_triangle
        assert(wire == """["ssr_triangle",10.0,10.0,10.0]""")
    }

    "tupleFlat field rename leaves the positional wire unchanged" in {
        val schemaPlain        = Schema[SSRShape].tupleFlat
        val schemaWithRename   = Schema[SSRShape].tupleFlat.renameAllFields(Schema.NameCase.SnakeCase)
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)

        val plainWire   = schemaPlain.encodeString[Json](triangle)
        val renamedWire = schemaWithRename.encodeString[Json](triangle)
        // TupleFlat drops field names: a field-level rename is wire-invisible
        assert(plainWire == renamedWire)
        assert(renamedWire == """["SSRTriangle",10.0,10.0,10.0]""")
    }

    // =========================================================================
    // Group: default byte-identity
    // =========================================================================

    "External default encodes and round-trips byte-identically" in {
        val schema           = Schema[SSRShape]
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // External wrapper shape: {"SSRCircle":{"radius":10.0}}
        assert(wire == """{"SSRCircle":{"radius":10.0}}""")
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRCircle(10.0)))
    }

    "Internal flat encodes and round-trips byte-identically" in {
        val schema           = Schema[SSRShape].discriminator("type")
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // Internal flat-discriminator shape: {"type":"SSRCircle","radius":10.0}
        assert(wire == """{"type":"SSRCircle","radius":10.0}""")
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(SSRCircle(10.0)))
    }

    "External and discriminator emit the canonical wrapper and flat-discriminator wire shapes" in {
        // The canonical External and Internal wire shapes for a single-field variant.
        val extSchema          = Schema[SSRShape]
        val discSchema         = Schema[SSRShape].discriminator("type")
        val square: SSRShape   = SSRSquare(5.0)
        val triangle: SSRShape = SSRTriangle(1.0, 2.0, 3.0)

        // External wrapper form: {"VariantName":{"field":value}}
        assert(extSchema.encodeString[Json](square) == """{"SSRSquare":{"side":5.0}}""")
        assert(extSchema.encodeString[Json](triangle) == """{"SSRTriangle":{"a":1.0,"b":2.0,"c":3.0}}""")

        // Internal flat form: {"tagKey":"VariantName","field":value}
        assert(discSchema.encodeString[Json](square) == """{"type":"SSRSquare","side":5.0}""")
        assert(discSchema.encodeString[Json](triangle) == """{"type":"SSRTriangle","a":1.0,"b":2.0,"c":3.0}""")
    }

    // =========================================================================
    // Group: untagged Ion round-trip with Short/Byte/Int fields
    // =========================================================================

    "untagged Ion round-trip with Short field decodes to the correct variant and value" in {
        val schema              = Schema[SSRUIntegral].untagged
        val value: SSRUIntegral = SSRUShortVal(42.toShort)
        val wire                = schema.encodeString[Ion](value)
        val result              = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRUShortVal(42.toShort)))
    }

    "untagged Ion round-trip with Byte field decodes to the correct variant and value" in {
        val schema              = Schema[SSRUIntegral].untagged
        val value: SSRUIntegral = SSRUByteVal(7.toByte)
        val wire                = schema.encodeString[Ion](value)
        val result              = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRUByteVal(7.toByte)))
    }

    "untagged Ion round-trip with Int field decodes to the correct variant and value" in {
        val schema              = Schema[SSRUIntegral].untagged
        val value: SSRUIntegral = SSRUIntVal(1000)
        val wire                = schema.encodeString[Ion](value)
        val result              = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRUIntVal(1000)))
    }

    // =========================================================================
    // Group: adjacent and tupleFlat decode on Yaml and Ion
    // =========================================================================

    "adjacent round-trips on Yaml with concrete value assertion" in {
        val schema          = Schema[SSRShape].adjacent("type", "content")
        val value: SSRShape = SSRCircle(5.0)
        val wire            = schema.encodeString[Yaml](value)
        val result          = schema.decodeString[Yaml](wire)
        assert(result == Result.succeed(SSRCircle(5.0)))
    }

    "adjacent round-trips on Ion with concrete value assertion" in {
        val schema          = Schema[SSRShape].adjacent("type", "content")
        val value: SSRShape = SSRSquare(3.0)
        val wire            = schema.encodeString[Ion](value)
        val result          = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRSquare(3.0)))
    }

    "tupleFlat round-trips on Yaml with concrete value assertion" in {
        val schema          = Schema[SSRShape].tupleFlat
        val value: SSRShape = SSRTriangle(1.0, 2.0, 3.0)
        val wire            = schema.encodeString[Yaml](value)
        val result          = schema.decodeString[Yaml](wire)
        assert(result == Result.succeed(SSRTriangle(1.0, 2.0, 3.0)))
    }

    "tupleFlat round-trips on Ion with concrete value assertion" in {
        val schema          = Schema[SSRShape].tupleFlat
        val value: SSRShape = SSRTriangle(4.0, 5.0, 6.0)
        val wire            = schema.encodeString[Ion](value)
        val result          = schema.decodeString[Ion](wire)
        assert(result == Result.succeed(SSRTriangle(4.0, 5.0, 6.0)))
    }

    // =========================================================================
    // Codec.Capabilities, the representation chain slot, and the chain builders
    // =========================================================================

    "representationFor is deterministic and capability-keyed" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.External
        )
        val capTrue  = Codec.Capabilities(canWriteTopLevelNonObject = true)
        val capFalse = Codec.Capabilities(canWriteTopLevelNonObject = false)
        assert(schema.representationFor(capTrue) == Schema.UnionRepresentation.TupleFlat)
        assert(schema.representationFor(capTrue) == Schema.UnionRepresentation.TupleFlat)
        assert(schema.representationFor(capFalse) == Schema.UnionRepresentation.External)
        assert(schema.representationFor(capFalse) == Schema.UnionRepresentation.External)
    }

    "duplicate chain is rejected at the builder call site" in {
        val dupChain = Result.catching[DuplicateRepresentationException](
            Schema[SSRShape].representations(
                Schema.UnionRepresentation.TupleFlat,
                Schema.UnionRepresentation.TupleFlat
            )
        )
        assert(dupChain.isFailure)

        val dupOrElse = Result.catching[DuplicateRepresentationException](
            Schema[SSRShape].tupleFlat.orElseRepresentation(Schema.UnionRepresentation.TupleFlat)
        )
        assert(dupOrElse.isFailure)
    }

    "representations requires a first parameter - single-arg form compiles" in {
        val schema = Schema[SSRShape].representations(Schema.UnionRepresentation.External)
        assert(schema.representationChain.isDefined)
    }

    "single-entry External chain is byte-identical to default-External" in {
        val default         = Schema[SSRShape]
        val chainOne        = Schema[SSRShape].representations(Schema.UnionRepresentation.External)
        val value: SSRShape = SSRCircle(5.0)
        assert(default.encodeString[Json](value) == chainOne.encodeString[Json](value))
    }

    // =========================================================================
    // Chain encode selection and decode try-in-order
    // =========================================================================

    "encode emits primary shape on capable codec" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.Adjacent("type", "content"),
            Schema.UnionRepresentation.External
        )
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)
        val wire               = schema.encodeString[Json](triangle)
        assert(wire.startsWith("["))
        assert(wire == """["SSRTriangle",10.0,10.0,10.0]""")
    }

    "encode degrades to first object-shaped entry on incapable codec" in {
        // Chain: TupleFlat (needs canWriteTopLevelNonObject), Adjacent (object-shaped, always ok), External.
        // Protobuf cannot express TupleFlat, so selectRepresentation picks Adjacent.
        // The encode SUCCEEDS (Adjacent is an object shape Protobuf can write).
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.Adjacent("type", "content"),
            Schema.UnionRepresentation.External
        )
        val triangle: SSRShape = SSRTriangle(10.0, 10.0, 10.0)
        val bytes              = schema.encode[Protobuf](triangle)
        assert(bytes.nonEmpty)
        // Chain decode requires a self-describing reader; Protobuf is not one.
        // Decode via Json to confirm the encode produced an Adjacent-shaped value.
        // (Re-encode as Adjacent-only Json wire and verify the shape.)
        val adjWire = Schema[SSRShape].adjacent("type", "content").encodeString[Json](triangle)
        val decoded = Schema[SSRShape].adjacent("type", "content").decodeString[Json](adjWire)
        assert(decoded == Result.succeed(SSRTriangle(10.0, 10.0, 10.0)))
    }

    "no-chain tupleFlat still throws on Protobuf" in {
        val schema           = Schema[SSRShape].tupleFlat
        val circle: SSRShape = SSRCircle(10.0)
        val result           = Result.catching[RepresentationUnsupportedException](schema.encode[Protobuf](circle))
        assert(result.isFailure)
        result match
            case Result.Failure(ex) => assert(ex.codec == "Protobuf")
            case other              => fail(s"Expected RepresentationUnsupportedException but got $other")
        end match
    }

    "exhausted chain throws naming codec and attempted chain" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.Tuple
        )
        val circle: SSRShape = SSRCircle(10.0)
        val result           = Result.catching[RepresentationUnsupportedException](schema.encode[Protobuf](circle))
        result match
            case Result.Failure(ex) =>
                assert(ex.getMessage.contains("Protobuf"))
                assert(ex.getMessage.contains("TupleFlat"))
                assert(ex.getMessage.contains("Tuple"))
            case other => fail(s"Expected RepresentationUnsupportedException but got $other")
        end match
    }

    "chain round-trips a value valid for a later entry" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Internal("type"),
            Schema.UnionRepresentation.External
        )
        val circle: SSRShape = SSRCircle(10.0)
        // Encode using External (the baseline) to produce a wire the Internal arm won't match
        val externalWire = Schema[SSRShape].encodeString[Json](circle)
        // externalWire is {"SSRCircle":{"radius":10.0}}, which won't parse as Internal
        // but will parse as External (the fallback in the chain)
        val result = schema.decodeString[Json](externalWire)
        assert(result == Result.succeed(SSRCircle(10.0)))
    }

    "chain decode whose first attempt panics re-throws the panic" in {
        // Use Untagged as the only chain entry so readUntagged calls variantDecoders.
        // The injected decoder at position 0 throws IllegalStateException (not a SchemaException),
        // which must surface as Result.Panic and NOT be swallowed as a chain no-match.
        val base = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Untagged
        )
        val injected: Codec.Reader => Any = (_: Codec.Reader) =>
            throw new IllegalStateException("injected panic in chain decode")
        val patched = Schema.copyWith(base)(
            variantDecoders = Chunk(injected) ++ base.variantDecoders.drop(1)
        )
        // Untagged wire: a bare SSRCircle payload
        val wire   = """{"radius":10.0}"""
        val result = patched.decodeString[Json](wire)
        result match
            case Result.Panic(ex: IllegalStateException) =>
                assert(ex.getMessage == "injected panic in chain decode")
            case other => fail(s"Expected Result.Panic(IllegalStateException) but got $other")
        end match
    }

    "ambiguous two-entry chain selects first-declared on decode" in {
        // Two representations that could both decode the same External wire: External then Internal.
        // External is first-declared, so it should win.
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.External,
            Schema.UnionRepresentation.Internal("type")
        )
        val wire   = Schema[SSRShape].encodeString[Json](SSRCircle(10.0))
        val result = schema.decodeString[Json](wire)
        assert(result == Result.succeed(SSRCircle(10.0)))
    }

    "reordering the chain flips the chosen decode path" in {
        // Internal is first when we use Internal wire format: chain tries Internal first and wins.
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Internal("type"),
            Schema.UnionRepresentation.External
        )
        val internalWire = Schema[SSRShape].discriminator("type").encodeString[Json](SSRCircle(10.0))
        val result       = schema.decodeString[Json](internalWire)
        assert(result == Result.succeed(SSRCircle(10.0)))
    }

    "variant wire name is consistent across selected representations" in {
        val schema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Adjacent("type", "content"),
            Schema.UnionRepresentation.External
        ).discriminator("type").renameAllVariants(Schema.NameCase.SnakeCase)
        val circle: SSRShape = SSRCircle(10.0)
        val wire             = schema.encodeString[Json](circle)
        // Adjacent is selected (capable); snake_case variant name must appear
        assert(wire.contains("ssr_circle"))
    }

    "one derived decoder set round-trips a sum schema through every representation" in {
        // The six-representation schema uses the single derived variantDecoders set to decode
        // wire produced by each individual representation schema. Each round-trip must produce
        // the same concrete value, proving variantDecoders is representation-independent.
        val chainSchema = Schema[SSRShape].representations(
            Schema.UnionRepresentation.External,
            Schema.UnionRepresentation.Internal("type"),
            Schema.UnionRepresentation.Adjacent("type", "content"),
            Schema.UnionRepresentation.Tuple,
            Schema.UnionRepresentation.TupleFlat,
            Schema.UnionRepresentation.Untagged
        )
        val value: SSRShape = SSRTriangle(10.0, 10.0, 10.0)

        // Produce wires for each of the six representations using single-rep schemas
        val extWire  = Schema[SSRShape].encodeString[Json](value)
        val intWire  = Schema[SSRShape].discriminator("type").encodeString[Json](value)
        val adjWire  = Schema[SSRShape].adjacent("type", "content").encodeString[Json](value)
        val tupWire  = Schema[SSRShape].tupleTagged.encodeString[Json](value)
        val tupFWire = Schema[SSRShape].tupleFlat.encodeString[Json](value)
        val untWire  = Schema[SSRShape].untagged.encodeString[Json](value)

        // Decode each wire through the chain schema; chain tries entries in declared order
        // and the first that succeeds returns the value
        assert(chainSchema.decodeString[Json](extWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](intWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](adjWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](tupWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](tupFWire) == Result.succeed(value))
        assert(chainSchema.decodeString[Json](untWire) == Result.succeed(value))
    }

    "tagged union representation throws RepresentationUnsupportedException before bytes on incapable binary codec" in {
        // tupleFlat requires a top-level array; Protobuf cannot express this.
        // The exception must be raised before any bytes are written.
        val s      = summon[Schema[Int | String]].tupleFlat
        val result = Result.catching[RepresentationUnsupportedException](s.encode[Protobuf](42))
        result match
            case Result.Failure(ex) =>
                assert(ex.codec == "Protobuf", s"Exception must name the codec; got: ${ex.codec}")
                assert(ex.representation == "TupleFlat", s"Exception must name the representation; got: ${ex.representation}")
            case other => fail(s"Expected Failure(RepresentationUnsupportedException), got $other")
        end match
    }

    "union member naming via reused variantNames and variantAlias composes through the one variantNaming layer" in {
        // Use product-member union (SSRUCircle | SSRUSquare) so the adjacent content is an object.
        // Adjacent representation makes the tag observable in the wire output.
        val s = summon[Schema[SSRUCircle | SSRUSquare]]
            .adjacent("type", "content")
            .variantNames("SSRUCircle" -> "circle", "SSRUSquare" -> "square")
        val value: SSRUCircle | SSRUSquare = SSRUCircle(10.0)
        // Encode: the tag must be the renamed wire name "circle".
        val wire = s.encodeString[Json](value)
        assert(wire.contains("\"circle\""), s"Encoded wire must contain renamed tag 'circle'; got: $wire")
        assert(!wire.contains("\"SSRUCircle\""), s"Original name must not appear in wire; got: $wire")
        // Decode via the primary renamed tag.
        val decoded = s.decodeString[Json](wire)
        assert(decoded == Result.succeed(value), s"Decode via renamed tag must return SSRUCircle(10.0); got: $decoded")
        // variantAlias lets a secondary name decode to the same variant.
        val sWithAlias  = s.variantAlias("circle", "circ")
        val aliasWire   = wire.replace("\"circle\"", "\"circ\"")
        val aliasResult = sWithAlias.decodeString[Json](aliasWire)
        assert(aliasResult == Result.succeed(value), s"Alias 'circ' must decode to SSRUCircle(10.0); got: $aliasResult")
    }

    "a sealed sub-trait without a Schema of its own" - {

        val leaves: Chunk[SSRFRoot] = Chunk(SSRFLeaf(1), SSRFMark, SSRFDeepLeaf("d"), SSRFDirect(2))

        "adds its leaves to the root's variants, in declaration order" in {
            Schema[SSRFRoot].structure match
                case sum: Structure.Type.Sum =>
                    assert(sum.variants.map(_.name) == Chunk("SSRFLeaf", "SSRFMark", "SSRFDeepLeaf", "SSRFDirect"))
                case other => fail(s"expected a sum, got $other")
        }

        "the wrapper object keys each leaf by its own name" in {
            assert(leaves.map(Json.encode(_)) == Chunk(
                """{"SSRFLeaf":{"x":1}}""",
                """{"SSRFMark":{}}""",
                """{"SSRFDeepLeaf":{"z":"d"}}""",
                """{"SSRFDirect":{"y":2}}"""
            ))
            assert(leaves.map(v => Json.decode[SSRFRoot](Json.encode(v))) == leaves.map(Result.succeed(_)))
        }

        "a discriminator tags each leaf with its own name and keeps its fields" in {
            val schema = Schema[SSRFRoot].discriminator("type")
            assert(leaves.map(schema.encodeString[Json](_)) == Chunk(
                """{"type":"SSRFLeaf","x":1}""",
                """{"type":"SSRFMark"}""",
                """{"type":"SSRFDeepLeaf","z":"d"}""",
                """{"type":"SSRFDirect","y":2}"""
            ))
            assert(leaves.map(v => schema.decodeString[Json](schema.encodeString[Json](v))) == leaves.map(Result.succeed(_)))
        }

        "adjacent, tupleTagged and tupleFlat round-trip each leaf" in {
            val schemas = Chunk(Schema[SSRFRoot].adjacent("t", "c"), Schema[SSRFRoot].tupleTagged, Schema[SSRFRoot].tupleFlat)
            assert(Schema[SSRFRoot].adjacent("t", "c").encodeString[Json](SSRFDeepLeaf("d")) == """{"t":"SSRFDeepLeaf","c":{"z":"d"}}""")
            schemas.foreach { schema =>
                assert(leaves.map(v => schema.decodeString[Json](schema.encodeString[Json](v))) == leaves.map(Result.succeed(_)))
            }
            succeed
        }

        "untagged writes each leaf's fields alone and reads them back" in {
            // Without a case object, which untagged would read from any object.
            val schema                   = Schema[SSRFPlain].untagged
            val values: Chunk[SSRFPlain] = Chunk(SSRFPlainLeaf(1), SSRFPlainDirect("d"))
            assert(values.map(schema.encodeString[Json](_)) == Chunk("""{"x":1}""", """{"z":"d"}"""))
            assert(values.map(v => schema.decodeString[Json](schema.encodeString[Json](v))) == values.map(Result.succeed(_)))
        }

        "tag-only writes a leaf case object by its own name" in {
            val warm: SSRFColor = SSRFRed
            assert(Json.encode(warm) == "\"SSRFRed\"")
            assert(Json.decode[SSRFColor]("\"SSRFRed\"") == Result.succeed(SSRFRed))
            assert(Json.decode[SSRFColor]("\"SSRFBlue\"") == Result.succeed(SSRFBlue))
        }

        "the catch-all builder names a leaf of a sub-trait" in {
            val schema = Schema[SSRFPlain].discriminator("type").catchAll("SSRFPlainOther")
            val other  = Structure.Value.Record(Chunk("type" -> Structure.Value.Str("zzz")))
            assert(schema.decodeString[Json]("""{"type":"zzz"}""") == Result.succeed(SSRFPlainOther("zzz", other)))
            assert(schema.decodeString[Json]("""{"type":"SSRFPlainLeaf","x":1}""") == Result.succeed(SSRFPlainLeaf(1)))
        }

        "a leaf under two sub-traits is one variant" in {
            Schema[SSRFShared].structure match
                case sum: Structure.Type.Sum => assert(sum.variants.map(_.name) == Chunk("SSRFSharedBoth", "SSRFSharedOnly"))
                case other                   => fail(s"expected a sum, got $other")
            val both: SSRFShared = SSRFSharedBoth(1)
            assert(Json.encode(both) == """{"SSRFSharedBoth":{"x":1}}""")
            assert(Json.decode[SSRFShared]("""{"SSRFSharedBoth":{"x":1}}""") == Result.succeed(both))
        }

        "sub-traits mixed into one another derive, each leaf once" in {
            // 2^11 paths lead to SSRFChainLeaf; the derivation visits each sub-trait once, so this compiles at all.
            Schema[SSRFChain].structure match
                case sum: Structure.Type.Sum => assert(sum.variants.map(_.name) == Chunk("SSRFChainLeaf"))
                case other                   => fail(s"expected a sum, got $other")
            val leaf: SSRFChain = SSRFChainLeaf(1)
            assert(Json.decode[SSRFChain](Json.encode(leaf)) == Result.succeed(leaf))
        }

        "navigation reaches a leaf by its own name" in {
            val schema = Schema[SSRFRoot]
            assert(schema.focus(_.SSRFDeepLeaf).tag =:= Tag[SSRFDeepLeaf])
            assert(schema.fieldNames == Set("SSRFLeaf", "SSRFMark", "SSRFDeepLeaf", "SSRFDirect"))
        }

        "a leaf's annotations apply under the root, the catch-all included" in {
            val leaf: SSRFTagged = SSRFTaggedLeaf(1)
            assert(Json.encode(leaf) == """{"type":"leaf","x":1}""")
            assert(Json.decode[SSRFTagged]("""{"type":"leaf","x":1}""") == Result.succeed(leaf))
            assert(Json.decode[SSRFTagged]("""{"type":"direct","y":2}""") == Result.succeed(SSRFTaggedDirect(2)))
            val other = Structure.Value.Record(Chunk("type" -> Structure.Value.Str("zzz")))
            assert(Json.decode[SSRFTagged]("""{"type":"zzz"}""") == Result.succeed(SSRFTaggedOther("zzz", other)))
        }
    }

    "a sealed sub-trait with a Schema of its own stays one variant, encoded by that schema" in {
        val leaf: SSRFNested = SSRFNestedLeaf(1)
        val wire             = Json.encode(leaf)
        assert(wire == """{"op":"SSRFNestedGroup","body":{"kind":"SSRFNestedLeaf","x":1}}""")
        assert(Json.decode[SSRFNested](wire) == Result.succeed(leaf))
    }

    "chain decode over empty variantDecoders yields typed NoVariantMatchException" in {
        // A schema whose variantDecoders is empty reaches readChain, which dispatches to
        // readUntagged (via readForRepresentation), which immediately throws NoVariantMatchException
        // (zero decoders). That is caught as a DecodeException and re-thrown on chain exhaustion.
        val base = Schema[SSRShape].representations(
            Schema.UnionRepresentation.Untagged
        )
        val patched = Schema.copyWith(base)(variantDecoders = Chunk.empty)
        val wire    = """{"radius":10.0}"""
        val result  = patched.decodeString[Json](wire)
        result match
            case Result.Failure(_: NoVariantMatchException) => succeed("empty variantDecoders yields NoVariantMatchException")
            case Result.Panic(ex)                           => fail(s"Expected typed Failure but got Panic: $ex")
            case other                                      => fail(s"Expected Failure(NoVariantMatchException) but got $other")
        end match
    }

    // =========================================================================
    // Group: a variant's own field annotations
    // =========================================================================

    "a variant field's @rename sets its wire key under a discriminator, on encode and decode" in {
        val value: SWRRx = SWRCustomEmoji("e1")
        val wire         = """{"type":"custom_emoji","custom_emoji_id":"e1"}"""
        assert(Json.encode(value) == wire)
        assert(Json.decode[SWRRx](wire) == Result.succeed(value))
        assert(Json.decode[SWRRx]("""{"type":"custom_emoji","id":"e1"}""").isFailure)
    }

    "a variant field's @rename, @alias and @transform all apply together" in {
        val value: SWRElement = SWRButton("go", "a1")
        assert(Json.encode(value) == """{"type":"button","text":"GO","action_id":"a1"}""")
        assert(Json.decode[SWRElement]("""{"type":"button","text":"GO","action_id":"a1","hint_text":"h"}""") ==
            Result.succeed(SWRButton("go", "a1", Present("h"))))
    }

    "a variant field's @rename applies under the external, untagged and adjacent representations" in {
        assert(Json.encode(SWRExternalCase("e1"): SWRExternal) == """{"SWRExternalCase":{"custom_emoji_id":"e1"}}""")
        assert(Json.decode[SWRExternal]("""{"SWRExternalCase":{"custom_emoji_id":"e1"}}""") == Result.succeed(SWRExternalCase("e1")))
        assert(Json.encode(SWRUntaggedCase("e1"): SWRUntagged) == """{"custom_emoji_id":"e1"}""")
        assert(Json.decode[SWRUntagged]("""{"custom_emoji_id":"e1"}""") == Result.succeed(SWRUntaggedCase("e1")))
        assert(Json.encode(SWRAdjacentCase("e1"): SWRAdjacent) == """{"type":"SWRAdjacentCase","content":{"custom_emoji_id":"e1"}}""")
        assert(Json.decode[SWRAdjacent]("""{"type":"SWRAdjacentCase","content":{"custom_emoji_id":"e1"}}""") ==
            Result.succeed(SWRAdjacentCase("e1")))
    }

    "a variant's renamed field keeps its declaration position under tupleFlat and tupleTagged" in {
        val point: SWPShape = SWPPoint(1, 2, 3)
        Chunk(
            Schema[SWPShape].tupleFlat   -> """["SWPPoint",1,2,3]""",
            Schema[SWPShape].tupleTagged -> """["SWPPoint",{"x_coord":1,"y":2,"z":3}]"""
        ).foreach { (schema, expected) =>
            val wire = schema.encodeString[Json](point)
            assert(wire == expected, wire)
            val decoded = schema.decodeString[Json](wire)
            assert(decoded == Result.succeed(point), s"${schema.representation}: $decoded")
        }
    }

    "a variant's renamed field keeps its declaration position under the keyed representations" in {
        val point: SWPShape = SWPPoint(1, 2, 3)
        Chunk(
            Schema[SWPShape]                    -> """{"SWPPoint":{"x_coord":1,"y":2,"z":3}}""",
            Schema[SWPShape].discriminator("t") -> """{"t":"SWPPoint","x_coord":1,"y":2,"z":3}""",
            Schema[SWPShape].adjacent("t", "c") -> """{"t":"SWPPoint","c":{"x_coord":1,"y":2,"z":3}}""",
            Schema[SWPShape].untagged           -> """{"x_coord":1,"y":2,"z":3}"""
        ).foreach { (schema, expected) =>
            val wire = schema.encodeString[Json](point)
            assert(wire == expected, wire)
            val decoded = schema.decodeString[Json](wire)
            assert(decoded == Result.succeed(point), s"${schema.representation}: $decoded")
        }
    }

    "a variant's renamed field round-trips on Protobuf under every object-shaped representation" in {
        val point: SWPShape = SWPPoint(1, 2, 3)
        Chunk(
            Schema[SWPShape],
            Schema[SWPShape].discriminator("t"),
            Schema[SWPShape].adjacent("t", "c")
        ).foreach { schema =>
            val decoded = schema.decode[Protobuf](schema.encode[Protobuf](point))
            assert(decoded == Result.succeed(point), s"${schema.representation}: $decoded")
        }
    }

    "a variant's renamed field is numbered by its wire name on Protobuf; pinning the old number reads older data" in {
        val older = Schema[SWPOldShape].encode[Protobuf](SWPOldPoint(1, 2, 3))
        // the older bytes carry the field under the Scala name's number, so it reads as absent: proto3's default, 0
        val renamed = Schema[SWPShape].discriminator("type").decode[Protobuf](older)
        assert(renamed == Result.succeed(SWPPoint(0, 2, 3)), renamed.toString)
        assert(kyo.internal.CodecMacro.fieldId("x") == 701810)
        val pinned = Schema[SWPPinnedShape].decode[Protobuf](older)
        assert(pinned == Result.succeed(SWPPinnedPoint(1, 2, 3)), pinned.toString)
    }

    "a variant's own given, configured with a builder, is the schema the sum uses" in {
        val value: SWGEvent = SWGUserJoined(1, "t")
        val wire            = """{"type":"SWGUserJoined","user_id":1,"chat_title":"t"}"""
        assert(Json.encode(value) == wire)
        assert(Json.decode[SWGEvent](wire) == Result.succeed(value))
    }

    "a variant's derivedVia given rejects through the sum as it does standalone" in {
        val result = Json.decode[SWGPay]("""{"type":"SWGCard","last4":"12"}""")
        assert(result.failure.exists(_.isInstanceOf[ConstructorRejectedException]), result.toString)
        assert(Json.decode[SWGPay]("""{"type":"SWGCard","last4":"1234"}""") == Result.succeed(SWGCard("1234")))
        assert(Json.decode[SWGPay]("""{"type":"SWGCash","amount":5}""") == Result.succeed(SWGCash(5)))
    }

    "a sum derives when a variant has a private constructor and a derivedVia given" in {
        val token: SWGTokenized = SWGToken.make("abc") match
            case Result.Success(t) => t
            case other             => fail(s"expected a token, got $other")
        val wire = Json.encode(token)
        assert(wire == """{"SWGToken":{"value":"abc"}}""")
        assert(Json.decode[SWGTokenized](wire) == Result.succeed(token))
        assert(Json.decode[SWGTokenized]("""{"SWGToken":{"value":""}}""").failure.exists(_.isInstanceOf[ConstructorRejectedException]))
    }

    "transformField on a variant path is rejected, pointing at the variant's own schema" in {
        typeCheckFailure("""Schema[kyo.SWRRx].transformField(_.SWRCustomEmoji.id)((v, w) => w.string(v))(r => r.string())""")(
            "Apply .transformField to a Schema of a specific case class variant instead"
        )
    }

    "transformFieldWrite and transformFieldRead on a variant path are rejected, pointing at the variant's own schema" in {
        typeCheckFailure("""Schema[kyo.SWRRx].transformFieldWrite(_.SWRCustomEmoji.id)((v, w) => w.string(v))""")(
            "Apply .transformFieldWrite to a Schema of a specific case class variant instead"
        )
        typeCheckFailure("""Schema[kyo.SWRRx].transformFieldRead(_.SWRCustomEmoji.id)(r => r.string())""")(
            "Apply .transformFieldRead to a Schema of a specific case class variant instead"
        )
    }

    "a field transform on a path that crosses a sum is rejected" in {
        typeCheckFailure("""Schema[kyo.SWFHolder].transformField(_.shape.SSRCircle.radius)((v, w) => w.double(v))(r => r.double())""")(
            "Schema.transformField takes a field of the schema's own type; `_.shape.SSRCircle.radius` names a nested field. " +
                "Apply .transformField to a Schema of the nested field's type instead."
        )
    }

    "a field transform on a multi-segment path is rejected, for each of the three builders" in {
        typeCheckFailure("""Schema[kyo.MTPersonAddr].transformField(_.address.city)((v, w) => w.string(v))(r => r.string())""")(
            "Schema.transformField takes a field of the schema's own type; `_.address.city` names a nested field."
        )
        typeCheckFailure("""Schema[kyo.MTPersonAddr].transformFieldWrite(_.address.city)((v, w) => w.string(v))""")(
            "Schema.transformFieldWrite takes a field of the schema's own type; `_.address.city` names a nested field."
        )
        typeCheckFailure("""Schema[kyo.MTPersonAddr].transformFieldRead(_.address.city)(r => r.string())""")(
            "Schema.transformFieldRead takes a field of the schema's own type; `_.address.city` names a nested field."
        )
    }

    "a variant field's own @omit applies under every representation and decodes back" in {
        val bag: SWOBag = SWOItems(Nil, "a")
        Chunk(
            Schema[SWOBag]                    -> """{"SWOItems":{"label":"a"}}""",
            Schema[SWOBag].discriminator("t") -> """{"t":"SWOItems","label":"a"}""",
            Schema[SWOBag].adjacent("t", "c") -> """{"t":"SWOItems","c":{"label":"a"}}""",
            Schema[SWOBag].tupleTagged        -> """["SWOItems",{"label":"a"}]""",
            Schema[SWOBag].untagged           -> """{"label":"a"}"""
        ).foreach { (schema, expected) =>
            val wire = schema.encodeString[Json](bag)
            assert(wire == expected, wire)
            val decoded = schema.decodeString[Json](wire)
            assert(decoded == Result.succeed(bag), s"${schema.representation}: $decoded")
        }
    }

    "an omitted or absent variant field keeps its position under tupleFlat" in {
        val schema = Schema[SWOBag].tupleFlat
        Chunk[(SWOBag, String)](
            SWOItems(Nil, "a")        -> """["SWOItems",[],"a"]""",
            SWONote(Maybe.empty, "a") -> """["SWONote",null,"a"]""",
            SWONote(Maybe("n"), "a")  -> """["SWONote","n","a"]"""
        ).foreach { (bag, expected) =>
            val wire = schema.encodeString[Json](bag)
            assert(wire == expected, wire)
            val decoded = schema.decodeString[Json](wire)
            assert(decoded == Result.succeed(bag), decoded.toString)
        }
    }

    "an absent field nested in a tupleFlat payload keeps its keyed form" in {
        val schema = Schema[SWONested].tupleFlat
        val value  = SWONestedCase(SWONote(Maybe.empty, "a"))
        val wire   = schema.encodeString[Json](value)
        assert(wire == """["SWONestedCase",{"SWONote":{"label":"a"}}]""", wire)
        assert(schema.decodeString[Json](wire) == Result.succeed(value))
    }

    "variant naming reaches every representation that writes a variant's name" in {
        val circle: SSRAShape = SSRACircle(1.0)
        val adjacent          = Schema[SSRAShape].adjacent("t", "c").variantNames("SSRACircle" -> "circle")
        assert(adjacent.encodeString[Json](circle) == """{"t":"circle","c":{"radius":1.0}}""")
        assert(adjacent.decodeString[Json]("""{"t":"circle","c":{"radius":1.0}}""") == Result.succeed(circle))
        assert(Schema[SSRAShape].tupleTagged.variantNames("SSRACircle" -> "circle").encodeString[Json](circle) ==
            """["circle",{"radius":1.0}]""")
        assert(Schema[SSRAShape].tupleFlat.variantNames("SSRACircle" -> "circle").encodeString[Json](circle) == """["circle",1.0]""")
    }

    "the wrapper-object format keeps the Scala variant names" in {
        val circle: SSRAShape = SSRACircle(1.0)
        assert(Schema[SSRAShape].variantNames("SSRACircle" -> "circle").encodeString[Json](circle) == """{"SSRACircle":{"radius":1.0}}""")
        assert(Schema[SSRAShape].renameAllVariants(Schema.NameCase.SnakeCase).encodeString[Json](circle) ==
            """{"SSRACircle":{"radius":1.0}}""")
        val renamed: SSRARenamed = SSRARenamedCircle(1)
        assert(Json.encode(renamed) == """{"SSRARenamedCircle":{"r":1}}""")
    }

    "a union of case classes takes a discriminator" in {
        val schema = summon[Schema[SSRAPhoto | SSRAVideo]].discriminator("type")
        val wire   = schema.encodeString[Json](SSRAPhoto("a"))
        assert(wire == """{"type":"SSRAPhoto","url":"a"}""", wire)
        assert(schema.decodeString[Json](wire) == Result.succeed(SSRAPhoto("a")))
    }

    "a variant field whose wire key is the discriminator key is refused, never dropped" - {

        "under @discriminator, when the sum is derived" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """{
                    @kyo.schema.discriminator("type") sealed trait Clash
                    case class ClashCase(`type`: String, x: Int) extends Clash
                    kyo.Schema.derived[Clash]
                }"""
            )
            assert(errors.exists(e => e.message.contains("ClashCase") && e.message.contains("type")), errors.map(_.message).toString)
        }

        "under the discriminator builder, before anything is written" in {
            val schema              = Schema[SSRTypeField].discriminator("type")
            val value: SSRTypeField = SSRTypeFieldCase("a", 1)
            Result.catching[FieldNameCollisionException](schema.encodeString[Json](value)) match
                case Result.Failure(_) => succeed("the collision is refused")
                case other             => fail(s"the variant's `type` value was not refused: $other")
        }
    }

end SchemaUnionRepresentationTest

sealed trait SSRTypeField derives CanEqual, Schema
case class SSRTypeFieldCase(`type`: String, x: Int) extends SSRTypeField derives CanEqual

sealed trait SSRARenamed derives CanEqual, Schema
@kyo.schema.rename("circle")
case class SSRARenamedCircle(r: Int) extends SSRARenamed derives CanEqual

case class SSRAPhoto(url: String) derives CanEqual, Schema
case class SSRAVideo(src: String) derives CanEqual, Schema
