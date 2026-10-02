package kyo

import kyo.schema.*

class SchemaCatchAllTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private val scroll = Structure.Value.Record(Chunk("type" -> Structure.Value.Str("scroll"), "dy" -> Structure.Value.Integer(3)))
    private val dy     = Structure.Value.Record(Chunk("dy" -> Structure.Value.Integer(3)))

    "under a discriminator" - {

        "an unknown tag gives the catch-all variant with the tag and the whole object" in {
            assert(Json.decode[SCAEvent]("""{"type":"scroll","dy":3}""") == Result.succeed(SCAUnknown("scroll", scroll)))
        }

        "a known tag decodes its own variant" in {
            assert(Json.decode[SCAEvent]("""{"type":"SCAClick","x":1}""") == Result.succeed(SCAClick(1)))
        }

        "encode writes the object that was read" in {
            val value: SCAEvent = SCAUnknown("scroll", scroll)
            assert(Json.encode(value) == """{"type":"scroll","dy":3}""")
        }

        "a missing tag key is still a missing field" in {
            Json.decode[SCAEvent]("""{"dy":3}""") match
                case Result.Failure(e: MissingFieldException) => assert(e.fieldName == "type")
                case other                                    => fail(s"expected MissingFieldException, got $other")
        }
    }

    "adjacent: the tag and the content value" in {
        val schema = Schema[SCAEvent].adjacent("t", "c")
        assert(schema.decodeString[Json]("""{"t":"scroll","c":{"dy":3}}""") == Result.succeed(SCAUnknown("scroll", dy)))
        assert(schema.encodeString[Json](SCAUnknown("scroll", dy)) == """{"t":"scroll","c":{"dy":3}}""")
        assert(schema.decodeString[Json]("""{"t":"SCAClick","c":{"x":1}}""") == Result.succeed(SCAClick(1)))
    }

    "a sum with a catch-all as an adjacent variant's content, read from JSON and from a captured value" in {
        val known  = """{"t":"event","c":{"type":"SCAClick","x":1}}"""
        val other  = """{"t":"event","c":{"type":"scroll","dy":3}}"""
        val schema = Schema[SCAEnvelope]
        assert(schema.decodeString[Json](known) == Result.succeed(SCAEventEnvelope(SCAClick(1))))
        assert(schema.decodeString[Json](other) == Result.succeed(SCAEventEnvelope(SCAUnknown("scroll", scroll))))
        // a sum with a catch-all of its own around it reads the envelope from the value it captured
        val outer = Schema[SCAOuter]
        assert(outer.decodeString[Json](known) == Result.succeed(SCAOuterEnvelope(SCAEventEnvelope(SCAClick(1)))))
        assert(schema.encodeString[Json](SCAEventEnvelope(SCAClick(1))) == """{"t":"event","c":{"type":"SCAClick","x":1}}""")
    }

    "the wrapper object: the key as the tag and its value" in {
        assert(Json.decode[SCAPlain]("""{"wheel":{"dy":3}}""") == Result.succeed(SCAPlainUnknown("wheel", dy)))
        val value: SCAPlain = SCAPlainUnknown("wheel", dy)
        assert(Json.encode(value) == """{"wheel":{"dy":3}}""")
        assert(Json.decode[SCAPlain]("""{"SCAPlainKnown":{"x":1}}""") == Result.succeed(SCAPlainKnown(1)))
    }

    "untagged: the whole value when no other variant matches" in {
        val schema = Schema[SCAShape].untagged.catchAll("SCAOther")
        val side   = Structure.Value.Record(Chunk("side" -> Structure.Value.Integer(2)))
        assert(schema.decodeString[Json]("""{"side":2}""") == Result.succeed(SCAOther(side)))
        assert(schema.decodeString[Json]("""{"radius":1}""") == Result.succeed(SCACircle(1)))
        assert(schema.encodeString[Json](SCAOther(side)) == """{"side":2}""")
    }

    "tagOnly: the unknown string as the tag" in {
        assert(Json.decode[SCAColor]("\"blue\"") == Result.succeed(SCAOtherColor("blue")))
        assert(Json.decode[SCAColor]("\"SCARed\"") == Result.succeed(SCARed))
        val value: SCAColor = SCAOtherColor("blue")
        assert(Json.encode(value) == "\"blue\"")
    }

    "the builder and tagOnly compose in either order" in {
        val before = Schema[SCAPlainColor].catchAll("SCAPlainOther").tagOnly
        val after  = Schema[SCAPlainColor].tagOnly.catchAll("SCAPlainOther")
        assert(before.decodeString[Json]("\"blue\"") == Result.succeed(SCAPlainOther("blue")))
        assert(after.decodeString[Json]("\"blue\"") == Result.succeed(SCAPlainOther("blue")))
        assert(after.encodeString[Json](SCAPlainOther("blue")) == "\"blue\"")
    }

    "under tagOnly, a one-String-field variant that is not the catch-all fails at the first decode" in {
        Schema[SCAPlainColor].tagOnly.decodeString[Json]("\"SCAPlainRed\"") match
            case Result.Panic(e: TransformFailedException) => assert(e.getMessage.contains("SCAPlainOther"))
            case other                                     => fail(s"expected TransformFailedException, got $other")
    }

    "onFailure: a known tag whose variant fails to decode" - {

        val noX = Structure.Value.Record(Chunk("type" -> Structure.Value.Str("SCALenientClick")))

        "under a discriminator, gives the catch-all variant with the tag and the whole object" in {
            assert(Json.decode[SCALenient]("""{"type":"SCALenientClick"}""") ==
                Result.succeed(SCALenientUnknown("SCALenientClick", noX)))
        }

        "a known tag that decodes still gives its own variant, and an unknown tag the catch-all" in {
            assert(Json.decode[SCALenient]("""{"type":"SCALenientClick","x":1}""") == Result.succeed(SCALenientClick(1)))
            assert(Json.decode[SCALenient]("""{"type":"scroll","dy":3}""") == Result.succeed(SCALenientUnknown("scroll", scroll)))
        }

        "encode writes the object that was read" in {
            val value: SCALenient = SCALenientUnknown("SCALenientClick", noX)
            assert(Json.encode(value) == """{"type":"SCALenientClick"}""")
        }

        "a value of another type in a known variant also falls back" in {
            val wrongType = Structure.Value.Record(Chunk("type" -> Structure.Value.Str("SCALenientClick"), "x" -> Structure.Value.Str("a")))
            assert(Json.decode[SCALenient]("""{"type":"SCALenientClick","x":"a"}""") ==
                Result.succeed(SCALenientUnknown("SCALenientClick", wrongType)))
        }

        "off by default: the variant's failure surfaces" in {
            Json.decode[SCAEvent]("""{"type":"SCAClick"}""") match
                case Result.Failure(e: MissingFieldException) => assert(e.fieldName == "x")
                case other                                    => fail(s"expected MissingFieldException, got $other")
        }

        "a missing tag key is still a missing field" in {
            Json.decode[SCALenient]("""{"x":1}""") match
                case Result.Failure(e: MissingFieldException) => assert(e.fieldName == "type")
                case other                                    => fail(s"expected MissingFieldException, got $other")
        }

        "input the catch-all's own fields reject fails with the known variant's failure" in {
            Json.decode[SCAStrict]("""{"type":"SCAStrictClick"}""") match
                case Result.Failure(e: MissingFieldException) => assert(e.fieldName == "x")
                case other                                    => fail(s"expected MissingFieldException, got $other")
        }

        "adjacent, through the builder: the tag and the content value" in {
            val schema = Schema[SCAEvent].adjacent("t", "c").catchAll("SCAUnknown", onFailure = true)
            val y      = Structure.Value.Record(Chunk("y" -> Structure.Value.Integer(1)))
            assert(schema.decodeString[Json]("""{"t":"SCAClick","c":{"y":1}}""") == Result.succeed(SCAUnknown("SCAClick", y)))
            assert(schema.encodeString[Json](SCAUnknown("SCAClick", y)) == """{"t":"SCAClick","c":{"y":1}}""")
            assert(schema.decodeString[Json]("""{"t":"SCAClick","c":{"x":1}}""") == Result.succeed(SCAClick(1)))
        }

        "adjacent: a known tag with no content fails as it does without onFailure" in {
            val lenient = Schema[SCAEvent].adjacent("t", "c").catchAll("SCAUnknown", onFailure = true)
            val strict  = Schema[SCAEvent].adjacent("t", "c").catchAll("SCAUnknown")
            val input   = """{"t":"SCAClick"}"""
            (lenient.decodeString[Json](input), strict.decodeString[Json](input)) match
                case (Result.Failure(a: MissingFieldException), Result.Failure(b: MissingFieldException)) =>
                    assert((a.path, a.fieldName) == (b.path, b.fieldName))
                case other => fail(s"expected two MissingFieldExceptions, got $other")
            end match
        }

        "the wrapper object, through the builder: the key as the tag and its value" in {
            val schema = Schema[SCAPlain].catchAll("SCAPlainUnknown", onFailure = true)
            val xa     = Structure.Value.Record(Chunk("x" -> Structure.Value.Str("a")))
            assert(schema.decodeString[Json]("""{"SCAPlainKnown":{"x":"a"}}""") == Result.succeed(SCAPlainUnknown("SCAPlainKnown", xa)))
            assert(schema.decodeString[Json]("""{"SCAPlainKnown":{"x":1}}""") == Result.succeed(SCAPlainKnown(1)))
        }

        "the builder's default leaves it off" in {
            val schema = Schema[SCAPlain].catchAll("SCAPlainUnknown")
            assert(schema.decodeString[Json]("""{"SCAPlainKnown":{"x":"a"}}""").isFailure)
        }
    }

    "configuration errors" - {

        "an onFailure that is not a literal is a compile error" in {
            typeCheckFailure("kyo.Schema.derived[kyo.SCANonLiteral]")("must be a literal")
        }

        "an unknown variant name is a compile error" in {
            typeCheckFailure("kyo.Schema[kyo.SCAShape].catchAll(\"SCANope\")")("SCANope")
        }

        "a variant of another shape is a compile error" in {
            typeCheckFailure("kyo.Schema[kyo.SCAShape].catchAll(\"SCAWide\")")("SCAWide")
        }

        "an annotated variant of another shape is a compile error" in {
            typeCheckFailure("kyo.Schema.derived[kyo.SCABadAnnotated]")("SCABadWide")
        }

        "two annotated variants are a compile error" in {
            typeCheckFailure("kyo.Schema.derived[kyo.SCATwoCatchAlls]")("SCASecondUnknown")
        }

        "@tagOnly() with a one-String-field variant that is not @catchAll() is a compile error" in {
            typeCheckFailure("kyo.Schema.derived[kyo.SCATagOnlyWithoutCatchAll]")("SCAStray")
        }

        "a positional representation rejects a catch-all when the schema is built, in either order" in {
            val untagged = Schema[SCAShape].untagged.catchAll("SCAOther")
            assert(Result.catching[TransformFailedException](untagged.tupleTagged).isFailure)
            assert(Result.catching[TransformFailedException](Schema[SCAShape].tupleFlat.catchAll("SCAOther")).isFailure)
        }

        "a tagged representation needs a tag field, reported at the first decode or encode" in {
            val schema = Schema[SCAShape].catchAll("SCAOther").discriminator("type")
            schema.decodeString[Json]("""{"type":"x"}""") match
                case Result.Panic(ex) if ex.isInstanceOf[TransformFailedException] => assert(ex.getMessage.contains("SCAOther"))
                case other                                                         => fail(s"expected TransformFailedException, got $other")
            end match
            assert(Result.catching[TransformFailedException](schema.encodeString[Json](SCACircle(1))).isFailure)
        }
    }

    "a codec without a self-describing reader decodes a known variant of a sum with a catch-all" in {
        val bytes = Schema[SCAEvent].encode[Protobuf](SCAClick(1))
        assert(Schema[SCAEvent].decode[Protobuf](bytes) == Result.succeed(SCAClick(1)))
    }

    "a catch-all whose tag does not write as a name is refused under the wrapper object, not written under an empty key" in {
        val value: SCAWrapped = SCAWrappedOther("scroll", Structure.Value.Integer(3))
        Result.catching[TransformFailedException](Json.encode(value)) match
            case Result.Failure(_) => succeed("the tag that is no name is refused")
            case other             => fail(s"expected TransformFailedException, got $other")
    }

