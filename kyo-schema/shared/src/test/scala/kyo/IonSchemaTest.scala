package kyo

import kyo.internal.IonReader

case class ISTPerson(name: String, age: Int) derives CanEqual, Schema
case class ISTProfile(name: String, nickname: Option[String], tags: List[Int], scores: Map[String, Int]) derives CanEqual, Schema
case class ISTUnique(tags: List[String]) derives CanEqual, Schema
case class ISTGpsFix(lat: Double, lon: Double) derives CanEqual, Schema
case class ISTReading(sensorId: String, value: Double) derives CanEqual, Schema
case class ISTNestedReport(name: String, location: Option[ISTGpsFix], readings: List[ISTReading]) derives CanEqual, Schema
case class ISTIntKeyedMap(counts: Map[Int, Int]) derives CanEqual, Schema

/** Records the length of every `write` call it receives, so a test can assert on the chunk
  * boundaries a streaming writer produced instead of only on the final byte content.
  */
final class RecordingOutputStream extends java.io.ByteArrayOutputStream:
    private var chunkSizes: Chunk[Int] = Chunk.empty

    def writeCount: Int = chunkSizes.size

    override def write(b: Int): Unit =
        chunkSizes = chunkSizes :+ 1
        super.write(b)
    end write

    override def write(b: Array[Byte], off: Int, len: Int): Unit =
        chunkSizes = chunkSizes :+ len
        super.write(b, off, len)
    end write
end RecordingOutputStream

