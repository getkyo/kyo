package kyo

import kyo.schema.*

class SchemaTagNumberTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def roundTripString[C <: Codec, A](s: Schema[A], value: A)(using C, Frame, kyo.test.AssertScope): Unit =
        val wire = s.encodeString[C](value)
        assert(s.decodeString[C](wire) == Result.succeed(value), s"string round-trip failed for $value via $wire")
    end roundTripString

    private def roundTripBytes[C <: Codec, A](s: Schema[A], value: A)(using C, Frame, kyo.test.AssertScope): Unit =
        val wire = s.encode[C](value)
        assert(s.decode[C](wire) == Result.succeed(value), s"byte round-trip failed for $value")
    end roundTripBytes

    private def obj(fields: (String, Structure.Value)*): Structure.Value = Structure.Value.Record(Chunk.from(fields))
    private def int(n: Long): Structure.Value                            = Structure.Value.Integer(n)

    "under a discriminator" - {

        "encode writes the tag as an integer" in {
            val value: STNComponent = STNButton("ok")
            assert(Json.encode(value) == """{"type":2,"label":"ok"}""")
        }

        "decode reads an integer tag" in {
            assert(Json.decode[STNComponent]("""{"label":"ok","type":2}""") == Result.succeed(STNButton("ok")))
            assert(Json.decode[STNComponent]("""{"type":3,"max":5}""") == Result.succeed(STNSlider(5)))
        }

        "a string tag is a type mismatch" in {
            Json.decode[STNPlain]("""{"type":"2","label":"ok"}""") match
                case Result.Failure(_: TypeMismatchException) => succeed
                case other                                    => fail(s"expected TypeMismatchException, got $other")
        }

        "an unknown number without a catch-all is an unknown variant" in {
            Json.decode[STNPlain]("""{"type":9}""") match
                case Result.Failure(e: UnknownVariantException) => assert(e.variantName == "9")
                case other                                      => fail(s"expected UnknownVariantException, got $other")
        }

        "the catch-all keeps an unknown number and the whole object" in {
            val raw = obj("type" -> int(7), "x" -> int(1))
            assert(Json.decode[STNComponent]("""{"type":7,"x":1}""") == Result.succeed(STNOther(7, raw)))
            val value: STNComponent = STNOther(7, raw)
            assert(Json.encode(value) == """{"type":7,"x":1}""")
        }

        "the catch-all passes a known number to its variant" in {
            assert(Json.decode[STNComponent]("""{"type":2,"label":"ok"}""") == Result.succeed(STNButton("ok")))
        }

        "a catch-all tag field may be a Long" in {
            val raw = obj("type" -> int(8))
            assert(Json.decode[STNLongTagged]("""{"type":8}""") == Result.succeed(STNLongOther(8L, raw)))
        }

        "a nested numbered sum decodes through the outer one" in {
            val value: STNComponent = STNRow(Chunk(STNButton("a"), STNSlider(2)))
            val wire                = Json.encode(value)
            assert(wire == """{"type":1,"items":[{"type":2,"label":"a"},{"type":3,"max":2}]}""")
            assert(Json.decode[STNComponent](wire) == Result.succeed(value))
        }

        "round-trips on every codec" in {
            val schema          = Schema[STNPlain]
            val value: STNPlain = STNPlainButton("ok")
            roundTripString[Json, STNPlain](schema, value)
            roundTripString[Yaml, STNPlain](schema, value)
            roundTripString[Ion, STNPlain](schema, value)
            roundTripBytes[MsgPack, STNPlain](schema, value)
            roundTripBytes[Bson, STNPlain](schema, value)
            roundTripBytes[IonBinary, STNPlain](schema, value)
            roundTripBytes[Protobuf, STNPlain](schema, value)
        }

        "the catch-all round-trips on every self-describing codec" in {
            val schema              = Schema[STNComponent]
            val known: STNComponent = STNButton("ok")
            roundTripString[Json, STNComponent](schema, known)
            roundTripString[Yaml, STNComponent](schema, known)
            roundTripString[Ion, STNComponent](schema, known)
            roundTripBytes[MsgPack, STNComponent](schema, known)
            roundTripBytes[Bson, STNComponent](schema, known)
            roundTripBytes[IonBinary, STNComponent](schema, known)
            val raw = obj("type" -> int(7), "x" -> int(1))
            assert(schema.decodeString[Yaml]("type: 7\nx: 1\n") == Result.succeed(STNOther(7, raw)))
            assert(schema.decodeString[Ion]("{type:7,x:1}") match
                case Result.Success(STNOther(7, _)) => true
                case _                              => false)
            assert(schema.decode[MsgPack](Schema[Structure.Value].encode[MsgPack](raw)) == Result.succeed(STNOther(7, raw)))
        }
    }

    "adjacent" - {

        "writes and reads the tag as an integer" in {
            val value: STNOp = STNHello(41250)
            assert(Json.encode(value) == """{"op":10,"d":{"interval":41250}}""")
            assert(Json.decode[STNOp]("""{"op":10,"d":{"interval":41250}}""") == Result.succeed(value))
            assert(Json.decode[STNOp]("""{"d":{"interval":1},"op":10}""") == Result.succeed(STNHello(1)))
        }

        "the catch-all keeps an unknown number and the content" in {
            val content = obj("a" -> int(1))
            assert(Json.decode[STNOp]("""{"op":99,"d":{"a":1}}""") == Result.succeed(STNUnknownOp(99, content)))
            val value: STNOp = STNUnknownOp(99, content)
            assert(Json.encode(value) == """{"op":99,"d":{"a":1}}""")
        }

        "round-trips on every codec" in {
            val schema       = Schema[STNOp]
            val value: STNOp = STNHello(5)
            roundTripString[Json, STNOp](schema, value)
            roundTripString[Yaml, STNOp](schema, value)
            roundTripString[Ion, STNOp](schema, value)
            roundTripBytes[MsgPack, STNOp](schema, value)
            roundTripBytes[Bson, STNOp](schema, value)
            roundTripBytes[IonBinary, STNOp](schema, value)
        }
    }

    "@tagOnly() with numbered variants and a Long catch-all" in {
        val value: STNOpcode = STNHeartbeat
        assert(Json.encode(value) == "1")
        assert(Json.decode[STNOpcode]("0") == Result.succeed(STNDispatch))
        assert(Json.decode[STNOpcode]("42") == Result.succeed(STNOtherOpcode(42L)))
        val other: STNOpcode = STNOtherOpcode(42L)
        assert(Json.encode(other) == "42")
    }

    "the variantNumbers builder" - {

        "numbers the variants under a discriminator" in {
            val schema = Schema[STNShape].discriminator("kind").variantNumbers("STNCircle" -> 1, "STNSquare" -> 2)
            assert(schema.encodeString[Json](STNSquare(3)) == """{"kind":2,"side":3}""")
            assert(schema.decodeString[Json]("""{"kind":1,"radius":4}""") == Result.succeed(STNCircle(4)))
        }

        "numbers the variants under tupleTagged and tupleFlat" in {
            val tuple = Schema[STNShape].tupleTagged.variantNumbers("STNCircle" -> 1, "STNSquare" -> 2)
            assert(tuple.encodeString[Json](STNSquare(3)) == """[2,{"side":3}]""")
            assert(tuple.decodeString[Json]("""[1,{"radius":4}]""") == Result.succeed(STNCircle(4)))
            val flat = Schema[STNShape].tupleFlat.variantNumbers("STNCircle" -> 1, "STNSquare" -> 2)
            assert(flat.encodeString[Json](STNSquare(3)) == """[2,3]""")
            assert(flat.decodeString[Json]("""[1,4]""") == Result.succeed(STNCircle(4)))
        }

        "numbers the variants under tagOnly, with a catch-all" in {
            val schema = Schema[STNLevel].tagOnly.variantNumbers("STNLow" -> 0, "STNHigh" -> 1)
            assert(schema.encodeString[Json](STNHigh) == "1")
            assert(schema.decodeString[Json]("0") == Result.succeed(STNLow))
            assert(schema.decodeString[Json]("5") == Result.succeed(STNOtherLevel(5)))
            assert(schema.encodeString[Json](STNOtherLevel(5)) == "5")
            schema.decodeString[Json]("\"STNLow\"") match
                case Result.Failure(_: TypeMismatchException) => succeed
                case other                                    => fail(s"expected TypeMismatchException, got $other")
        }

        "the builder's tagOnly rejects a one-Int variant that is not marked @catchAll()" in {
            typeCheckFailure("kyo.Schema[kyo.STNPlainLevel].tagOnly")("STNPlainOther")
        }

        "a variant without a number fails at the first encode or decode" in {
            val schema = Schema[STNShape].discriminator("kind").variantNumbers("STNCircle" -> 1)
            schema.decodeString[Json]("""{"kind":1,"radius":4}""") match
                case Result.Panic(ex) if ex.isInstanceOf[TransformFailedException] => assert(ex.getMessage.contains("STNSquare"))
                case other                                                         => fail(s"expected TransformFailedException, got $other")
            assert(Result.catching[TransformFailedException](schema.encodeString[Json](STNCircle(1))).isFailure)
        }

        "an unknown variant name is rejected at the call" in {
            assert(Result.catching[UnknownVariantException](Schema[STNShape].variantNumbers("STNNope" -> 1)).isFailure)
        }

        "two variants with one number are rejected at the call" in {
            Result.catching[VariantNameCollisionException](Schema[STNShape].variantNumbers("STNCircle" -> 1, "STNSquare" -> 1)) match
                case Result.Failure(e) => assert(e.getMessage.contains("STNCircle") && e.getMessage.contains("STNSquare"))
                case other             => fail(s"expected VariantNameCollisionException, got $other")
        }

        "mixing numbers with variant names is rejected at the call, in either order" in {
            val named = Schema[STNShape].variantNames("STNCircle" -> "circle")
            assert(Result.catching[TransformFailedException](named.variantNumbers("STNCircle" -> 1, "STNSquare" -> 2)).isFailure)
            val numbered = Schema[STNShape].variantNumbers("STNCircle" -> 1, "STNSquare" -> 2)
            assert(Result.catching[TransformFailedException](numbered.variantNames("STNCircle" -> "circle")).isFailure)
            assert(Result.catching[TransformFailedException](numbered.renameAllVariants(Schema.NameCase.SnakeCase)).isFailure)
            assert(Result.catching[TransformFailedException](numbered.variantAlias("1", "one")).isFailure)
        }

        "a String catch-all tag field under numbers fails at the first decode" in {
            val schema = Schema[STNStringCatchAll].discriminator("type").variantNumbers("STNStringKnown" -> 1)
            schema.decodeString[Json]("""{"type":5}""") match
                case Result.Panic(ex) if ex.isInstanceOf[TransformFailedException] => assert(ex.getMessage.contains("STNStringOther"))
                case other                                                         => fail(s"expected TransformFailedException, got $other")
        }
    }

    "configuration errors" - {

        "a variant without @tagNumber in a numbered sum is a compile error" in {
            typeCheckFailure("kyo.Schema.derived[kyo.STNMissing]")("STNMissingTwo")
        }

        "@tagNumber and @rename on one variant is a compile error" in {
            typeCheckFailure("kyo.Schema.derived[kyo.STNRenamed]")("STNRenamedOne")
        }

        "two variants with one number is a compile error" in {
            typeCheckFailure("kyo.Schema.derived[kyo.STNDuplicate]")("STNDuplicateTwo")
        }

        "@alias in a numbered sum is a compile error" in {
            typeCheckFailure("kyo.Schema.derived[kyo.STNAliased]")("STNAliasedOne")
        }

        "a String catch-all tag field in a numbered sum is a compile error" in {
            typeCheckFailure("kyo.Schema.derived[kyo.STNStringTagged]")("STNStringTaggedOther")
        }
    }