end SchemaCatchAllTest

object SCALengthTag extends Transformer.Full[String]:
    def write(value: String, writer: Codec.Writer): Unit = writer.int(value.length)
    def read(reader: Codec.Reader): String               = "x" * reader.int()

sealed trait SCAWrapped derives CanEqual, Schema
case class SCAWrappedKnown(x: Int)                                                                     extends SCAWrapped derives CanEqual
@catchAll() case class SCAWrappedOther(@transform(SCALengthTag) tag: String, payload: Structure.Value) extends SCAWrapped
    derives CanEqual

@discriminator("type") sealed trait SCAEvent derives CanEqual, Schema
case class SCAClick(x: Int)                                              extends SCAEvent derives CanEqual
@catchAll() case class SCAUnknown(tag: String, payload: Structure.Value) extends SCAEvent derives CanEqual

@adjacent("t", "c") sealed trait SCAEnvelope derives CanEqual
@rename("event") final case class SCAEventEnvelope(event: SCAEvent) extends SCAEnvelope
object SCAEventEnvelope:
    given Schema[SCAEventEnvelope] = summon[Schema[SCAEvent]].transform(SCAEventEnvelope(_))(_.event)
object SCAEnvelope:
    given Schema[SCAEnvelope] = Schema.derived[SCAEnvelope]

