package kyo

// --- sums declared as members of a class ---

trait MELevels:
    enum Level derives CanEqual, Schema:
        case One, Two
end MELevels

object MELevelsInTrait extends MELevels

object MEObject:
    class Inner:
        enum Level derives CanEqual, Schema:
            case One, Two
end MEObject

class MEClass:
    class Inner:
        enum Level derives CanEqual, Schema:
            case One, Two
end MEClass

// Nothing here derives a schema, so each derivation happens at the test's site, outside this class.
class MEHolder:
    enum Level derives CanEqual:
        case One, Two
        case Custom(value: Int)

    sealed trait Signal derives CanEqual
    case object Start              extends Signal
    case object Stop               extends Signal
    case class Pause(seconds: Int) extends Signal
end MEHolder

// Declared in the file of the leaf that derives MEScattered: the compiler resolves a sealed trait's children once, so Inside must be
// entered before that derivation is typed.
sealed trait MEScattered
class MEScatteredHost:
    case object Inside extends MEScattered

class SchemaDerivationTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    enum MemberLevel derives CanEqual, Schema:
        case One, Two

    enum MemberShape derives CanEqual, Schema:
        case Point
        case Circle(radius: Int)
        case Origin
    end MemberShape

    case class MemberTask(name: String, level: MemberLevel, shape: MemberShape) derives CanEqual, Schema

    private val noFields = Structure.Value.Record(Chunk.empty)

    private def assertLevels[L](one: L, two: L)(using Schema[L], kyo.test.AssertScope): Unit =
        assert(Structure.encode[L](two) == Structure.Value.VariantCase("Two", noFields))
        assert(Structure.decode[L](Structure.Value.VariantCase("One", noFields)) == Result.succeed(one))

    // enum derivation
    // =========================================================================

    private def roundTripString[C <: Codec, A](s: Schema[A], value: A)(using C, Frame, kyo.test.AssertScope): Unit =
        val wire = s.encodeString[C](value)
        assert(s.decodeString[C](wire) == Result.succeed(value), s"string round-trip failed for $value via $wire")
    end roundTripString

    private def roundTripBytes[C <: Codec, A](s: Schema[A], value: A)(using C, Frame, kyo.test.AssertScope): Unit =
        val wire = s.encode[C](value)
        assert(s.decode[C](wire) == Result.succeed(value), s"byte round-trip failed for $value")
    end roundTripBytes

    "enum derivation" - {

        "simple no-param enum cases produce distinct name-keyed wire text through Json" in {
            val schema = Schema[EDSimple]
            val red    = schema.encodeString[Json](EDSimple.Red)
            assert(red == """{"Red":{}}""", s"unexpected wire for Red: $red")
            assert(schema.decodeString[Json](red) == Result.succeed(EDSimple.Red))
            val green = schema.encodeString[Json](EDSimple.Green)
            assert(green == """{"Green":{}}""", s"unexpected wire for Green: $green")
            assert(schema.decodeString[Json](green) == Result.succeed(EDSimple.Green))
        }

        "simple no-param enum round-trips through every codec" in {
            val schema = Schema[EDSimple]
            val value  = EDSimple.Blue
            roundTripString[Json, EDSimple](schema, value)
            roundTripString[Yaml, EDSimple](schema, value)
            roundTripString[Ion, EDSimple](schema, value)
            roundTripBytes[MsgPack, EDSimple](schema, value)
            roundTripBytes[Protobuf, EDSimple](schema, value)
            roundTripBytes[Bson, EDSimple](schema, value)
            roundTripBytes[IonBinary, EDSimple](schema, value)
        }

        "parameterized enum cases produce concrete wire text through Json" in {
            val schema = Schema[EDParam]
            val point  = schema.encodeString[Json](EDParam.Point(1, 2))
            assert(point == """{"Point":{"x":1,"y":2}}""", s"unexpected wire for Point: $point")
            assert(schema.decodeString[Json](point) == Result.succeed(EDParam.Point(1, 2)))
        }

        "parameterized enum round-trips through every codec" in {
            val schema = Schema[EDParam]
            val value  = EDParam.Line(1, 2, 3)
            roundTripString[Json, EDParam](schema, value)
            roundTripString[Yaml, EDParam](schema, value)
            roundTripString[Ion, EDParam](schema, value)
            roundTripBytes[MsgPack, EDParam](schema, value)
            roundTripBytes[Protobuf, EDParam](schema, value)
            roundTripBytes[Bson, EDParam](schema, value)
            roundTripBytes[IonBinary, EDParam](schema, value)
        }

        "mixed parameterized and no-arg enum cases round-trip distinctly through every codec" in {
            val schema        = Schema[EDMixed]
            val named         = EDMixed.Named("x")
            val anon: EDMixed = EDMixed.Anonymous
            roundTripString[Json, EDMixed](schema, named)
            roundTripString[Json, EDMixed](schema, anon)
            roundTripString[Yaml, EDMixed](schema, named)
            roundTripString[Yaml, EDMixed](schema, anon)
            roundTripString[Ion, EDMixed](schema, named)
            roundTripString[Ion, EDMixed](schema, anon)
            roundTripBytes[MsgPack, EDMixed](schema, named)
            roundTripBytes[MsgPack, EDMixed](schema, anon)
            roundTripBytes[Protobuf, EDMixed](schema, named)
            roundTripBytes[Protobuf, EDMixed](schema, anon)
            roundTripBytes[Bson, EDMixed](schema, named)
            roundTripBytes[Bson, EDMixed](schema, anon)
            roundTripBytes[IonBinary, EDMixed](schema, named)
            roundTripBytes[IonBinary, EDMixed](schema, anon)
        }

        "case with multiple params and defaults round-trips, with and without Scala-default-valued fields" in {
            val schema            = Schema[EDDefaults]
            val full              = EDDefaults.Full(1, "custom", false)
            val defaultLike       = EDDefaults.Full(1, "def-b", true)
            val empty: EDDefaults = EDDefaults.Empty
            roundTripString[Json, EDDefaults](schema, full)
            roundTripString[Json, EDDefaults](schema, defaultLike)
            roundTripString[Json, EDDefaults](schema, empty)
        }

        "enum with methods and vals in the body derives and round-trips, ignoring the extra members" in {
            val schema = Schema[EDWithBody]
            val alpha  = EDWithBody.Alpha(5)
            assert(alpha.describe == "alpha-5")
            roundTripString[Json, EDWithBody](schema, alpha)
            val beta: EDWithBody = EDWithBody.Beta
            assert(beta.describe == "beta")
            roundTripString[Json, EDWithBody](schema, beta)
        }

        "enum extending a trait derives and round-trips" in {
            val schema = Schema[EDExtendsTrait]
            val one    = EDExtendsTrait.One(3)
            assert(one.kind == "labelled")
            roundTripString[Json, EDExtendsTrait](schema, one)
            val two: EDExtendsTrait = EDExtendsTrait.Two
            roundTripString[Json, EDExtendsTrait](schema, two)
        }

        "enum declared inside an object (nested enum) derives and round-trips" in {
            val schema = Schema[EDHolder.Nested]
            roundTripString[Json, EDHolder.Nested](schema, EDHolder.Nested.X)
            roundTripString[Json, EDHolder.Nested](schema, EDHolder.Nested.Y(9))
        }

        "enum case with a field typed as another enum (enum-in-enum) derives and round-trips" in {
            val schema = Schema[EDOuter]
            val wrap   = EDOuter.Wrap(EDInner.B(4))
            roundTripString[Json, EDOuter](schema, wrap)
            val plain: EDOuter = EDOuter.Plain
            roundTripString[Json, EDOuter](schema, plain)
        }

        "enum companion with a custom apply factory does not disturb derived Schema" in {
            val schema = Schema[EDCustomApply]
            val made   = EDCustomApply.make("abc")
            assert(made == EDCustomApply.Item("ABC"), s"custom apply factory must uppercase: $made")
            roundTripString[Json, EDCustomApply](schema, made)
            roundTripString[Json, EDCustomApply](schema, EDCustomApply.Blank)
        }

        "single-case enum derives and round-trips" in {
            val schema = Schema[EDSingle]
            roundTripString[Json, EDSingle](schema, EDSingle.Only(7))
        }

        "enum with 25 cases (an arity boundary) round-trips every case distinctly through Json and Protobuf" in {
            val schema = Schema[EDMany]
            val all    = List(
                EDMany.M0,
                EDMany.M1,
                EDMany.M2,
                EDMany.M3,
                EDMany.M4,
                EDMany.M5,
                EDMany.M6,
                EDMany.M7,
                EDMany.M8,
                EDMany.M9,
                EDMany.M10,
                EDMany.M11,
                EDMany.M12,
                EDMany.M13,
                EDMany.M14,
                EDMany.M15,
                EDMany.M16,
                EDMany.M17,
                EDMany.M18,
                EDMany.M19,
                EDMany.M20,
                EDMany.M21,
                EDMany.M22,
                EDMany.M23,
                EDMany.M24
            )
            val jsonWires = all.map(schema.encodeString[Json])
            assert(jsonWires.distinct.size == 25, s"expected 25 distinct Json wire values, got ${jsonWires.distinct.size}")
            all.zip(jsonWires).foreach { (value, wire) =>
                assert(schema.decodeString[Json](wire) == Result.succeed(value), s"Json round-trip failed for $value via $wire")
            }
            val protoBytes = all.map(schema.encode[Protobuf])
            assert(
                protoBytes.map(_.toArray.toSeq).distinct.size == 25,
                "Protobuf bytes must be distinct across all 25 cases"
            )
            all.zip(protoBytes).foreach { (value, bytes) =>
                assert(schema.decode[Protobuf](bytes) == Result.succeed(value), s"Protobuf round-trip failed for $value")
            }
        }

        "declared case ordinal never appears in the wire encoding; only the declared case name does" in {
            // EDMany.M10 has ordinal 10; the wire must carry the NAME "M10", never a bare "10".
            val schema = Schema[EDMany]
            val wire   = schema.encodeString[Json](EDMany.M10)
            assert(wire == """{"M10":{}}""", s"expected name-keyed wrapper, got: $wire")
        }

        "enum as a product field round-trips through every codec" in {
            val schema = Schema[EDHolderProduct]
            val value  = EDHolderProduct(1, EDMixed.Named("x"))
            roundTripString[Json, EDHolderProduct](schema, value)
            roundTripString[Yaml, EDHolderProduct](schema, value)
            roundTripString[Ion, EDHolderProduct](schema, value)
            roundTripBytes[MsgPack, EDHolderProduct](schema, value)
            roundTripBytes[Protobuf, EDHolderProduct](schema, value)
            roundTripBytes[Bson, EDHolderProduct](schema, value)
            roundTripBytes[IonBinary, EDHolderProduct](schema, value)
        }

        "Option[enum] field round-trips present and absent through every codec" in {
            val schema  = Schema[EDOptionHolder]
            val present = EDOptionHolder(Some(EDMixed.Anonymous))
            val absent  = EDOptionHolder(None)
            roundTripString[Json, EDOptionHolder](schema, present)
            roundTripString[Json, EDOptionHolder](schema, absent)
            roundTripBytes[MsgPack, EDOptionHolder](schema, present)
            roundTripBytes[MsgPack, EDOptionHolder](schema, absent)
            roundTripBytes[Bson, EDOptionHolder](schema, present)
            roundTripBytes[Bson, EDOptionHolder](schema, absent)
        }

        "List[enum] field round-trips through every codec" in {
            val schema = Schema[EDListHolder]
            val value  = EDListHolder(List(EDMixed.Named("a"), EDMixed.Anonymous, EDMixed.Named("b")))
            roundTripString[Json, EDListHolder](schema, value)
            roundTripString[Yaml, EDListHolder](schema, value)
            roundTripBytes[MsgPack, EDListHolder](schema, value)
            roundTripBytes[Protobuf, EDListHolder](schema, value)
            roundTripBytes[Bson, EDListHolder](schema, value)
        }

        "Map[String, enum] (enum as a map VALUE) round-trips through every codec" in {
            val schema = Schema[EDMapValueHolder]
            val value  = EDMapValueHolder(Map("a" -> EDMixed.Named("x"), "b" -> EDMixed.Anonymous))
            roundTripString[Json, EDMapValueHolder](schema, value)
            roundTripBytes[MsgPack, EDMapValueHolder](schema, value)
            roundTripBytes[Protobuf, EDMapValueHolder](schema, value)
            roundTripBytes[Bson, EDMapValueHolder](schema, value)
        }

        "Map[enum, V] (enum as a map KEY) round-trips through self-describing and binary-document codecs; Protobuf rejects the non-scalar key" in {
            val schema = Schema[EDMapKeyHolder]
            val value  = EDMapKeyHolder(Map(EDMixed.Named("k") -> 1, EDMixed.Anonymous -> 2))
            roundTripString[Json, EDMapKeyHolder](schema, value)
            roundTripString[Yaml, EDMapKeyHolder](schema, value)
            roundTripBytes[Bson, EDMapKeyHolder](schema, value)
            roundTripBytes[IonBinary, EDMapKeyHolder](schema, value)
            // proto3 admits only scalar map keys (Protobuf.isProto3MapKey); an enum (Sum) key is
            // rejected with a typed SchemaNotSerializableException rather than silently mis-encoded.
            val result = Result.catching[SchemaNotSerializableException](schema.encode[Protobuf](value))
            assert(result.isFailure, s"expected Protobuf to reject an enum map key; got $result")
        }

        "Set[enum] field round-trips through Json" in {
            val schema = Schema[EDSetHolder]
            val value  = EDSetHolder(Set(EDSimple.Red, EDSimple.Blue))
            roundTripString[Json, EDSetHolder](schema, value)
        }

        "explicitly summoned Schema.derived for an enum matches the derives-clause instance" in {
            val derivesSchema                   = Schema[EDMixed]
            val summonedSchema: Schema[EDMixed] = Schema.derived
            val value                           = EDMixed.Named("z")
            assert(derivesSchema.encodeString[Json](value) == summonedSchema.encodeString[Json](value))
        }

        "enum-typed product field survives sibling .rename and .drop transforms on the containing product" in {
            val value = EDTransformHolder(EDMixed.Named("v"), "hidden")
            val json  = edTransformHolderSchema.encodeString[Json](value)
            assert(json.contains("\"type_of\""), s"renamed field name must appear: $json")
            assert(!json.contains("\"secret\"") && !json.contains("hidden"), s"dropped field must not appear: $json")
            assert(json.contains("\"Named\""), s"enum field's own internal representation must render unchanged: $json")
            val decoded = edTransformHolderSchema.decodeString[Json](json).getOrThrow
            assert(decoded.kind == EDMixed.Named("v"), s"enum field must survive rename+drop transform: $decoded")
        }

        "enum round-trips under discriminator, adjacent, and untagged UnionRepresentation" in {
            val value: EDMixed = EDMixed.Named("z")

            val discSchema = Schema[EDMixed].discriminator("type")
            val discJson   = discSchema.encodeString[Json](value)
            assert(discJson == """{"type":"Named","label":"z"}""", s"discriminator wire: $discJson")
            assert(discSchema.decodeString[Json](discJson) == Result.succeed(value))

            val adjSchema = Schema[EDMixed].adjacent("type", "content")
            val adjJson   = adjSchema.encodeString[Json](value)
            assert(adjJson == """{"type":"Named","content":{"label":"z"}}""", s"adjacent wire: $adjJson")
            assert(adjSchema.decodeString[Json](adjJson) == Result.succeed(value))

            val untaggedSchema = Schema[EDMixed].untagged
            val untaggedJson   = untaggedSchema.encodeString[Json](value)
            assert(untaggedSchema.decodeString[Json](untaggedJson) == Result.succeed(value), s"untagged round-trip: $untaggedJson")
        }

        "a @proto.fieldNumber pin on a parameterized enum case's own field affects Protobuf wire numbering, not Json field names" in {
            val schema           = Schema[EDPinCase]
            val value: EDPinCase = EDPinCase.Pinned(3, 4)
            val json             = schema.encodeString[Json](value)
            assert(json.contains("\"x\":3") && json.contains("\"y\":4"), s"Json must use plain field names regardless of the pin: $json")
            val audit = Protobuf.fieldNumberAudit[EDPinCase]
            val xRow  = audit.find(_.name == "x")
            assert(xRow.exists(r => r.number == 99 && r.pinned), s"pinned field x must report wire number 99, pinned=true: $audit")
            val bytes = schema.encode[Protobuf](value)
            val back  = schema.decode[Protobuf](bytes)
            assert(back == Result.succeed(value), s"Protobuf round-trip with a pinned variant field failed: $back")
        }

        "Protobuf Strict conformance rejects a non-scalar (enum) map key through the generic Schema.encode entry point, not only Protobuf.encode" in {
            val schema       = Schema[EDMapKeyHolder]
            val value        = EDMapKeyHolder(Map(EDMixed.Named("k") -> 1))
            val viaGeneric   = Result.catching[SchemaNotSerializableException](schema.encode[Protobuf](value))
            val viaCompanion =
                Result.catching[SchemaNotSerializableException](Protobuf.encode(value)(using summon[Protobuf], schema, summon[Frame]))
            assert(
                viaGeneric.isFailure,
                s"Schema[A].encode[Protobuf] must reject an enum map key exactly like Protobuf.encode; got $viaGeneric"
            )
            assert(viaCompanion.isFailure, s"Protobuf.encode must reject an enum map key; got $viaCompanion")
        }
    }

    "sealed case class derivation" - {

        "single-field sealed case class derives as a product, not a sum" in {
            val schema = Schema.derived[SCCSingle]
            assert(schema.structure.isInstanceOf[Structure.Type.Product], s"expected a Product structure, got ${schema.structure}")
            val json = schema.encodeString[Json](SCCSingle(7))
            assert(json == """{"x":7}""", s"wire: $json")
            assert(schema.decodeString[Json](json) == Result.succeed(SCCSingle(7)))
        }

        "multi-field sealed case class round-trips" in {
            val schema = Schema.derived[SCCMulti]
            val value  = SCCMulti("ada", 36)
            val json   = schema.encodeString[Json](value)
            assert(json == """{"name":"ada","age":36}""", s"wire: $json")
            assert(schema.decodeString[Json](json) == Result.succeed(value))
        }

        "sealed case class nested as a field of another product round-trips" in {
            val schema = Schema.derived[SCCHolder]
            val value  = SCCHolder(1, SCCSingle(2))
            val json   = schema.encodeString[Json](value)
            assert(json == """{"id":1,"inner":{"x":2}}""", s"wire: $json")
            assert(schema.decodeString[Json](json) == Result.succeed(value))
        }

        "sealed case class wire shape matches the same class without sealed" in {
            val sealedJson = Schema.derived[SCCSingle].encodeString[Json](SCCSingle(3))
            val plainJson  = Schema.derived[SCCPlainTwin].encodeString[Json](SCCPlainTwin(3))
            assert(sealedJson == plainJson, s"sealed=$sealedJson plain=$plainJson")
        }

        "a genuine sealed hierarchy still derives as a sum" in {
            val schema = Schema.derived[SCCShape]
            assert(schema.structure.isInstanceOf[Structure.Type.Sum], s"expected a Sum structure, got ${schema.structure}")
            val value: SCCShape = SCCShape.Circle(2)
            val json            = schema.encodeString[Json](value)
            assert(json == """{"Circle":{"r":2}}""", s"wire: $json")
            assert(schema.decodeString[Json](json) == Result.succeed(value))
        }

        "a sealed case class variant of a sealed trait round-trips as that variant's product" in {
            val schema         = Schema.derived[SCCNode]
            val value: SCCNode = SCCNode.Leaf(4)
            val json           = schema.encodeString[Json](value)
            assert(json == """{"Leaf":{"v":4}}""", s"wire: $json")
            assert(schema.decodeString[Json](json) == Result.succeed(value))
        }

        "an intermediate sealed abstract class adds its cases as variants instead of becoming a zero-field product" in {
            val schema        = Schema.derived[SCCTop]
            val value: SCCTop = SCCTop.Mid.Concrete(5)
            val json          = schema.encodeString[Json](value)
            assert(json == """{"Concrete":{"n":5}}""", s"wire: $json")
            assert(schema.decodeString[Json](json) == Result.succeed(value))
        }

        "an abstract case class reports its missing constructor, not a wrong shape" in {
            val src  = "sealed abstract case class SCCAbstractProbe(v: Int) derives kyo.Schema"
            val errs = scala.compiletime.testing.typeCheckErrors(src)
            assert(errs.nonEmpty)
            val msg = errs.head.message
            assert(msg.contains("no accessible constructor"), s"message must name the obstacle: $msg")
            assert(!msg.contains("sealed trait"), s"message must not misclassify the type: $msg")
        }
    }

    "derivedVia" - {

        "a sealed abstract case class round-trips through its smart constructor" in {
            val schema = summon[Schema[DVPort]]
            val port   = DVPort.make(8080).toMaybe.get
            val json   = schema.encodeString[Json](port)
            assert(json == """{"value":8080}""", s"wire: $json")
            assert(schema.decodeString[Json](json) == Result.succeed(port))
        }

        "the wire shape is the same as the plain case class with the same field" in {
            val viaJson   = summon[Schema[DVPort]].encodeString[Json](DVPort.make(443).toMaybe.get)
            val plainJson = Schema.derived[DVPortTwin].encodeString[Json](DVPortTwin(443))
            assert(viaJson == plainJson, s"via=$viaJson plain=$plainJson")
        }

        "the derived structure is a Product over the case fields" in {
            summon[Schema[DVPort]].structure match
                case product: Structure.Type.Product =>
                    assert(product.fields.map(_.name) == Chunk("value"), s"fields: ${product.fields}")
                case other => fail(s"expected a Product structure, got $other")
        }

        "a rejected value decodes to a ConstructorRejectedException carrying the constructor's reason" in {
            summon[Schema[DVPort]].decodeString[Json]("""{"value":0}""") match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.typeName == "DVPort")
                    assert(e.getMessage.contains("port out of range: 0"), s"message: ${e.getMessage}")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
        }

        "the invariant holds on every codec, not just Json" in {
            val schema = summon[Schema[DVPort]]
            val port   = DVPort.make(22).toMaybe.get
            assert(schema.decode[Protobuf](schema.encode[Protobuf](port)) == Result.succeed(port))
            assert(schema.decode[MsgPack](schema.encode[MsgPack](port)) == Result.succeed(port))
            val rejected = schema.decode[Protobuf](Schema.derived[DVPortTwin].encode[Protobuf](DVPortTwin(-1)))
            assert(rejected.isFailure, s"a negative port must not decode: $rejected")
        }

        "a two-argument constructor takes the case fields in declaration order" in {
            val schema = summon[Schema[DVRange]]
            val range  = DVRange.make(1, 9).toMaybe.get
            val json   = schema.encodeString[Json](range)
            assert(json == """{"low":1,"high":9}""", s"wire: $json")
            assert(schema.decodeString[Json](json) == Result.succeed(range))
            assert(schema.decodeString[Json]("""{"low":9,"high":1}""").isFailure)
        }

        "a total constructor applies on decode, so the decoded value is the constructed one" in {
            val schema = summon[Schema[DVUpper]]
            assert(schema.decodeString[Json]("""{"name":"ada"}""") == Result.succeed(DVUpper("ADA")))
            assert(schema.encodeString[Json](DVUpper("ADA")) == """{"name":"ADA"}""")
        }

        "an Option constructor reports absence as a decode failure" in {
            val schema = summon[Schema[DVEven]]
            assert(schema.decodeString[Json]("""{"n":4}""") == Result.succeed(DVEven(4)))
            schema.decodeString[Json]("""{"n":5}""") match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.getMessage.contains("returned None"), s"message: ${e.getMessage}")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
            end match
        }

        "a Maybe constructor reports absence as a decode failure" in {
            val schema = summon[Schema[DVPositive]]
            assert(schema.decodeString[Json]("""{"v":3}""") == Result.succeed(DVPositive(3L)))
            schema.decodeString[Json]("""{"v":0}""") match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.getMessage.contains("returned Absent"), s"message: ${e.getMessage}")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
            end match
        }

        "an Either constructor carries its Left value into the decode failure" in {
            val schema = summon[Schema[DVCode]]
            assert(schema.decodeString[Json]("""{"code":"sku"}""") == Result.succeed(DVCode("sku")))
            schema.decodeString[Json]("""{"code":""}""") match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.getMessage.contains("empty code"), s"message: ${e.getMessage}")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
            end match
        }

        "a Try constructor carries its exception into the decode failure as the cause" in {
            val schema = summon[Schema[DVShort]]
            assert(schema.decodeString[Json]("""{"raw":"abc"}""") == Result.succeed(DVShort("abc")))
            schema.decodeString[Json]("""{"raw":"abcd"}""") match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.getMessage.contains("too long"), s"message: ${e.getMessage}")
                    assert(e.getCause.isInstanceOf[IllegalArgumentException], s"cause: ${e.getCause}")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
            end match
        }

        "an Option field keeps its optional wire treatment when decoding through a constructor" in {
            val schema = summon[Schema[DVTagged]]
            assert(schema.decodeString[Json]("""{"id":1,"label":"x"}""") == Result.succeed(DVTagged(1, Some("x"))))
            assert(schema.decodeString[Json]("""{"id":1}""") == Result.succeed(DVTagged(1, None)))
        }

        "the constructed type is inferred from the constructor with no expected type to pin it" in {
            val schema: Schema[DVPort] = Schema.derivedVia(DVPort.make)
            assert(schema.encodeString[Json](DVPort.make(80).toMaybe.get) == """{"value":80}""")
        }

        "a constructor's rejection carries the decoding caller's Frame, not the given's" in {
            Json.decode[DVSpan]("""{"lo":2,"hi":1}""") match
                case Result.Failure(e: ConstructorRejectedException) =>
                    e.rejection match
                        case leaf: DVInvalidSpan => assert(leaf.frame == e.frame, s"rejected at ${leaf.frame}, decoded at ${e.frame}")
                        case other               => fail(s"expected the constructor's own failure, got $other")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
        }

        "a derivedVia type nested as a field decodes through its constructor, not around it" in {
            val schema = Schema.derived[DVListener]
            assert(schema.encodeString[Json](DVListener("api", DVPort.make(80).toMaybe.get)) == """{"name":"api","port":{"value":80}}""")
            assert(schema.decodeString[Json]("""{"name":"api","port":{"value":80}}""").isSuccess)
            schema.decodeString[Json]("""{"name":"api","port":{"value":-1}}""") match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.typeName == "DVPort", s"typeName: ${e.typeName}")
                case other => fail(s"a nested rejection must fail the outer decode, got $other")
            end match
        }

        "a generic case class derives through a constructor at a concrete type argument" in {
            val schema = summon[Schema[DVNonEmpty[Int]]]
            val value  = DVNonEmpty.make(Chunk(1, 2)).toMaybe.get
            val json   = schema.encodeString[Json](value)
            assert(json == """{"items":[1,2]}""", s"wire: $json")
            assert(schema.decodeString[Json](json) == Result.succeed(value))
            schema.decodeString[Json]("""{"items":[]}""") match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.getMessage.contains("must not be empty"), s"message: ${e.getMessage}")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
            end match
        }

        "a constructor whose arity does not match the case fields is a compile error naming them" in {
            val errs = scala.compiletime.testing.typeCheckErrors(
                "kyo.Schema.derivedVia((a: Int, b: Int) => kyo.DVEven(a)): kyo.Schema[kyo.DVEven]"
            )
            assert(errs.nonEmpty)
            val msg = errs.map(_.message).mkString("\n")
            assert(msg.contains("expects a constructor of 1 argument"), s"message: $msg")
            assert(msg.contains("(n: scala.Int)"), s"message: $msg")
        }

        "a constructor argument that does not accept its case field is a compile error naming the field" in {
            val errs = scala.compiletime.testing.typeCheckErrors(
                "kyo.Schema.derivedVia((s: String) => kyo.DVEven(s.length)): kyo.Schema[kyo.DVEven]"
            )
            assert(errs.nonEmpty)
            val msg = errs.map(_.message).mkString("\n")
            assert(msg.contains("case field 'n'"), s"message: $msg")
        }
    }

    "an enum declared as a member of a class" - {

        "encodes a case as its variant" in {
            assert(Structure.encode[MemberLevel](MemberLevel.Two) == Structure.Value.VariantCase("Two", noFields))
        }

        "decodes a case" in {
            val decoded = Structure.decode[MemberLevel](Structure.Value.VariantCase("One", noFields))
            assert(decoded == Result.succeed(MemberLevel.One), s"decoded $decoded")
        }

        "as a member of a trait" in {
            assertLevels(MELevelsInTrait.Level.One, MELevelsInTrait.Level.Two)
        }

        "as a member of a class nested in an object" in {
            val inner = new MEObject.Inner
            assertLevels(inner.Level.One, inner.Level.Two)
        }

        "as a member of a class nested in a class" in {
            val outer = new MEClass
            val inner = new outer.Inner
            assertLevels(inner.Level.One, inner.Level.Two)
        }

        "a case with a payload beside cases without one" in {
            val circle = MemberShape.Circle(3)
            assert(Structure.encode[MemberShape](circle) == Structure.Value.VariantCase(
                "Circle",
                Structure.Value.Record(Chunk("radius" -> Structure.Value.Integer(3)))
            ))
            Chunk(MemberShape.Point, circle, MemberShape.Origin).foreach { shape =>
                val decoded = Structure.decode[MemberShape](Structure.encode[MemberShape](shape))
                assert(decoded == Result.succeed(shape), s"decoded $decoded")
            }
        }

        "a case class field of a member enum type" in {
            val task    = MemberTask("ship", MemberLevel.Two, MemberShape.Origin)
            val encoded = Structure.encode(task)
            assert(encoded == Structure.Value.Record(Chunk(
                "name"  -> Structure.Value.Str("ship"),
                "level" -> Structure.Value.VariantCase("Two", noFields),
                "shape" -> Structure.Value.VariantCase("Origin", noFields)
            )))
            assert(Structure.decode[MemberTask](encoded) == Result.succeed(task))
        }

        "derived at a site outside the class" in {
            val holder                 = new MEHolder
            given Schema[holder.Level] = Schema.derived[holder.Level]
            assertLevels(holder.Level.One, holder.Level.Two)
            val custom = holder.Level.Custom(7)
            assert(Structure.decode[holder.Level](Structure.encode[holder.Level](custom)) == Result.succeed(custom))
        }
    }

    "a case with no path from the sum type is a compile error naming the case" - {

        "a case declared inside a class the sum is not in" in {
            val errs = scala.compiletime.testing.typeCheckErrors("kyo.Schema.derived[kyo.MEScattered]")
            val msg  = errs.map(_.message).mkString("\n")
            assert(msg.contains("its case Inside is declared inside MEScatteredHost, which is not an object"), s"message: $msg")
        }

        "a member enum reached through a type projection" in {
            val errs = scala.compiletime.testing.typeCheckErrors("kyo.Schema.derived[kyo.MEHolder#Level]")
            val msg  = errs.map(_.message).mkString("\n")
            assert(msg.contains("its case One cannot be selected from kyo.MEHolder"), s"message: $msg")
        }
    }

    "an enum local to a method" in {
        enum Level derives CanEqual, Schema:
            case One, Two
        assertLevels(Level.One, Level.Two)
    }

    "a sealed trait declared as a member of a class, derived at a site outside the class" in {
        val holder                  = new MEHolder
        given Schema[holder.Signal] = Schema.derived[holder.Signal]
        assert(Structure.encode[holder.Signal](holder.Stop) == Structure.Value.VariantCase("Stop", noFields))
        Chunk[holder.Signal](holder.Start, holder.Stop, holder.Pause(5)).foreach { signal =>
            val decoded = Structure.decode[holder.Signal](Structure.encode[holder.Signal](signal))
            assert(decoded == Result.succeed(signal), s"decoded $decoded")
        }
    }

    "a record declared in the scope of an opaque type over String keeps its annotations when derived outside the scope" in {
        import OSHolderSchemas.given
        val value = OSHolder.Labelled("a", 1)
        assert(Json.encode(value) == """{"the_label":"a","count":1}""")
        assert(Json.decode[OSHolder.Labelled]("""{"the_label":"a","count":1}""") == Result.succeed(value))
    }

end SchemaDerivationTest