end SchemaTagNumberTest

@discriminator("type") sealed trait STNComponent derives CanEqual, Schema
@tagNumber(1) case class STNRow(items: Chunk[STNComponent])        extends STNComponent derives CanEqual
@tagNumber(2) case class STNButton(label: String)                  extends STNComponent derives CanEqual
@tagNumber(3) case class STNSlider(max: Int)                       extends STNComponent derives CanEqual
@catchAll() case class STNOther(`type`: Int, raw: Structure.Value) extends STNComponent derives CanEqual

@discriminator("type") sealed trait STNPlain derives CanEqual, Schema
@tagNumber(2) case class STNPlainButton(label: String) extends STNPlain derives CanEqual
@tagNumber(4) case object STNPlainSpacer               extends STNPlain

@discriminator("type") sealed trait STNLongTagged derives CanEqual, Schema
@tagNumber(1) case class STNLongKnown(x: Int)                           extends STNLongTagged derives CanEqual
@catchAll() case class STNLongOther(`type`: Long, raw: Structure.Value) extends STNLongTagged derives CanEqual

@adjacent("op", "d") sealed trait STNOp derives CanEqual, Schema
@tagNumber(10) case class STNHello(interval: Int)                extends STNOp derives CanEqual
@tagNumber(11) case class STNAck()                               extends STNOp derives CanEqual
@catchAll() case class STNUnknownOp(op: Int, d: Structure.Value) extends STNOp derives CanEqual