class IonSchemaTest extends kyo.test.Test[Any]:

    "Ion Schema generation" - {

        "emits an ISL 2.0 document for a product schema" in {
            val expected =
                """$ion_schema_2_0
                  |
                  |type::{
                  |  name: ISTPerson,
                  |  type: struct,
                  |  fields: closed::{
                  |    name: { type: string, occurs: required },
                  |    age: { type: int, occurs: required },
                  |  },
                  |}
                  |""".stripMargin

            assert(Ion.ionSchemaString[ISTPerson]() == expected)
            assert(IonSchema.encode(Ion.ionSchema[ISTPerson]()) == expected)
        }

        "emits optional, collection, and string-keyed map constraints" in {
            val expected =
                """$ion_schema_2_0
                  |
                  |type::{
                  |  name: ISTProfile,
                  |  type: struct,
                  |  fields: closed::{
                  |    name: { type: string, occurs: required },
                  |    nickname: { type: $null_or::string },
                  |    tags: { type: list, element: int, occurs: required },
                  |    scores: { type: struct, field_names: string, element: int, occurs: required },
                  |  },
                  |}
                  |""".stripMargin

            assert(IonSchema.encode(IonSchema.from[ISTProfile]) == expected)
        }

        "fromSchema derives from an explicit runtime Schema value" in {
            val expected =
                """$ion_schema_2_0
                  |
                  |type::{
                  |  name: ISTPerson,
                  |  type: struct,
                  |  fields: closed::{
                  |    name: { type: string, occurs: required },
                  |    age: { type: int, occurs: required },
                  |  },
                  |}
                  |""".stripMargin

            val runtimeSchema: Schema[ISTPerson] = summon[Schema[ISTPerson]]
            assert(IonSchema.encode(IonSchema.fromSchema(runtimeSchema, IonSchema.Config.Default)) == expected)
        }

        "encodes ISL text to an output stream" in {
            val schema = IonSchema.from[ISTPerson]
            val out    = new java.io.ByteArrayOutputStream

            IonSchema.encodeTo(schema, out)

            assert(new String(out.toByteArray, java.nio.charset.StandardCharsets.UTF_8) == IonSchema.encode(schema))
            out.write('x')
            assert(out.toByteArray.last == 'x'.toByte)
        }

        "derives and encodes ISL text to an output stream" in {
            val out = new java.io.ByteArrayOutputStream

            IonSchema.encodeTo[ISTPerson](out)

            assert(new String(out.toByteArray, java.nio.charset.StandardCharsets.UTF_8) == IonSchema.encode[ISTPerson]())
            out.write('x')
            assert(out.toByteArray.last == 'x'.toByte)
        }

        "streams ISL text to an output stream across more than one write call" in {
            val fieldCount = 300
            val fields = Chunk.from((1 to fieldCount).map { i =>
                IonSchema.Field(f"wideField$i%04d", IonSchema.TypeExpr.Scalar("string"), required = true)
            })
            val schema = IonSchema(
                IonSchema.Version.V2_0,
                Chunk(IonSchema.TypeDefinition("WideRecord", IonSchema.TypeExpr.Struct(fields, closed = true)))
            )

            val expected = IonSchema.encode(schema)
            assert(expected.length > 8192)

            val recorder = new RecordingOutputStream
            IonSchema.encodeTo(schema, recorder)

            assert(new String(recorder.toByteArray, java.nio.charset.StandardCharsets.UTF_8) == expected)
            assert(recorder.writeCount > 1)
        }

        "maps kyo validation metadata to ISL constraints" in {
            val schema = Schema[ISTProfile]
                .checkMinLength(_.name)(2)
                .checkMaxLength(_.name)(12)
                .checkMinItems(_.tags)(1)
                .checkMaxItems(_.tags)(3)

            val encoded = Ion.ionSchemaString[ISTProfile]()(using schema)

            assert(encoded.contains(
                "name: { type: string, codepoint_length: range::[2, 12], occurs: required }"
            ))
            assert(encoded.contains(
                "tags: { type: list, element: int, container_length: range::[1, 3], occurs: required }"
            ))
        }

        "maps unique item metadata to ISL distinct elements" in {
            val encoded = Ion.ionSchemaString[ISTUnique]()(using Schema[ISTUnique].checkUniqueItems(_.tags))

            assert(encoded.contains("tags: { type: list, element: distinct::string, occurs: required }"))
        }

        "emits Ion type annotation constraints when configured" in {
            val encoded = Ion.ionSchemaString[IonSpecAnnotated](
                IonSchema.Config(annotationEmissionMode = Ion.AnnotationEmissionMode.Emit)
            )

            assert(encoded.contains("annotations: required::['kyo.IonSpecCustomTypeAnnotation']"))
            assert(encoded.contains(
                "value: { type: int, annotations: required::['kyo.IonSpecInt32Annotation', 'kyo.IonSpecDegreesAnnotation', 'kyo.IonSpecCelsiusAnnotation'], occurs: required }"
            ))
        }

        "emits sealed traits as ISL one_of alternatives for external wrappers" in {
            val expected =
                """$ion_schema_2_0
                  |
                  |type::{
                  |  name: MTShape,
                  |  one_of: [
                  |    { type: struct, fields: closed::{ MTCircle: { type: struct, fields: closed::{ radius: { type: float, occurs: required } }, occurs: required } } },
                  |    { type: struct, fields: closed::{ MTRectangle: { type: struct, fields: closed::{ width: { type: float, occurs: required }, height: { type: float, occurs: required } }, occurs: required } } },
                  |  ],
                  |}
                  |""".stripMargin

            val encoded = Ion.ionSchemaString[MTShape]()

            assert(encoded == expected)

            val marker         = s"${IonSchema.Version.V2_0.marker}\n\n"
            val typeDefinition = parseValue(encoded.substring(marker.length))
            val circleFields   = field(oneOfVariant(typeDefinition, "MTCircle"), "fields")

            assert(field(circleFields, "radius") == Structure.Value.Record(Chunk(
                "type"   -> Structure.Value.Str("float"),
                "occurs" -> Structure.Value.Str("required")
            )))
        }

        "emits a syntactically valid inline struct for a non-string-keyed map" in {
            val expected =
                """$ion_schema_2_0
                  |
                  |type::{
                  |  name: ISTIntKeyedMap,
                  |  type: struct,
                  |  fields: closed::{
                  |    counts: { type: list, element: { type: struct, fields: closed::{ key: { type: int, occurs: required }, value: { type: int, occurs: required } } }, occurs: required },
                  |  },
                  |}
                  |""".stripMargin

            assert(Ion.ionSchemaString[ISTIntKeyedMap]() == expected)
        }

        "emits sealed traits as ISL one_of alternatives for discriminator wrappers" in {
            given Schema[MTShape] = Schema[MTShape].discriminator("type")

            val encoded = Ion.ionSchemaString[MTShape]()

            assert(encoded.contains("type: { type: string, valid_values: [\"MTCircle\"], occurs: required }"))
            assert(encoded.contains("type: { type: string, valid_values: [\"MTRectangle\"], occurs: required }"))
            assert(encoded.contains("radius: { type: float, occurs: required }"))
            assert(encoded.contains("width: { type: float, occurs: required }"))
            assert(!encoded.contains("MTCircle: { type: struct"))
        }

        "emits a syntactically valid inline struct for a nested product under Option and List" in {
            val expected =
                """$ion_schema_2_0
                  |
                  |type::{
                  |  name: ISTNestedReport,
                  |  type: struct,
                  |  fields: closed::{
                  |    name: { type: string, occurs: required },
                  |    location: { type: $null_or::{ type: struct, fields: closed::{ lat: { type: float, occurs: required }, lon: { type: float, occurs: required } } } },
                  |    readings: { type: list, element: { type: struct, fields: closed::{ sensorId: { type: string, occurs: required }, value: { type: float, occurs: required } } }, occurs: required },
                  |  },
                  |}
                  |""".stripMargin

            assert(Ion.ionSchemaString[ISTNestedReport]() == expected)
        }

        "renders a nested inline struct that round-trips through the Ion reader" in {
            val encoded = Ion.ionSchemaString[ISTNestedReport]()
            val marker  = s"${IonSchema.Version.V2_0.marker}\n\n"

            assert(encoded.startsWith(marker))

            val typeDefinition = parseValue(encoded.substring(marker.length))
            val fields         = field(typeDefinition, "fields")
            val location       = field(fields, "location")
            val locationFields = field(field(location, "type"), "fields")
            val readings       = field(fields, "readings")
            val readingFields  = field(field(readings, "element"), "fields")

            val requiredFloat =
                Structure.Value.Record(Chunk("type" -> Structure.Value.Str("float"), "occurs" -> Structure.Value.Str("required")))
            val requiredString =
                Structure.Value.Record(Chunk("type" -> Structure.Value.Str("string"), "occurs" -> Structure.Value.Str("required")))

            assert(field(locationFields, "lat") == requiredFloat)
            assert(field(locationFields, "lon") == requiredFloat)
            assert(field(readingFields, "sensorId") == requiredString)
            assert(field(readingFields, "value") == requiredFloat)
        }
    }

    private def parseValue(text: String): Structure.Value =
        IonReader(text).readStructure()
    end parseValue

    private def field(value: Structure.Value, name: String)(using kyo.test.AssertScope): Structure.Value =
        value match
            case Structure.Value.Record(fields) =>
                fields.find(_._1 == name).map(_._2).getOrElse(fail(s"missing field '$name' in $fields"))
            case other =>
                fail(s"expected a struct while looking up '$name', got $other")
    end field

    /** Locates a `one_of` alternative's payload by its external-representation wire name. */
    private def oneOfVariant(root: Structure.Value, variantName: String)(using kyo.test.AssertScope): Structure.Value =
        field(root, "one_of") match
            case Structure.Value.Sequence(options) =>
                val matches = options.flatMap { option =>
                    field(option, "fields") match
                        case Structure.Value.Record(optionFields) =>
                            optionFields.collect { case (`variantName`, payload) => payload }
                        case other =>
                            fail(s"expected a struct one_of option, got $other")
                }
                matches.headOption.getOrElse(fail(s"missing one_of variant '$variantName' in $options"))
            case other =>
                fail(s"expected a one_of sequence, got $other")
    end oneOfVariant
end IonSchemaTest
