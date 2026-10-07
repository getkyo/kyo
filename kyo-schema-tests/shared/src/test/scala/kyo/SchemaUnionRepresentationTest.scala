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

// The leaf extends the top sub-trait, not the bottom one. A class below the whole chain has 2^23 ancestor paths, and the Scala.js
// linker's heap grows with them: a leaf there takes the test module's JS link to 5.2GB of live heap, against 2.4GB here. The
// derivation walks the sub-traits from the sum downward, so their 2^23 paths reach it all the same.
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
sealed trait SSRFChain13               extends SSRFChain with SSRFChain12
sealed trait SSRFChain14               extends SSRFChain with SSRFChain13
sealed trait SSRFChain15               extends SSRFChain with SSRFChain14
sealed trait SSRFChain16               extends SSRFChain with SSRFChain15
sealed trait SSRFChain17               extends SSRFChain with SSRFChain16
sealed trait SSRFChain18               extends SSRFChain with SSRFChain17
sealed trait SSRFChain19               extends SSRFChain with SSRFChain18
sealed trait SSRFChain20               extends SSRFChain with SSRFChain19
sealed trait SSRFChain21               extends SSRFChain with SSRFChain20
sealed trait SSRFChain22               extends SSRFChain with SSRFChain21
sealed trait SSRFChain23               extends SSRFChain with SSRFChain22
sealed trait SSRFChain24               extends SSRFChain with SSRFChain23
final case class SSRFChainLeaf(x: Int) extends SSRFChain1 derives CanEqual

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

    // A variant whose read fails with an error that is not a decode failure.
    private def throwingVariant(message: String): () => Schema[Any] =
        val variant = Schema.init[Any](
            writeFn = (_: Any, _: Codec.Writer) => (),
            readFn = (_: Codec.Reader) => throw new IllegalStateException(message)
        )
        () => variant
    end throwingVariant

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
        val schema           = Schema[SSRShape].discriminator("first").discriminator("second")
        val circle: SSRShape = SSRCircle(10.0)

        // Last discriminator call wins for both fields
        assert(schema.representation == Schema.UnionRepresentation.Internal("second"))

        val wire = schema.encodeString[Json](circle)
        assert(wire == """{"second":"SSRCircle","radius":10.0}""")
        assert(!wire.contains("\"first\""))
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
        // Replace the first variant with one whose read throws to verify the Panic surfaces.
        val base    = Schema[SSRUShape].untagged
        val patched = Schema.copyWith(base)(
            variantSchemas = Chunk(throwingVariant("injected unexpected decoder failure")) ++ base.variantSchemas.drop(1)
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

    "a union of case classes takes a discriminator" in {
        val schema = summon[Schema[SSRAPhoto | SSRAVideo]].discriminator("type")
        val wire   = schema.encodeString[Json](SSRAPhoto("a"))
        assert(wire == """{"type":"SSRAPhoto","url":"a"}""", wire)
        assert(schema.decodeString[Json](wire) == Result.succeed(SSRAPhoto("a")))
    }

end SchemaUnionRepresentationTest

sealed trait SSRTypeField derives CanEqual, Schema
case class SSRTypeFieldCase(`type`: String, x: Int) extends SSRTypeField derives CanEqual

sealed trait SSRARenamed derives CanEqual, Schema
@kyo.schema.rename("circle")
case class SSRARenamedCircle(r: Int) extends SSRARenamed derives CanEqual

case class SSRAPhoto(url: String) derives CanEqual, Schema
case class SSRAVideo(src: String) derives CanEqual, Schema
