package jsonbench

import kyo.Json
import kyo.Schema

case class Small(name: String, age: Int, active: Boolean) derives Schema
case class Nested(user: Small, score: Double, tags: List[String]) derives Schema
case class Wide(
    f1: String,
    f2: Int,
    f3: Boolean,
    f4: Double,
    f5: String,
    f6: Int,
    f7: Boolean,
    f8: Double,
    f9: String,
    f10: Int
) derives Schema
case class Collection(items: List[Small]) derives Schema

object JsonBenchData:

    // ---- kyo-schema codec ----
    // kyo.Json provides a given Json instance; use it directly via encodeString[Json] / decodeString[Json]

    // ---- jsoniter-scala codecs ----
    import com.github.plokhotnyuk.jsoniter_scala.core.*
    import com.github.plokhotnyuk.jsoniter_scala.macros.*
    private val jsoniterSmallCodec: JsonValueCodec[Small]           = JsonCodecMaker.make
    private val jsoniterNestedCodec: JsonValueCodec[Nested]         = JsonCodecMaker.make
    private val jsoniterWideCodec: JsonValueCodec[Wide]             = JsonCodecMaker.make
    private val jsoniterCollectionCodec: JsonValueCodec[Collection] = JsonCodecMaker.make

    // ---- circe codecs ----
    import io.circe.Decoder as CDecoder
    import io.circe.Encoder as CEncoder
    import io.circe.generic.semiauto.*
    given CEncoder[Small]      = deriveEncoder[Small]
    given CDecoder[Small]      = deriveDecoder[Small]
    given CEncoder[Nested]     = deriveEncoder[Nested]
    given CDecoder[Nested]     = deriveDecoder[Nested]
    given CEncoder[Wide]       = deriveEncoder[Wide]
    given CDecoder[Wide]       = deriveDecoder[Wide]
    given CEncoder[Collection] = deriveEncoder[Collection]
    given CDecoder[Collection] = deriveDecoder[Collection]

    // ---- zio-json codecs ----
    import zio.json.*
    given zio.json.JsonEncoder[Small]      = DeriveJsonEncoder.gen[Small]
    given zio.json.JsonDecoder[Small]      = DeriveJsonDecoder.gen[Small]
    given zio.json.JsonEncoder[Nested]     = DeriveJsonEncoder.gen[Nested]
    given zio.json.JsonDecoder[Nested]     = DeriveJsonDecoder.gen[Nested]
    given zio.json.JsonEncoder[Wide]       = DeriveJsonEncoder.gen[Wide]
    given zio.json.JsonDecoder[Wide]       = DeriveJsonDecoder.gen[Wide]
    given zio.json.JsonEncoder[Collection] = DeriveJsonEncoder.gen[Collection]
    given zio.json.JsonDecoder[Collection] = DeriveJsonDecoder.gen[Collection]

    // ---- zio-blocks codecs ----
    import zio.blocks.schema.Schema as ZSchema
    import zio.blocks.schema.json.Json as ZBJson
    import zio.blocks.schema.json.JsonEncoder as ZBEncoder
    given ZSchema[Small]      = ZSchema.derived
    given ZSchema[Nested]     = ZSchema.derived
    given ZSchema[Wide]       = ZSchema.derived
    given ZSchema[Collection] = ZSchema.derived

    // ---- Payload instances ----
    val small      = Small("Alice", 30, true)
    val nested     = Nested(Small("Bob", 25, false), 99.5, List("scala", "kyo", "json"))
    val wide       = Wide("a", 1, true, 1.1, "b", 2, false, 2.2, "c", 3)
    val collection = Collection(List.fill(100)(Small("Item", 42, true)))

    // ---- Pre-encoded JSON strings for decode benchmarks ----
    val smallJson: String      = summon[Schema[Small]].encodeString[Json](small)
    val nestedJson: String     = summon[Schema[Nested]].encodeString[Json](nested)
    val wideJson: String       = summon[Schema[Wide]].encodeString[Json](wide)
    val collectionJson: String = summon[Schema[Collection]].encodeString[Json](collection)

    // ---- kyo encode ----
    def kyoEncodeSmall(): String      = summon[Schema[Small]].encodeString[Json](small)
    def kyoEncodeNested(): String     = summon[Schema[Nested]].encodeString[Json](nested)
    def kyoEncodeWide(): String       = summon[Schema[Wide]].encodeString[Json](wide)
    def kyoEncodeCollection(): String = summon[Schema[Collection]].encodeString[Json](collection)

    // ---- kyo decode ----
    def kyoDecodeSmall(json: String): Small =
        summon[Schema[Small]].decodeString[Json](json).getOrThrow
    def kyoDecodeNested(json: String): Nested =
        summon[Schema[Nested]].decodeString[Json](json).getOrThrow
    def kyoDecodeWide(json: String): Wide =
        summon[Schema[Wide]].decodeString[Json](json).getOrThrow
    def kyoDecodeCollection(json: String): Collection =
        summon[Schema[Collection]].decodeString[Json](json).getOrThrow

    // ---- jsoniter encode ----
    def jsoniterEncodeSmall(): String      = writeToString(small)(using jsoniterSmallCodec)
    def jsoniterEncodeNested(): String     = writeToString(nested)(using jsoniterNestedCodec)
    def jsoniterEncodeWide(): String       = writeToString(wide)(using jsoniterWideCodec)
    def jsoniterEncodeCollection(): String = writeToString(collection)(using jsoniterCollectionCodec)

    // ---- jsoniter decode ----
    def jsoniterDecodeSmall(json: String): Small           = readFromString(json)(using jsoniterSmallCodec)
    def jsoniterDecodeNested(json: String): Nested         = readFromString(json)(using jsoniterNestedCodec)
    def jsoniterDecodeWide(json: String): Wide             = readFromString(json)(using jsoniterWideCodec)
    def jsoniterDecodeCollection(json: String): Collection = readFromString(json)(using jsoniterCollectionCodec)

    // ---- circe encode ----
    import io.circe.syntax.*
    def circeEncodeSmall(): String      = small.asJson.noSpaces
    def circeEncodeNested(): String     = nested.asJson.noSpaces
    def circeEncodeWide(): String       = wide.asJson.noSpaces
    def circeEncodeCollection(): String = collection.asJson.noSpaces

    // ---- circe decode ----
    import io.circe.parser.*
    def circeDecodeSmall(json: String): Small           = decode[Small](json).toTry.get
    def circeDecodeNested(json: String): Nested         = decode[Nested](json).toTry.get
    def circeDecodeWide(json: String): Wide             = decode[Wide](json).toTry.get
    def circeDecodeCollection(json: String): Collection = decode[Collection](json).toTry.get

    // ---- zio-json encode ----
    def zioEncodeSmall(): String      = small.toJson
    def zioEncodeNested(): String     = nested.toJson
    def zioEncodeWide(): String       = wide.toJson
    def zioEncodeCollection(): String = collection.toJson

    // ---- zio-json decode ----
    def zioDecodeSmall(json: String): Small           = json.fromJson[Small].fold(e => throw new RuntimeException(e), identity)
    def zioDecodeNested(json: String): Nested         = json.fromJson[Nested].fold(e => throw new RuntimeException(e), identity)
    def zioDecodeWide(json: String): Wide             = json.fromJson[Wide].fold(e => throw new RuntimeException(e), identity)
    def zioDecodeCollection(json: String): Collection = json.fromJson[Collection].fold(e => throw new RuntimeException(e), identity)

    // ---- zio-blocks encode ----
    // Encodes via Schema-derived JsonEncoder; .print uses compact (no-indent) output
    def zbEncodeSmall(): String      = ZBEncoder[Small].encode(small).print
    def zbEncodeNested(): String     = ZBEncoder[Nested].encode(nested).print
    def zbEncodeWide(): String       = ZBEncoder[Wide].encode(wide).print
    def zbEncodeCollection(): String = ZBEncoder[Collection].encode(collection).print

    // ---- zio-blocks decode ----
    def zbDecodeSmall(json: String): Small =
        ZBJson.parse(json).flatMap(_.as[Small]).fold(e => throw new RuntimeException(e.toString), identity)
    def zbDecodeNested(json: String): Nested =
        ZBJson.parse(json).flatMap(_.as[Nested]).fold(e => throw new RuntimeException(e.toString), identity)
    def zbDecodeWide(json: String): Wide =
        ZBJson.parse(json).flatMap(_.as[Wide]).fold(e => throw new RuntimeException(e.toString), identity)
    def zbDecodeCollection(json: String): Collection =
        ZBJson.parse(json).flatMap(_.as[Collection]).fold(e => throw new RuntimeException(e.toString), identity)

end JsonBenchData
