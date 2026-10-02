package kyo

import Record.*

class SchemaFieldTransformTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "field transform builders preserve Focused" in {
        val both = Schema[FieldTransformCart].transformField(_.quantity)((value, writer) => writer.string(value.toString))(reader =>
            reader.string().toInt
        )
        val writeOnly = Schema[FieldTransformCart].transformFieldWrite(_.quantity)((value, writer) => writer.string(value.toString))
        val readOnly  = Schema[FieldTransformCart].transformFieldRead(_.quantity)(reader => reader.string().toInt)

        val _: Schema[FieldTransformCart] { type Focused = "name" ~ String & "quantity" ~ Int } = both
        val _: Schema[FieldTransformCart] { type Focused = "name" ~ String & "quantity" ~ Int } = writeOnly
        val _: Schema[FieldTransformCart] { type Focused = "name" ~ String & "quantity" ~ Int } = readOnly

        assert(both.fieldTransforms.map(_._1) == Chunk("quantity"))
        assert(writeOnly.fieldTransforms.map(_._1) == Chunk("quantity"))
        assert(readOnly.fieldTransforms.map(_._1) == Chunk("quantity"))
        assert(both.hasTransforms)
        assert(both.hasReadTransforms)
    }

    "same field transform directions merge by source field" in {
        val writeThenRead = Schema[FieldTransformCart]
            .transformFieldWrite(_.quantity)((value, writer) => writer.string(value.toString))
            .transformFieldRead(_.quantity)(reader => reader.string().toInt)

        assert(writeThenRead.fieldTransforms.map(_._1) == Chunk("quantity"))
        val transform = writeThenRead.fieldTransforms(0)._2
        assert(transform.write.isDefined)
        assert(transform.read.isDefined)

        val readThenWrite = Schema[FieldTransformCart]
            .transformFieldRead(_.quantity)(reader => reader.string().toInt)
            .transformFieldWrite(_.quantity)((value, writer) => writer.string(value.toString))

        assert(readThenWrite.fieldTransforms.map(_._1) == Chunk("quantity"))
        val transform2 = readThenWrite.fieldTransforms(0)._2
        assert(transform2.write.isDefined)
        assert(transform2.read.isDefined)

        val both = readThenWrite.transformField(_.quantity)((value, writer) => writer.int(value))(_.int())

        assert(both.fieldTransforms.map(_._1) == Chunk("quantity"))
        val transform3 = both.fieldTransforms(0)._2
        assert(transform3.write.isDefined)
        assert(transform3.read.isDefined)
    }

    "transformField round-trip: Int field written and read as string" in {
        val schema = Schema[FieldTransformCart]
            .transformField(_.quantity)((v, writer) => writer.string(v.toString))(reader => reader.string().toInt)

        val value   = FieldTransformCart("hat", 42)
        val encoded = schema.encodeString[Json](value)
        // quantity must appear as a JSON string, not a number
        assert(encoded.contains("\"quantity\":\"42\""), s"expected quantity as string in: $encoded")
        assert(encoded.contains("\"name\":\"hat\""), s"expected name in: $encoded")

        val decoded = schema.decodeString[Json](encoded)
        assert(decoded == Result.Success(value), s"round-trip failed: $decoded")
    }

    "transformField round-trip: explicit string wire decodes back to Int" in {
        val schema = Schema[FieldTransformCart]
            .transformField(_.quantity)((v, writer) => writer.string(v.toString))(reader => reader.string().toInt)

        // Wire value where quantity is already a string
        val wire    = """{"name":"mug","quantity":"7"}"""
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.Success(FieldTransformCart("mug", 7)), s"decode from string wire failed: $decoded")
    }

    "transformFieldWrite-only: encode uses override, decode uses derived codec" in {
        val schema = Schema[FieldTransformCart]
            .transformFieldWrite(_.quantity)((v, writer) => writer.string(v.toString))

        val value   = FieldTransformCart("cup", 3)
        val encoded = schema.encodeString[Json](value)
        // Write override: quantity encoded as string on wire
        assert(encoded.contains("\"quantity\":\"3\""), s"expected quantity as string in: $encoded")

        // Decode uses the derived codec (expects a number), so a numeric wire decodes correctly
        val numericWire = """{"name":"cup","quantity":3}"""
        val decoded     = schema.decodeString[Json](numericWire)
        assert(decoded == Result.Success(value), s"decode from numeric wire failed: $decoded")
    }

    "transformFieldRead-only: encode uses derived codec, decode uses override" in {
        val schema = Schema[FieldTransformCart]
            .transformFieldRead(_.quantity)(reader => reader.string().toInt)

        val value   = FieldTransformCart("bowl", 9)
        val encoded = schema.encodeString[Json](value)
        // Encode uses derived codec: quantity is a number on wire
        assert(encoded.contains("\"quantity\":9"), s"expected quantity as number in: $encoded")

        // Decode uses override: accepts string wire representation
        val stringWire = """{"name":"bowl","quantity":"9"}"""
        val decoded    = schema.decodeString[Json](stringWire)
        assert(decoded == Result.Success(value), s"decode from string wire failed: $decoded")
    }

    "transformField composition with omit: transformed value is visible to the omit predicate" in {
        // Write override produces a string; omit fires when the override value is Str("0").
        val schema = Schema[FieldTransformCart]
            .transformField(_.quantity)((v, writer) => writer.string(v.toString))(reader => reader.string().toInt)
            .omit(_.quantity)
            .when {
                case Structure.Value.Str(s) => s == "0"
                case _                      => false
            }

        val omitted  = schema.encodeString[Json](FieldTransformCart("x", 0))
        val retained = schema.encodeString[Json](FieldTransformCart("x", 5))

        assert(!omitted.contains("\"quantity\""), s"quantity must be absent when transformed value is 0: $omitted")
        assert(retained.contains("\"quantity\":\"5\""), s"quantity must be present as string when non-zero: $retained")
    }

    "transformField composition with rename: encode applies both transform and rename" in {
        val schema = Schema[FieldTransformCart]
            .transformField(_.quantity)((v, writer) => writer.string(v.toString))(reader => reader.string().toInt)
            .rename(_.quantity, "qty")

        val value   = FieldTransformCart("plate", 12)
        val encoded = schema.encodeString[Json](value)
        // Renamed to "qty" and value is a string due to write override
        assert(encoded.contains("\"qty\":\"12\""), s"expected renamed qty as string in: $encoded")
        assert(!encoded.contains("\"quantity\""), s"old field name must not appear: $encoded")

        val decoded = schema.decodeString[Json](encoded)
        assert(decoded == Result.Success(value), s"round-trip with rename failed: $decoded")
    }

    "transformField Protobuf round-trip: exercises _translatedByWrapper on read override" in {
        // Encode quantity as a length-delimited string field on the Protobuf wire.
        // On decode the read override intercepts the field after fieldParse sets
        // _translatedByWrapper=true, which routes matchField through the string
        // comparison path rather than the numeric field-ID path.
        val schema = Schema[FieldTransformCart]
            .transformField(_.quantity)((v, writer) => writer.string(v.toString))(reader => reader.string().toInt)

        val value = FieldTransformCart("spoon", 99)

        val proto  = summon[Protobuf]
        val writer = proto.newWriter()
        schema.writeTo(value, writer)
        val bytes = writer.result()
        assert(bytes.nonEmpty, "encoded bytes must be non-empty")

        val reader  = proto.newReader(bytes)
        val decoded = Result.catching[DecodeException](schema.readFrom(reader))
        assert(decoded == Result.Success(value), s"Protobuf round-trip with field transform failed: $decoded")
    }

    "transformField on product field: write and read override for a nested case class" in {
        // Write override serializes a FieldTransformInner as a single string "x:y".
        // Read override parses "x:y" back into a FieldTransformInner.
        val writeInner: (FieldTransformInner, Codec.Writer) => Unit =
            (v, writer) => writer.string(s"${v.x}:${v.y}")
        val readInner: Codec.Reader => FieldTransformInner =
            reader =>
                val parts = reader.string().split(":")
                FieldTransformInner(parts(0).toInt, parts(1).toInt)
        val schema = Schema[FieldTransformOuter]
            .transformField(_.inner)(writeInner)(readInner)

        val value   = FieldTransformOuter("label", FieldTransformInner(3, 7))
        val encoded = schema.encodeString[Json](value)
        // The product field must appear as a flat string, not a nested object
        assert(encoded.contains("\"inner\":\"3:7\""), s"expected inner as encoded string in: $encoded")

        val decoded = schema.decodeString[Json](encoded)
        assert(decoded == Result.Success(value), s"product field round-trip failed: $decoded")
    }

    "transformField composes with default: present field uses the read override, absent field falls to the supplier" in {
        // A field can carry both a per-field transform (a custom wire form) and a decode-time
        // default supplier. When the field is present on the wire the read override decodes it;
        // when it is absent the supplier provides the value.
        val schema = Schema[FieldTransformCart]
            .transformField(_.quantity)((v, writer) => writer.string(v.toString))(reader => reader.string().toInt)
            .default(_.quantity)(999)

        // Present: quantity arrives in the override's string form, so the read override parses it.
        val present = schema.decodeString[Json]("""{"name":"spoon","quantity":"42"}""")
        assert(present == Result.Success(FieldTransformCart("spoon", 42)), s"present field must use the read override: $present")

        // Absent: quantity is missing from the wire, so the configured default supplier provides 999.
        val absent = schema.decodeString[Json]("""{"name":"spoon"}""")
        assert(absent == Result.Success(FieldTransformCart("spoon", 999)), s"absent field must fall to the default supplier: $absent")
    }

    "cross-feature: transformField write + read + omit when + default + rename" in {
        val schema = Schema[CrossTransformItem]
            .rename(_.tag, "t")
            .transformField(_.value)((v, w) => w.string(v.toString))(r => r.string().toInt)
            .omit(_.value).when(sv => sv == Structure.Value.Str("0"))
            .default(_.value)(99)

        // value=5: encoded as string "5" under source name "value"; tag renamed to "t"
        val e1 = schema.encodeString[Json](CrossTransformItem("x", 5))
        assert(e1.contains("\"t\":\"x\""), s"renamed tag must appear as t: $e1")
        assert(e1.contains("\"value\":\"5\""), s"value must be encoded as string: $e1")

        // value=0: the transform produces String("0"), which matches the omit predicate -> omitted
        val e2 = schema.encodeString[Json](CrossTransformItem("y", 0))
        assert(!e2.contains("\"value\""), s"value==0 (transformed to String(0)) must be omitted: $e2")
        assert(e2.contains("\"t\":\"y\""), s"renamed tag must still appear: $e2")

        // decode: tag arrives as "t", value arrives as string "7" -> read override parses to 7
        val r1 = schema.decodeString[Json]("""{"t":"z","value":"7"}""")
        assert(r1 == Result.Success(CrossTransformItem("z", 7)), s"read override must parse string to int: $r1")

        // absent value -> default supplier provides 99
        val r2 = schema.decodeString[Json]("""{"t":"w"}""")
        assert(r2 == Result.Success(CrossTransformItem("w", 99)), s"absent value must fall to supplier: $r2")

        // compile check: schema is a Schema[CrossTransformItem]
        val _: Schema[CrossTransformItem] = schema
    }

    "@omit(WhenDefault) on a transformed field compares the field's value, not its transformed wire form" in {
        assert(Json.encode(FieldTransformFlagged()) == "{}")
        assert(Json.encode(FieldTransformFlagged(urgent = true)) == """{"urgent":64}""")
        assert(Json.decode[FieldTransformFlagged]("{}") == Result.succeed(FieldTransformFlagged()))
        assert(Json.decode[FieldTransformFlagged]("""{"urgent":64}""") == Result.succeed(FieldTransformFlagged(urgent = true)))
    }

    "a field annotation that carries a closure survives each structural transform of a schema derived inside an object" - {
        import FieldTransformOps.*

        "flatten(_.field)" in {
            val value = FlattenOne("ab", FieldTransformInner(1, 2))
            assert(Json.encode(value) == """{"code":"AB","x":1,"y":2}""")
            assert(Json.decode[FlattenOne]("""{"code":"AB","x":1,"y":2}""") == Result.succeed(value))
        }

        "flatten" in {
            val value = FlattenAll("ab", FieldTransformInner(1, 2))
            assert(Json.encode(value) == """{"code":"AB","x":1,"y":2}""")
            assert(Json.decode[FlattenAll]("""{"code":"AB","x":1,"y":2}""") == Result.succeed(value))
        }

        "drop" in {
            assert(Json.encode(Dropped("ab", 1)) == """{"code":"AB"}""")
        }

        "rename" in {
            assert(Json.encode(Renamed("ab", 1)) == """{"code":"AB","count":1}""")
            assert(Json.decode[Renamed]("""{"code":"AB","count":1}""") == Result.succeed(Renamed("ab", 1)))
        }

        "select" in {
            assert(Json.encode(Selected("ab", 1)) == """{"code":"AB"}""")
        }

        "add" in {
            assert(Json.encode(Added("ab", 1)) == """{"code":"AB","n":1,"double":2}""")
        }

        "fold" in {
            assert(fieldNames(Folded("ab", 1)) == Chunk("code", "n"))
        }

        "an @omit(omit.When(...)) field beside a flattened record field" in {
            assert(Json.encode(OmitFlatten(-1, FieldTransformInner(1, 2))) == """{"x":1,"y":2}""")
            assert(Json.encode(OmitFlatten(3, FieldTransformInner(1, 2))) == """{"count":3,"x":1,"y":2}""")
        }

        "an @omit(omit.WhenDefault) field with a case-object default beside a flattened record field" in {
            assert(Json.encode(DefaultFlatten(inner = FieldTransformInner(1, 2))) == """{"x":1,"y":2}""")
            assert(Json.encode(DefaultFlatten(SA27bMode.Draft, FieldTransformInner(1, 2))) == """{"mode":"draft","x":1,"y":2}""")
            assert(Json.decode[DefaultFlatten]("""{"x":1,"y":2}""") == Result.succeed(DefaultFlatten(inner = FieldTransformInner(1, 2))))
        }
    }