sealed trait SCAOuter derives CanEqual
final case class SCAOuterEnvelope(envelope: SCAEnvelope)         extends SCAOuter
@catchAll() final case class SCAOuterOther(raw: Structure.Value) extends SCAOuter
object SCAOuterEnvelope:
    given Schema[SCAOuterEnvelope] = summon[Schema[SCAEnvelope]].transform(SCAOuterEnvelope(_))(_.envelope)
object SCAOuter:
    given Schema[SCAOuter] = Schema.derived[SCAOuter].untagged

@discriminator("type") sealed trait SCALenient derives CanEqual, Schema
case class SCALenientClick(x: Int)                                                              extends SCALenient derives CanEqual
@catchAll(onFailure = true) case class SCALenientUnknown(tag: String, payload: Structure.Value) extends SCALenient derives CanEqual

case class SCAScroll(dy: Int) derives CanEqual, Schema

@discriminator("type") sealed trait SCAStrict derives CanEqual, Schema
case class SCAStrictClick(x: Int)                                                        extends SCAStrict derives CanEqual
@catchAll(onFailure = true) case class SCAStrictUnknown(tag: String, payload: SCAScroll) extends SCAStrict derives CanEqual

object SCAFlag:
    val on = true

sealed trait SCANonLiteral derives CanEqual
case class SCANonLiteralKnown(x: Int) extends SCANonLiteral derives CanEqual
@catchAll(onFailure = SCAFlag.on) case class SCANonLiteralUnknown(tag: String, payload: Structure.Value) extends SCANonLiteral
    derives CanEqual