sealed trait STNShape derives CanEqual, Schema
case class STNCircle(radius: Int) extends STNShape derives CanEqual
case class STNSquare(side: Int)   extends STNShape derives CanEqual

sealed trait STNLevel derives CanEqual, Schema
case object STNLow                               extends STNLevel
case object STNHigh                              extends STNLevel
@catchAll() case class STNOtherLevel(value: Int) extends STNLevel derives CanEqual

sealed trait STNPlainLevel derives CanEqual, Schema
case object STNPlainLow              extends STNPlainLevel
case class STNPlainOther(value: Int) extends STNPlainLevel derives CanEqual

@tagOnly() sealed trait STNOpcode derives CanEqual, Schema
@tagNumber(0) case object STNDispatch           extends STNOpcode
@tagNumber(1) case object STNHeartbeat          extends STNOpcode
@catchAll() case class STNOtherOpcode(op: Long) extends STNOpcode derives CanEqual

sealed trait STNStringCatchAll derives CanEqual, Schema
case class STNStringKnown(x: Int)                                        extends STNStringCatchAll derives CanEqual
@catchAll() case class STNStringOther(tag: String, raw: Structure.Value) extends STNStringCatchAll derives CanEqual

@discriminator("type") sealed trait STNMissing derives CanEqual
@tagNumber(1) case class STNMissingOne(x: Int) extends STNMissing derives CanEqual
case class STNMissingTwo(x: Int)               extends STNMissing derives CanEqual

@discriminator("type") sealed trait STNRenamed derives CanEqual
@tagNumber(1) @rename("one") case class STNRenamedOne(x: Int) extends STNRenamed derives CanEqual

@discriminator("type") sealed trait STNDuplicate derives CanEqual
@tagNumber(1) case class STNDuplicateOne(x: Int) extends STNDuplicate derives CanEqual
@tagNumber(1) case class STNDuplicateTwo(x: Int) extends STNDuplicate derives CanEqual

@discriminator("type") sealed trait STNAliased derives CanEqual
@tagNumber(1) @alias("uno") case class STNAliasedOne(x: Int) extends STNAliased derives CanEqual

@discriminator("type") sealed trait STNStringTagged derives CanEqual
@tagNumber(1) case class STNStringTaggedKnown(x: Int)                          extends STNStringTagged derives CanEqual
@catchAll() case class STNStringTaggedOther(tag: String, raw: Structure.Value) extends STNStringTagged derives CanEqual
