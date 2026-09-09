package kyo

import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import kyo.schema.*
import proteus.ProtobufCodec
import proteus.ProtobufDeriver
import scala.jdk.CollectionConverters.*

// Differential oracle for the Protobuf codec (issue #1747): kyo's bytes are checked against two
// independent implementations instead of against kyo's own reading of proto3.
//
//   - protobuf-java is the WIRE oracle: given a descriptor equivalent to what protoSchema
//     declares, are kyo's bytes parseable, and are protobuf-java's bytes decodable by kyo?
//     Descriptors are built programmatically (no protoc, no codegen), and fixtures pin kyo's
//     field numbers with @proto.fieldNumber so the descriptors match by construction.
//   - Proteus is the SCHEMA-MAPPING oracle: a code-first Scala 3 library with kyo's constraint
//     set (derive codecs from types, no codegen). It answers the questions .proto cannot
//     express: what happens to None, to an empty collection, to nested collections.
//
// Both oracles cover only canonical proto3 shapes; kyo's non-canonical extensions (nested
// collection records, empty-value map entries) are guarded by ProtobufCollectionsTest.

// --- kyo fixtures, field numbers pinned 1..n in declaration order ---

case class PDScalars(
    @proto.fieldNumber(1) i: Int,
    @proto.fieldNumber(2) l: Long,
    @proto.fieldNumber(3) d: Double,
    @proto.fieldNumber(4) f: Float,
    @proto.fieldNumber(5) b: Boolean,
    @proto.fieldNumber(6) s: String
) derives Schema, CanEqual

case class PDRepeated(
    @proto.fieldNumber(1) nums: List[Int],
    @proto.fieldNumber(2) names: List[String]
) derives Schema, CanEqual

case class PDItem(
    @proto.fieldNumber(1) n: Int,
    @proto.fieldNumber(2) tag: String
) derives Schema, CanEqual

case class PDNested(
    @proto.fieldNumber(1) item: PDItem,
    @proto.fieldNumber(2) items: List[PDItem]
) derives Schema, CanEqual

case class PDMaps(
    @proto.fieldNumber(1) byName: Map[String, Int],
    @proto.fieldNumber(2) byId: Map[Int, String]
) derives Schema, CanEqual

case class PDOpt(
    @proto.fieldNumber(1) o: Maybe[Int],
    @proto.fieldNumber(2) s: String
) derives Schema, CanEqual

// --- kyo fixtures mirrored against Proteus (sequential numbers match Proteus's own) ---

case class KDText(
    @proto.fieldNumber(1) a: String,
    @proto.fieldNumber(2) b: String
) derives Schema, CanEqual

case class KDNum(@proto.fieldNumber(1) n: Int) derives Schema, CanEqual

case class KDOptList(
    @proto.fieldNumber(1) o: Option[Int],
    @proto.fieldNumber(2) xs: List[Int]
) derives Schema, CanEqual

// --- Proteus fixtures (Proteus assigns sequential field numbers 1..n by declaration) ---

given ProtobufDeriver = ProtobufDeriver

case class PRText(a: String, b: String) derives ProtobufCodec
case class PRNum(n: Int) derives ProtobufCodec
case class PROptList(o: Option[Int], xs: List[Int]) derives ProtobufCodec

class ProtobufDifferentialTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // ===== protobuf-java wire oracle =====

    import FieldDescriptorProto.Label
    import FieldDescriptorProto.Type as PType

    private def scalarField(name: String, number: Int, tpe: PType): FieldDescriptorProto =
        FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(tpe).setLabel(Label.LABEL_OPTIONAL).build()

    private def repeatedField(name: String, number: Int, tpe: PType): FieldDescriptorProto =
        FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(tpe).setLabel(Label.LABEL_REPEATED).build()

    private def messageField(name: String, number: Int, typeName: String, repeated: Boolean = false): FieldDescriptorProto =
        FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(PType.TYPE_MESSAGE)
            .setLabel(if repeated then Label.LABEL_REPEATED else Label.LABEL_OPTIONAL)
            .setTypeName(typeName).build()

    private def mapEntryType(name: String, keyType: PType, valueType: PType): DescriptorProto =
        DescriptorProto.newBuilder().setName(name)
            .setOptions(MessageOptions.newBuilder().setMapEntry(true))
            .addField(scalarField("key", 1, keyType))
            .addField(scalarField("value", 2, valueType))
            .build()

    // One file holding every message the wire-oracle tests use; mirrors protoSchema's primitive
    // mapping (Int -> sint32, Long -> sint64, the rest direct).
    private val fileDescriptor: Descriptors.FileDescriptor =
        val scalars = DescriptorProto.newBuilder().setName("PDScalars")
            .addField(scalarField("i", 1, PType.TYPE_SINT32))
            .addField(scalarField("l", 2, PType.TYPE_SINT64))
            .addField(scalarField("d", 3, PType.TYPE_DOUBLE))
            .addField(scalarField("f", 4, PType.TYPE_FLOAT))
            .addField(scalarField("b", 5, PType.TYPE_BOOL))
            .addField(scalarField("s", 6, PType.TYPE_STRING))
            .build()
        val repeated = DescriptorProto.newBuilder().setName("PDRepeated")
            .addField(repeatedField("nums", 1, PType.TYPE_SINT32))
            .addField(repeatedField("names", 2, PType.TYPE_STRING))
            .build()
        val item = DescriptorProto.newBuilder().setName("PDItem")
            .addField(scalarField("n", 1, PType.TYPE_SINT32))
            .addField(scalarField("tag", 2, PType.TYPE_STRING))
            .build()
        val nested = DescriptorProto.newBuilder().setName("PDNested")
            .addField(messageField("item", 1, ".pd.PDItem"))
            .addField(messageField("items", 2, ".pd.PDItem", repeated = true))
            .build()
        val maps = DescriptorProto.newBuilder().setName("PDMaps")
            .addNestedType(mapEntryType("ByNameEntry", PType.TYPE_STRING, PType.TYPE_SINT32))
            .addNestedType(mapEntryType("ByIdEntry", PType.TYPE_SINT32, PType.TYPE_STRING))
            .addField(messageField("byName", 1, ".pd.PDMaps.ByNameEntry", repeated = true))
            .addField(messageField("byId", 2, ".pd.PDMaps.ByIdEntry", repeated = true))
            .build()
        val opt = DescriptorProto.newBuilder().setName("PDOpt")
            .addField(scalarField("o", 1, PType.TYPE_SINT32))
            .addField(scalarField("s", 2, PType.TYPE_STRING))
            .build()
        val file = FileDescriptorProto.newBuilder()
            .setName("pd.proto").setPackage("pd").setSyntax("proto3")
            .addMessageType(scalars).addMessageType(repeated).addMessageType(item)
            .addMessageType(nested).addMessageType(maps).addMessageType(opt)
            .build()
        Descriptors.FileDescriptor.buildFrom(file, Array.empty)
    end fileDescriptor

    private def descriptor(name: String): Descriptors.Descriptor =
        fileDescriptor.findMessageTypeByName(name)

    private def parseWithOracle(name: String, bytes: Span[Byte]): DynamicMessage =
        DynamicMessage.parseFrom(descriptor(name), bytes.toArray)

    private def fd(msg: Descriptors.Descriptor, name: String): Descriptors.FieldDescriptor =
        msg.findFieldByName(name)

    "protobuf-java wire oracle" - {

        "scalars: kyo bytes parse to the same values" in {
            val value  = PDScalars(-2, 300L, 1.5d, 2.5f, true, "hello")
            val parsed = parseWithOracle("PDScalars", Protobuf.encode(value))
            val d      = parsed.getDescriptorForType
            assert(parsed.getField(fd(d, "i")) == -2)
            assert(parsed.getField(fd(d, "l")) == 300L)
            assert(parsed.getField(fd(d, "d")) == 1.5d)
            assert(parsed.getField(fd(d, "f")) == 2.5f)
            assert(parsed.getField(fd(d, "b")) == true)
            assert(parsed.getField(fd(d, "s")) == "hello")
        }

        "scalars: oracle bytes decode to the same value in kyo" in {
            val d = descriptor("PDScalars")
            val oracleBytes = DynamicMessage.newBuilder(d)
                .setField(fd(d, "i"), -2)
                .setField(fd(d, "l"), 300L)
                .setField(fd(d, "d"), 1.5d)
                .setField(fd(d, "f"), 2.5f)
                .setField(fd(d, "b"), true)
                .setField(fd(d, "s"), "hello")
                .build().toByteArray
            val decoded = Protobuf.decode[PDScalars](Span.from(oracleBytes))
            assert(decoded == Result.succeed(PDScalars(-2, 300L, 1.5d, 2.5f, true, "hello")))
        }

        "scalars: oracle re-serialization of kyo bytes is byte-identical for non-default values" in {
            // kyo writes fields in declaration order, which equals field-number order here, and
            // protobuf-java serializes in field-number order, so the canonical forms coincide as
            // long as no value is a proto3 default (a canonical proto3 serializer omits defaults).
            val bytes = Protobuf.encode(PDScalars(-2, 300L, 1.5d, 2.5f, true, "hello"))
            val again = parseWithOracle("PDScalars", bytes).toByteArray
            assert(java.util.Arrays.equals(again, bytes.toArray))
        }

        "repeated: kyo packed sint32 run and per-element strings parse" in {
            val value  = PDRepeated(List(1, -2, 300), List("a", "b"))
            val parsed = parseWithOracle("PDRepeated", Protobuf.encode(value))
            val d      = parsed.getDescriptorForType
            assert(parsed.getField(fd(d, "nums")).asInstanceOf[java.util.List[?]].asScala.toList == List(1, -2, 300))
            assert(parsed.getField(fd(d, "names")).asInstanceOf[java.util.List[?]].asScala.toList == List("a", "b"))
        }

        "repeated: oracle bytes decode in kyo, including an empty repeated" in {
            val d       = descriptor("PDRepeated")
            val builder = DynamicMessage.newBuilder(d)
            builder.addRepeatedField(fd(d, "nums"), 1)
            builder.addRepeatedField(fd(d, "nums"), -2)
            builder.addRepeatedField(fd(d, "nums"), 300)
            // names left empty: proto3 encodes an empty repeated as absent
            val decoded = Protobuf.decode[PDRepeated](Span.from(builder.build().toByteArray))
            assert(decoded == Result.succeed(PDRepeated(List(1, -2, 300), List.empty)))
        }

        "nested: kyo bytes parse and oracle bytes decode" in {
            val value  = PDNested(PDItem(7, "x"), List(PDItem(1, "a"), PDItem(2, "b")))
            val parsed = parseWithOracle("PDNested", Protobuf.encode(value))
            val d      = parsed.getDescriptorForType
            val itemD  = descriptor("PDItem")
            val inner  = parsed.getField(fd(d, "item")).asInstanceOf[DynamicMessage]
            assert(inner.getField(fd(itemD, "n")) == 7)
            assert(inner.getField(fd(itemD, "tag")) == "x")
            val elems = parsed.getField(fd(d, "items")).asInstanceOf[java.util.List[?]].asScala.toList
            assert(elems.map(_.asInstanceOf[DynamicMessage].getField(fd(itemD, "tag"))) == List("a", "b"))

            val roundTripped = Protobuf.decode[PDNested](Span.from(parsed.toByteArray))
            assert(roundTripped == Result.succeed(value))
        }

        "maps: kyo entries parse as canonical MapEntry messages and round-trip through the oracle" in {
            val value       = PDMaps(Map("a" -> 1, "b" -> -2), Map(1 -> "one", 2 -> "two"))
            val parsed      = parseWithOracle("PDMaps", Protobuf.encode(value))
            val d           = parsed.getDescriptorForType
            val byNameEntry = d.findNestedTypeByName("ByNameEntry")
            val byName = parsed.getField(fd(d, "byName")).asInstanceOf[java.util.List[?]].asScala.toList
                .map(_.asInstanceOf[DynamicMessage])
                .map(m => m.getField(fd(byNameEntry, "key")) -> m.getField(fd(byNameEntry, "value")))
                .toMap
            assert(byName == Map("a" -> 1, "b" -> -2))
            val byIdEntry = d.findNestedTypeByName("ByIdEntry")
            val byId = parsed.getField(fd(d, "byId")).asInstanceOf[java.util.List[?]].asScala.toList
                .map(_.asInstanceOf[DynamicMessage])
                .map(m => m.getField(fd(byIdEntry, "key")) -> m.getField(fd(byIdEntry, "value")))
                .toMap
            assert(byId == Map(1 -> "one", 2 -> "two"))

            val roundTripped = Protobuf.decode[PDMaps](Span.from(parsed.toByteArray))
            assert(roundTripped == Result.succeed(value))
        }

        "scalars: canonical zero-omitted oracle bytes decode to proto3 defaults in kyo" in {
            // A canonical proto3 serializer omits default-valued scalar fields entirely.
            // protobuf-java drops every field of an all-defaults message, so the wire is empty;
            // kyo must decode absence as the proto3 default, not as a missing field.
            val d           = descriptor("PDScalars")
            val oracleBytes = DynamicMessage.newBuilder(d).build().toByteArray
            assert(oracleBytes.isEmpty)
            val decoded = Protobuf.decode[PDScalars](Span.from(oracleBytes))
            assert(decoded == Result.succeed(PDScalars(0, 0L, 0.0d, 0.0f, false, "")))
        }

        "optional: presence semantics match" in {
            val d = descriptor("PDOpt")
            // Present -> the oracle sees the field.
            val present = parseWithOracle("PDOpt", Protobuf.encode(PDOpt(Maybe(5), "x")))
            assert(present.getField(fd(d, "o")) == 5)
            // Absent -> kyo writes nothing for the field, so the oracle reads proto3's default.
            val absent = parseWithOracle("PDOpt", Protobuf.encode(PDOpt(Maybe.empty, "x")))
            assert(absent.getField(fd(d, "o")) == 0)
            // Oracle bytes without the field decode to Absent in kyo.
            val oracleBytes = DynamicMessage.newBuilder(d).setField(fd(d, "s"), "x").build().toByteArray
            assert(Protobuf.decode[PDOpt](Span.from(oracleBytes)) == Result.succeed(PDOpt(Maybe.empty, "x")))
        }
    }

    // ===== Proteus schema-mapping oracle =====

    "Proteus schema-mapping oracle" - {

        "aligned encodings are byte-identical and cross-decode (strings)" in {
            val kyoBytes     = Protobuf.encode(KDText("hello", "world"))
            val proteusBytes = ProtobufCodec[PRText].encode(PRText("hello", "world"))
            assert(java.util.Arrays.equals(kyoBytes.toArray, proteusBytes))
            assert(Protobuf.decode[KDText](Span.from(proteusBytes)) == Result.succeed(KDText("hello", "world")))
            assert(ProtobufCodec[PRText].decode(kyoBytes.toArray) == PRText("hello", "world"))
        }

        "integer mappings diverge as documented: kyo sint32 (zigzag), Proteus int32 (plain varint)" in {
            val kyoBytes     = Protobuf.encode(KDNum(1))
            val proteusBytes = ProtobufCodec[PRNum].encode(PRNum(1))
            // kyo: tag 0x08, zigzag(1) = 2. Proteus: tag 0x08, plain 1.
            assert(kyoBytes.toArray.toList == List[Byte](0x08, 0x02))
            assert(proteusBytes.toList == List[Byte](0x08, 0x01))
            assert(Protobuf.decode[KDNum](kyoBytes) == Result.succeed(KDNum(1)))
            assert(ProtobufCodec[PRNum].decode(proteusBytes) == PRNum(1))
        }

        "Scala-shape decisions agree: None and empty collections survive a round-trip" in {
            val kyoDecoded = Protobuf.decode[KDOptList](Protobuf.encode(KDOptList(None, List.empty)))
            assert(kyoDecoded == Result.succeed(KDOptList(None, List.empty)))
            val proteusCodec   = ProtobufCodec[PROptList]
            val proteusDecoded = proteusCodec.decode(proteusCodec.encode(PROptList(None, List.empty)))
            assert(proteusDecoded == PROptList(None, List.empty))
        }

        "Scala-shape decisions agree: Some and non-empty collections survive a round-trip" in {
            val kyoDecoded = Protobuf.decode[KDOptList](Protobuf.encode(KDOptList(Some(5), List(1, 2))))
            assert(kyoDecoded == Result.succeed(KDOptList(Some(5), List(1, 2))))
            val proteusCodec   = ProtobufCodec[PROptList]
            val proteusDecoded = proteusCodec.decode(proteusCodec.encode(PROptList(Some(5), List(1, 2))))
            assert(proteusDecoded == PROptList(Some(5), List(1, 2)))
        }
    }
end ProtobufDifferentialTest