sealed trait SCAPlain derives CanEqual, Schema
case class SCAPlainKnown(x: Int)                                              extends SCAPlain derives CanEqual
@catchAll() case class SCAPlainUnknown(tag: String, payload: Structure.Value) extends SCAPlain derives CanEqual

sealed trait SCAShape derives CanEqual, Schema
case class SCACircle(radius: Int)             extends SCAShape derives CanEqual
case class SCAOther(payload: Structure.Value) extends SCAShape derives CanEqual
case class SCAWide(a: Int, b: Int, c: Int)    extends SCAShape derives CanEqual

@tagOnly() sealed trait SCAColor derives CanEqual, Schema
case object SCARed                                  extends SCAColor
case object SCAGreen                                extends SCAColor
@catchAll() case class SCAOtherColor(value: String) extends SCAColor derives CanEqual

sealed trait SCAPlainColor derives CanEqual, Schema
case object SCAPlainRed                 extends SCAPlainColor
case class SCAPlainOther(value: String) extends SCAPlainColor derives CanEqual

sealed trait SCABadAnnotated derives CanEqual
case class SCABadKnown(x: Int)                            extends SCABadAnnotated derives CanEqual
@catchAll() case class SCABadWide(a: Int, b: Int, c: Int) extends SCABadAnnotated derives CanEqual

@tagOnly() sealed trait SCATagOnlyWithoutCatchAll derives CanEqual
case object SCAStrayRed            extends SCATagOnlyWithoutCatchAll
case class SCAStray(value: String) extends SCATagOnlyWithoutCatchAll derives CanEqual

sealed trait SCATwoCatchAlls derives CanEqual
@catchAll() case class SCAFirstUnknown(payload: Structure.Value)  extends SCATwoCatchAlls derives CanEqual
@catchAll() case class SCASecondUnknown(payload: Structure.Value) extends SCATwoCatchAlls derives CanEqual