end SchemaFieldTransformTest

object FieldTransformFlag extends kyo.schema.Transformer.Of[Boolean](
        Schema.intSchema.transform[Boolean](flags => (flags & 64) != 0)(set => if set then 64 else 0)
    )
case class FieldTransformFlagged(
    @kyo.schema.transform(FieldTransformFlag) @kyo.schema.omit(kyo.schema.omit.WhenDefault) urgent: Boolean = false
) derives CanEqual, Schema

object FieldTransformUpper extends kyo.schema.Transformer.Full[String]:
    def write(value: String, writer: Codec.Writer): Unit = writer.string(value.toUpperCase)
    def read(reader: Codec.Reader): String               = reader.string().toLowerCase

// Each schema is derived where it is transformed, inside an object, as a companion given or a method body: the shape in which a
// transform macro's receiver is a derivation tree rather than a reference to a given.
object FieldTransformOps:
    import kyo.schema.transform

    final case class FlattenOne(@transform(FieldTransformUpper) code: String, inner: FieldTransformInner) derives CanEqual
    object FlattenOne:
        given Schema[FlattenOne] = Schema[FlattenOne].flatten(_.inner)

    final case class FlattenAll(@transform(FieldTransformUpper) code: String, inner: FieldTransformInner) derives CanEqual
    object FlattenAll:
        given Schema[FlattenAll] = Schema[FlattenAll].flatten

    final case class Dropped(@transform(FieldTransformUpper) code: String, n: Int) derives CanEqual
    object Dropped:
        given Schema[Dropped] = Schema[Dropped].drop("n")

    final case class Renamed(@transform(FieldTransformUpper) code: String, n: Int) derives CanEqual
    object Renamed:
        given Schema[Renamed] = Schema[Renamed].rename("n", "count")

    final case class Selected(@transform(FieldTransformUpper) code: String, n: Int) derives CanEqual
    object Selected:
        given Schema[Selected] = Schema[Selected].select("code")

    final case class Added(@transform(FieldTransformUpper) code: String, n: Int) derives CanEqual
    object Added:
        given Schema[Added] = Schema[Added].add("double")(_.n * 2)

    object Negative extends kyo.schema.OmitPredicate:
        def test(value: Structure.Value): Boolean = value match
            case Structure.Value.Integer(n) => n < 0
            case _                          => false
    end Negative

    final case class OmitFlatten(@kyo.schema.omit(kyo.schema.omit.When(Negative)) count: Int, inner: FieldTransformInner)
    object OmitFlatten:
        given Schema[OmitFlatten] = Schema[OmitFlatten].flatten(_.inner)

    final case class DefaultFlatten(
        @kyo.schema.omit(kyo.schema.omit.WhenDefault) mode: SA27bMode = SA27bMode.Published,
        inner: FieldTransformInner
    ) derives CanEqual
    object DefaultFlatten:
        given Schema[DefaultFlatten] = Schema[DefaultFlatten].flatten(_.inner)

    final case class Folded(@transform(FieldTransformUpper) code: String, n: Int)

    def fieldNames(value: Folded): Chunk[String] =
        Schema[Folded].fold(value)(Chunk.empty[String])([N <: String, V] =>
            (acc: Chunk[String], field: Field[N, V], _: V) => acc :+ field.name
        )
end FieldTransformOps

case class FieldTransformCart(name: String, quantity: Int) derives CanEqual, Schema
case class FieldTransformInner(x: Int, y: Int) derives CanEqual, Schema
case class FieldTransformOuter(label: String, inner: FieldTransformInner) derives CanEqual, Schema

case class CrossTransformItem(tag: String, value: Int) derives CanEqual, Schema
