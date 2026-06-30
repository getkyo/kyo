package kyo

import kyo.Protobuf.ProtoSchema
import kyo.Schema.*
import kyo.internal.CodecMacro
import kyo.internal.ProtobufReader
import kyo.internal.ProtobufWriter
import kyo.schema.*
import scala.annotation.tailrec

class ProtobufTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // ===== writer/reader =====

    "writer/reader" - {

        "int round-trip" in {
            val w = new ProtobufWriter
            w.field("value", 0)
            w.int(42)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.int() == 42)
        }

        "negative int round-trip" in {
            val w = new ProtobufWriter
            w.field("value", 0)
            w.int(-7)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.int() == -7)
        }

        "long round-trip" in {
            val w = new ProtobufWriter
            w.field("value", 0)
            w.long(123456789L)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.long() == 123456789L)
        }

        "string round-trip" in {
            val w = new ProtobufWriter
            w.field("value", 0)
            w.string("hello world")
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.string() == "hello world")
        }

        "boolean round-trip" in {
            val w = new ProtobufWriter
            w.field("value", 0)
            w.boolean(true)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.boolean() == true)
        }

        "double round-trip" in {
            val w = new ProtobufWriter
            w.field("value", 0)
            w.double(3.14159)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.double() == 3.14159)
        }

        "float round-trip" in {
            val w = new ProtobufWriter
            w.field("value", 0)
            w.float(1.5f)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.float() == 1.5f)
        }

        "multiple fields round-trip" in {
            val w = new ProtobufWriter
            w.field("name", 0)
            w.string("Alice")
            w.field("age", 1)
            w.int(30)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            val s = r.string()
            val _ = r.field()
            val i = r.int()
            assert(s == "Alice")
            assert(i == 30)
        }

    }

    // ===== encode/decode =====

    "encode/decode" - {

        "protobuf encode simple" in {
            val person = MTPerson("Alice", 30)
            val bytes  = Protobuf.encode[MTPerson](person)
            assert(bytes.size > 0)
        }

        "protobuf encode different values" in {
            val person1 = MTPerson("Alice", 30)
            val person2 = MTPerson("Bob", 25)
            val bytes1  = Protobuf.encode[MTPerson](person1)
            val bytes2  = Protobuf.encode[MTPerson](person2)
            assert(bytes1.toArray.toSeq != bytes2.toArray.toSeq)
        }

        "protobuf encode deterministic" in {
            val person = MTPerson("Charlie", 40)
            val bytes1 = Protobuf.encode[MTPerson](person)
            val bytes2 = Protobuf.encode[MTPerson](person)
            assert(bytes1.toArray.toSeq == bytes2.toArray.toSeq)
        }

        "protobuf round-trip via Protobuf.decode" in {
            val person = MTPerson("Alice", 30)
            val bytes  = Protobuf.encode[MTPerson](person)
            val result = Protobuf.decode[MTPerson](bytes)
            assert(result == kyo.Result.Success(person))
        }

        "protobuf round-trip via writer/reader with field names" in {
            val schema = summon[Schema[MTPerson]]
            val person = MTPerson("Alice", 30)
            val w      = new ProtobufWriter
            schema.writeTo(person, w)

            // Use hash-based field IDs for the mapping
            val nameId = CodecMacro.fieldId("name")
            val ageId  = CodecMacro.fieldId("age")
            val r = new ProtobufReader(w.resultBytes)
                .withFieldNames(Map(nameId -> "name", ageId -> "age"))
            val decoded = schema.readFrom(r)
            assert(decoded == person)
        }

        "strict protobuf decode preserves normal numeric field matching" in {
            val schema = Schema[MTPerson].denyUnknownFields
            val person = MTPerson("Alice", 30)
            val bytes  = Protobuf.encode[MTPerson](person)
            val result = schema.decode[Protobuf](bytes)
            assert(result == Result.Success(person))
        }

        "strict protobuf decode rejects an unknown numeric field" in {
            val nameId = CodecMacro.fieldId("name")
            val ageId  = CodecMacro.fieldId("age")
            val unknownId = LazyList
                .from(1)
                .find(id => id != nameId && id != ageId)
                .get

            val w = new ProtobufWriter
            w.fieldBytes("name".getBytes(java.nio.charset.StandardCharsets.UTF_8), nameId)
            w.string("Alice")
            w.fieldBytes("age".getBytes(java.nio.charset.StandardCharsets.UTF_8), ageId)
            w.int(30)
            w.fieldBytes("unknown".getBytes(java.nio.charset.StandardCharsets.UTF_8), unknownId)
            w.int(7)

            val schema = Schema[MTPerson].denyUnknownFields
            val result = schema.decode[Protobuf](Span.from(w.resultBytes))
            result match
                case Result.Failure(ex: UnknownFieldException) =>
                    assert(ex.fieldName == unknownId.toString)
                case other =>
                    fail(s"Expected UnknownFieldException for protobuf field $unknownId, got $other")
            end match
        }

        "Protobuf.encode returns non-empty Span[Byte]" in {
            val person = MTPerson("Alice", 30)
            val bytes  = Protobuf.encode(person)
            assert(bytes.size > 0)
        }

        "Protobuf round-trip encode then decode" in {
            val person = MTPerson("Alice", 30)
            val bytes  = Protobuf.encode(person)
            val result = Protobuf.decode[MTPerson](bytes)
            assert(result == Result.Success(person))
        }

    }

    // ===== ProtoSchema =====

    "ProtoSchema" - {

        "ProtoSchema primitive types" in {
            val schema = ProtoSchema.from[MTPerson]
            assert(schema.contains("message MTPerson"))
            assert(schema.contains(s"string name = ${kyo.internal.CodecMacro.fieldId("name")};"))
            assert(schema.contains(s"sint32 age = ${kyo.internal.CodecMacro.fieldId("age")};"))
        }

        "ProtoSchema nested messages" in {
            val schema = ProtoSchema.from[MTTeam]
            assert(schema.contains("message MTTeam"))
            assert(schema.contains("message MTPersonAddr"))
            assert(schema.contains(s"repeated MTPersonAddr members = ${kyo.internal.CodecMacro.fieldId("members")};"))
        }

        "ProtoSchema optional fields" in {
            val schema = ProtoSchema.from[MTOptional]
            assert(schema.contains(s"optional string nickname = ${kyo.internal.CodecMacro.fieldId("nickname")};"))
        }

        "ProtoSchema sealed trait" in {
            val schema = ProtoSchema.from[MTShape]
            assert(schema.contains("message MTShape"))
            assert(schema.contains("oneof value"))
            assert(schema.contains("MTCircle"))
            assert(schema.contains("MTRectangle"))
        }

        "ProtoSchema syntax header" in {
            val schema = ProtoSchema.from[MTPerson]
            assert(schema.startsWith("syntax = \"proto3\";"))
        }

        "ProtoSchema map field emits map<K, V> syntax" in {
            val schema = ProtoSchema.from[MTProtoMapHolder]
            assert(schema.contains("map<string, sint32>"))
            assert(schema.contains("scores"))
        }

        "ProtoSchema top-level Primitive throws IllegalArgumentException" in {
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[Int], Map.empty)
            }
        }

        "ProtoSchema top-level Collection throws IllegalArgumentException" in {
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[List[Int]], Map.empty)
            }
        }

        "ProtoSchema top-level Optional throws IllegalArgumentException" in {
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[Option[Int]], Map.empty)
            }
        }

        "ProtoSchema top-level Mapping throws IllegalArgumentException" in {
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[Map[String, Int]], Map.empty)
            }
        }

        "ProtoSchema List[Option[A]] field throws IllegalArgumentException" in {
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.from[MTListOfOption]
            }
        }

        "ProtoSchema List[List[A]] field throws IllegalArgumentException" in {
            case class ListOfList(values: List[List[Int]]) derives CanEqual
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[ListOfList], Map.empty)
            }
        }

        "ProtoSchema List[Map[K, V]] field throws IllegalArgumentException" in {
            case class ListOfMap(values: List[Map[String, Int]]) derives CanEqual
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[ListOfMap], Map.empty)
            }
        }

        "ProtoSchema Option[Option[A]] field throws IllegalArgumentException" in {
            case class NestedOption(value: Option[Option[Int]]) derives CanEqual
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[NestedOption], Map.empty)
            }
        }

        "ProtoSchema Option[List[A]] field throws IllegalArgumentException" in {
            case class OptionOfList(value: Option[List[Int]]) derives CanEqual
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[OptionOfList], Map.empty)
            }
        }

        "ProtoSchema Option[Map[K, V]] field throws IllegalArgumentException" in {
            case class OptionOfMap(value: Option[Map[String, Int]]) derives CanEqual
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[OptionOfMap], Map.empty)
            }
        }

        "ProtoSchema Map[K, Option[V]] field throws IllegalArgumentException" in {
            case class MapWithOptionValue(value: Map[String, Option[Int]]) derives CanEqual
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[MapWithOptionValue], Map.empty)
            }
        }

        "ProtoSchema Map[K, List[V]] field throws IllegalArgumentException" in {
            case class MapWithListValue(value: Map[String, List[Int]]) derives CanEqual
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[MapWithListValue], Map.empty)
            }
        }

        "ProtoSchema Map[K, Map[K2, V]] field throws IllegalArgumentException" in {
            case class MapWithMapValue(value: Map[String, Map[String, Int]]) derives CanEqual
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[MapWithMapValue], Map.empty)
            }
        }

        "ProtoSchema Map[Option[K], V] field throws IllegalArgumentException" in {
            // Construct the Structure directly: Map with Optional key has no Schema instance
            // (only Map[String,V] and Dict[K,V] have Schema). Test directly via fromStructure.
            val optionalKeyMapping = Structure.Type.Mapping(
                "Map",
                Tag[Any],
                Structure.Type.Optional(
                    "Option",
                    Tag[Any],
                    Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]])
                ),
                Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
            )
            val productWithOptKey = Structure.Type.Product(
                "MapWithOptionKey",
                Tag[Any],
                Chunk.empty,
                Chunk(Structure.Field("value", optionalKeyMapping, Maybe.empty, Maybe.empty, false))
            )
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(productWithOptKey, Map.empty)
            }
        }

        "ProtoSchema Map[List[K], V] field throws IllegalArgumentException" in {
            // Construct the Structure directly: Map with Collection key has no Schema instance
            val collectionKeyMapping = Structure.Type.Mapping(
                "Map",
                Tag[Any],
                Structure.Type.Collection(
                    "List",
                    Tag[Any],
                    Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]])
                ),
                Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
            )
            val productWithListKey = Structure.Type.Product(
                "MapWithListKey",
                Tag[Any],
                Chunk.empty,
                Chunk(Structure.Field("value", collectionKeyMapping, Maybe.empty, Maybe.empty, false))
            )
            interceptThrown[IllegalArgumentException] {
                ProtoSchema.fromStructure(productWithListKey, Map.empty)
            }
        }

        "ProtoSchema Map[K, V] with non-default key types emits map<K, V>" in {
            // Construct the Structure directly: Map[Long, String] has no Schema (only Map[String,V] does)
            val longKeyMapping = Structure.Type.Mapping(
                "Map",
                Tag[Any],
                Structure.Type.Primitive(Structure.PrimitiveKind.Long, Tag[Long].asInstanceOf[Tag[Any]]),
                Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]])
            )
            val productWithLongKey = Structure.Type.Product(
                "MapIntToString",
                Tag[Any],
                Chunk.empty,
                Chunk(Structure.Field("values", longKeyMapping, Maybe.empty, Maybe.empty, false))
            )
            val schema = ProtoSchema.fromStructure(productWithLongKey, Map.empty)
            assert(schema.contains("map<sint64, string>"))
            assert(schema.contains("values"))
        }

    }

    // ===== recursive types =====

    "recursive types" - {

        "Protobuf encode/decode: tree round-trip" in {
            val tree = TreeNode(
                1,
                List(
                    TreeNode(2, scala.Nil),
                    TreeNode(3, List(TreeNode(4, scala.Nil)))
                )
            )
            // Use token-based round-trip since protobuf reader field-name map
            // doesn't handle nested recursive messages well
            val schema = summon[Schema[TreeNode]]
            val w      = new TestWriter
            schema.writeTo(tree, w)
            val r      = new TestReader(w.resultTokens)
            val result = schema.readFrom(r)
            assert(result == tree)
        }

        "Protobuf encode/decode: mutual recursion" in {
            val dept   = RTDepartment("Engineering", RTEmployee("Alice", Maybe.empty))
            val schema = summon[Schema[RTDepartment]]
            val w      = new TestWriter
            schema.writeTo(dept, w)
            val r      = new TestReader(w.resultTokens)
            val result = schema.readFrom(r)
            assert(result == dept)
        }

        "ProtoSchema from recursive case class" in {
            val proto = ProtoSchema.from[TreeNode]
            // Should contain "message TreeNode"
            assert(proto.contains("message TreeNode"))
            // Should contain a self-reference field for children
            assert(proto.contains("TreeNode"))
            // Should have the value field
            assert(proto.contains("value"))
            assert(proto.contains("children"))
        }

        "ProtoSchema from mutually recursive types" in {
            val proto = ProtoSchema.from[RTDepartment]
            // Should contain both message definitions
            assert(proto.contains("message RTDepartment"))
            assert(proto.contains("message RTEmployee"))
            assert(proto.contains("name"))
            assert(proto.contains("manager"))
        }

        "ProtoSchema from recursive sealed trait" in {
            val proto = ProtoSchema.from[Expr]
            // Should contain the Expr message with oneof
            assert(proto.contains("message Expr"))
            assert(proto.contains("oneof"))
            // Should contain variant messages
            assert(proto.contains("Lit"))
            assert(proto.contains("Add"))
            assert(proto.contains("Neg"))
        }

    }

    // ===== top-level sealed trait / enum decode =====
    //
    // These tests exercise Protobuf.decode[A] where A is itself a sealed trait
    // or enum (not wrapped in a case-class field). Before the matchField-based
    // sealed-trait read path, this flow failed with
    //   UnknownVariantException: Unknown variant '<hashed fieldId>'
    // because ProtobufReader.field() returned the fieldId as a string when no
    // name map was installed, and the sealed-trait reader dispatched by string
    // name.

    "top-level sealed trait / enum decode" - {

        "Protobuf.decode[SealedTrait] round-trips case class variant" in {
            val alpha: Protobuf1517Sealed = Protobuf1517Sealed.Alpha(42)
            val bytes                     = Protobuf.encode[Protobuf1517Sealed](alpha)
            assert(Protobuf.decode[Protobuf1517Sealed](bytes).getOrThrow == alpha)
        }

        "Protobuf.decode[SealedTrait] round-trips case object variant" in {
            val beta: Protobuf1517Sealed = Protobuf1517Sealed.Beta
            val bytes                    = Protobuf.encode[Protobuf1517Sealed](beta)
            assert(Protobuf.decode[Protobuf1517Sealed](bytes).getOrThrow == beta)
        }

        "Protobuf.decode[SealedTrait] discriminates between distinct variants" in {
            val alpha: Protobuf1517Sealed = Protobuf1517Sealed.Alpha(7)
            val beta: Protobuf1517Sealed  = Protobuf1517Sealed.Beta
            val gamma: Protobuf1517Sealed = Protobuf1517Sealed.Gamma("g")

            val ba = Protobuf.encode[Protobuf1517Sealed](alpha)
            val bb = Protobuf.encode[Protobuf1517Sealed](beta)
            val bg = Protobuf.encode[Protobuf1517Sealed](gamma)

            assert(Protobuf.decode[Protobuf1517Sealed](ba).getOrThrow == alpha)
            assert(Protobuf.decode[Protobuf1517Sealed](bb).getOrThrow == beta)
            assert(Protobuf.decode[Protobuf1517Sealed](bg).getOrThrow == gamma)
        }

        "Protobuf.decode[EnumType] round-trips no-arg enum case" in {
            val single: Protobuf1517Enum = Protobuf1517Enum.One
            val bytes                    = Protobuf.encode[Protobuf1517Enum](single)
            assert(Protobuf.decode[Protobuf1517Enum](bytes).getOrThrow == single)
        }

        "Protobuf.decode[EnumType] round-trips every no-arg enum case distinctly" in {
            val one: Protobuf1517Enum   = Protobuf1517Enum.One
            val two: Protobuf1517Enum   = Protobuf1517Enum.Two
            val three: Protobuf1517Enum = Protobuf1517Enum.Three

            val b1 = Protobuf.encode[Protobuf1517Enum](one)
            val b2 = Protobuf.encode[Protobuf1517Enum](two)
            val b3 = Protobuf.encode[Protobuf1517Enum](three)

            assert(Protobuf.decode[Protobuf1517Enum](b1).getOrThrow == one)
            assert(Protobuf.decode[Protobuf1517Enum](b2).getOrThrow == two)
            assert(Protobuf.decode[Protobuf1517Enum](b3).getOrThrow == three)
        }

        "Protobuf.decode[SealedTrait] round-trips nested sealed trait inside a case-class field" in {
            val holder = Protobuf1517Holder(Protobuf1517Sealed.Alpha(100))
            val bytes  = Protobuf.encode[Protobuf1517Holder](holder)
            assert(Protobuf.decode[Protobuf1517Holder](bytes).getOrThrow == holder)
        }
    }

    // ===== error paths =====
    //
    // Each throw site in Protobuf.scala / ProtobufReader.scala should have a
    // test that makes its failure observable. Tests prefer the public
    // Protobuf.encode/decode API where possible; low-level reader methods
    // (truncated buffers, range checks, parse errors on BigInt/BigDecimal)
    // are exercised via ProtobufReader directly because they are not
    // reachable through a round-trip with a valid writer.

    "error paths" - {

        // --- ProtobufReader: RangeException ---

        "ProtobufReader.short throws RangeException for out-of-range Int value" in {
            val w = new ProtobufWriter
            w.field("x", 0)
            w.int(Short.MaxValue.toInt + 1)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            intercept[RangeException](r.short())
            ()
        }

        "ProtobufReader.byte throws RangeException for out-of-range Int value" in {
            val w = new ProtobufWriter
            w.field("x", 0)
            w.int(Byte.MaxValue.toInt + 1)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            intercept[RangeException](r.byte())
            ()
        }

        "ProtobufReader.char throws RangeException for negative Int value" in {
            val w = new ProtobufWriter
            w.field("x", 0)
            w.int(-1)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            intercept[RangeException](r.char())
            ()
        }

        // --- ProtobufReader: TruncatedInputException ---

        "ProtobufReader.string throws TruncatedInputException when length prefix exceeds remaining data" in {
            // tag for field 1 LengthDelimited = (1 << 3) | 2 = 0x0a
            // varint length = 100, but only 1 payload byte follows
            val data = Array[Byte](0x0a.toByte, 100.toByte, 0x41.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.string())
            ()
        }

        "ProtobufReader.bytes throws TruncatedInputException when length prefix exceeds remaining data" in {
            val data = Array[Byte](0x0a.toByte, 50.toByte, 0x00.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.bytes())
            ()
        }

        "ProtobufReader.readVarint throws TruncatedInputException on unterminated varint" in {
            // every byte has continuation bit set, buffer ends before terminator
            val data = Array[Byte](0x80.toByte, 0x80.toByte, 0x80.toByte)
            val r    = new ProtobufReader(data)
            intercept[TruncatedInputException](r.field())
            ()
        }

        "ProtobufReader.readFixedLong throws TruncatedInputException when under 8 bytes remain" in {
            // tag for field 1 Fixed64 = (1 << 3) | 1 = 0x09, then only 3 payload bytes
            val data = Array[Byte](0x09.toByte, 0x01.toByte, 0x02.toByte, 0x03.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.double())
            ()
        }

        "ProtobufReader.readFixedInt throws TruncatedInputException when under 4 bytes remain" in {
            // tag for field 1 Fixed32 = (1 << 3) | 5 = 0x0d, then only 2 payload bytes
            val data = Array[Byte](0x0d.toByte, 0x01.toByte, 0x02.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.float())
            ()
        }

        "ProtobufReader.objectStart throws TruncatedInputException when nested message length exceeds remaining" in {
            // tag for field 1 LengthDelimited = 0x0a, nested length = 50, but no bytes follow
            val data = Array[Byte](0x0a.toByte, 50.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.objectStart())
            ()
        }

        "ProtobufReader.skip throws TruncatedInputException for fixed64 under 8 bytes remaining" in {
            val data = Array[Byte](0x09.toByte, 0x01.toByte, 0x02.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.skip())
            ()
        }

        "ProtobufReader.skip throws TruncatedInputException for length-delimited with over-large length" in {
            val data = Array[Byte](0x0a.toByte, 100.toByte, 0x00.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.skip())
            ()
        }

        "ProtobufReader.skip throws TruncatedInputException for fixed32 under 4 bytes remaining" in {
            val data = Array[Byte](0x0d.toByte, 0x01.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.skip())
            ()
        }

        // --- ProtobufReader: ParseException ---

        "ProtobufReader.bigInt throws ParseException on non-numeric payload" in {
            val w = new ProtobufWriter
            w.field("x", 0)
            w.string("not_a_number")
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            intercept[ParseException](r.bigInt())
            ()
        }

        "ProtobufReader.bigDecimal throws ParseException on non-numeric payload" in {
            val w = new ProtobufWriter
            w.field("x", 0)
            w.string("not_a_decimal")
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            intercept[ParseException](r.bigDecimal())
            ()
        }

        // --- Base Reader: LimitExceededException ---

        "ProtobufReader.checkCollectionSize throws LimitExceededException when count exceeds limit" in {
            val r = new ProtobufReader(Array.emptyByteArray)
            r.resetLimits(maxDepth = 512, maxCollectionSize = 10)
            intercept[LimitExceededException](r.checkCollectionSize(11))
            ()
        }

        "Protobuf.decode returns LimitExceededException when collection size exceeds limit" in {
            val holder     = MTCollectionHolder(List.fill(20)(1))
            given Protobuf = Protobuf(Protobuf.Config(maxCollectionSize = 5))
            val bytes      = Protobuf.encode(holder)
            val result     = Protobuf.decode[MTCollectionHolder](bytes)
            assert(result.failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        "Protobuf.decode returns LimitExceededException when nesting depth exceeds limit" in {
            // Build a deeply-nested TreeNode: TreeNode(1, List(TreeNode(2, List(TreeNode(3, ...)))))
            val deep = List.range(1, 6).foldRight(TreeNode(0, scala.Nil)) {
                case (v, acc) => TreeNode(v, List(acc))
            }
            val bytes  = Protobuf.encode(deep)
            val result = Protobuf.decode[TreeNode](bytes, 2, Json.DefaultMaxCollectionSize)
            assert(result.failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        "Protobuf.Config maxDepth limit applies to the no-arg decode" in {
            val deep = List.range(1, 6).foldRight(TreeNode(0, scala.Nil)) {
                case (v, acc) => TreeNode(v, List(acc))
            }
            given Protobuf = Protobuf(Protobuf.Config(maxDepth = 2))
            val bytes      = Protobuf.encode(deep)
            val result     = Protobuf.decode[TreeNode](bytes)
            assert(result.failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        // --- Protobuf.decode: MissingFieldException ---

        "Protobuf.decode returns MissingFieldException when required field is absent" in {
            val empty  = Span.empty[Byte]
            val result = Protobuf.decode[MTPerson](empty)
            assert(result.failure.exists(_.isInstanceOf[MissingFieldException]))
        }

        // --- Protobuf.decode: UnknownVariantException ---

        "Protobuf.decode returns UnknownVariantException for sealed trait with unknown variant tag" in {
            // Craft a protobuf message whose field number does not match any
            // variant hash of Protobuf1517Sealed. A single varint tag with
            // wire-type=Varint and fieldNumber set to a value that is not any
            // of Alpha/Beta/Gamma's CodecMacro.fieldId is enough.
            val alphaId = kyo.internal.CodecMacro.fieldId("Alpha")
            val betaId  = kyo.internal.CodecMacro.fieldId("Beta")
            val gammaId = kyo.internal.CodecMacro.fieldId("Gamma")
            val unknownId = LazyList
                .from(1)
                .find(id => id != alphaId && id != betaId && id != gammaId)
                .get

            val w = new ProtobufWriter
            // Write a tag for unknownId with varint wire type, then a value
            w.fieldBytes("unknown".getBytes("UTF-8"), unknownId)
            w.int(0)
            val bytes  = Span.from(w.resultBytes)
            val result = Protobuf.decode[Protobuf1517Sealed](bytes)
            assert(result.failure.exists(_.isInstanceOf[UnknownVariantException]))
        }

    }

    "Protobuf.protoSchema rejects Unit-typed fields" - {
        "throws IllegalArgumentException with 'Unit' in message" in {
            val ex = intercept[IllegalArgumentException](Protobuf.protoSchema[WithUnitField])
            assert(ex.getMessage.contains("Unit"))
        }
    }

    // ===== Protobuf rejects Open =====
    // Open raises SchemaNotSerializableException in Protobuf (NOT IllegalArgumentException).

    "Protobuf rejects Open" - {

        "ProtoSchema.fromStructure on Open throws SchemaNotSerializableException" in {
            val open = Structure.Type.Open(Tag[Structure.Value].asInstanceOf[Tag[Any]])
            val ex   = intercept[SchemaNotSerializableException](Protobuf.ProtoSchema.fromStructure(open, Map.empty))
            assert(ex.getMessage.contains("open-shape schemas"))
        }

        "ProtoSchema.fromStructure on Open throws SchemaNotSerializableException not IllegalArgumentException" in {
            val open = Structure.Type.Open(Tag[Structure.Value].asInstanceOf[Tag[Any]])
            val ex   = intercept[SchemaNotSerializableException](Protobuf.ProtoSchema.fromStructure(open, Map.empty))
            assert(!(ex: Throwable).isInstanceOf[IllegalArgumentException])
        }

    }

    "Protobuf.protoSchema requires Schema in scope" - {

        "fails to compile for a type with no Schema" in {
            typeCheckFailure("""
                class NoSchemaType2
                Protobuf.protoSchema[NoSchemaType2]
            """)("NoSchemaType2")
        }

    }

    "strict protobuf decode via Protobuf.decode rejects unknown field" in {
        val widerBytes =
            given Schema[CFPersonWide] = Schema[CFPersonWide]
            Protobuf.encode(CFPersonWide("Alice", 30, "surplus"))
        val strictSchema = Schema[CFPerson].denyUnknownFields
        val result =
            given Schema[CFPerson] = strictSchema
            Protobuf.decode[CFPerson](widerBytes)
        assert(result.isFailure, s"strict protobuf decode must reject unknown field: $result")
        assert(
            result.failure.exists(_.isInstanceOf[UnknownFieldException]),
            s"failure must be UnknownFieldException: $result"
        )
    }

    "transformField round-trip via Protobuf.encode and Protobuf.decode" in {
        val transformSchema = Schema[CFPerson]
            .transformField(_.age)((v, w) => w.int(v * 10))(r => r.int() / 10)
        val value = CFPerson("Bob", 3)
        val bytes =
            given Schema[CFPerson] = transformSchema
            Protobuf.encode(value)
        val result =
            given Schema[CFPerson] = transformSchema
            Protobuf.decode[CFPerson](bytes)
        assert(result == Result.Success(CFPerson("Bob", 3)), s"transform round-trip failed: $result")
        val nativeBytes =
            given Schema[CFPerson] = Schema[CFPerson]
            Protobuf.encode(CFPerson("Bob", 30))
        assert(bytes.toArray.toSeq == nativeBytes.toArray.toSeq, s"wire bytes must reflect write transform (age=30 on wire)")
    }

    "strict decode treats a unicode-digit wire key as an unknown name, not a numeric field id" in {
        // The wire token "١٢٣" (Arabic-Indic digits for 123) must NOT be parsed as field id 123 and
        // accidentally matched against a known field. It is a name, so strict decode rejects it as
        // unknown. Pins wireFieldId's strict-ASCII classification (a unicode-digit token returns -1).
        val schema  = Schema[CFPerson].denyUnknownFields
        val decoded = schema.decodeString[Json]("""{"name":"a","age":1,"١٢٣":"x"}""")
        assert(
            decoded.isFailure && decoded.failure.exists(_.isInstanceOf[UnknownFieldException]),
            s"a unicode-digit wire key must be rejected as an unknown field, not matched as id 123: $decoded"
        )
    }

    "decode default does not overwrite a present field on Protobuf" in {
        // A binary reader reports a field by its numeric field-id, so the default-injection
        // suppression must recognize the present field under that id and leave it untouched.
        val schema = Schema[CFDefaulted].default(_.score)(999)
        val bytes =
            given Schema[CFDefaulted] = schema
            Protobuf.encode(CFDefaulted("a", 7))
        val decoded =
            given Schema[CFDefaulted] = schema
            Protobuf.decode[CFDefaulted](bytes)
        assert(
            decoded == Result.Success(CFDefaulted("a", 7)),
            s"a present field must survive decode, not be overwritten by the configured supplier: $decoded"
        )
    }

    "all four field-customization features compose and round-trip through Json and Protobuf" in {
        // strict + transform + decode-default + whenDefault-omit on one schema, exercised through
        // BOTH the self-describing (Json) and binary (Protobuf numeric field-id) codecs. The binary
        // leg is the one that matters: a transform-read rewrites _translatedField on the numeric-key
        // path, and this proves it does not perturb the strict known-set or the default injection.
        val schema = Schema[CFAllFeatures]
            .denyUnknownFields
            .transformField(_.qty)((v, w) => w.int(v * 10))(r => r.int() / 10)
            .default(_.code)(999)
            .omit(_.note).whenDefault

        // note == "skip" equals its compile-time default, so it is omitted on encode and restored on
        // decode; qty is rewritten to qty*10 on the wire and divided back on read.
        val value = CFAllFeatures("widget", 5, 7, "skip")

        val json = schema.encodeString[Json](value)
        assert(!json.contains("\"note\""), s"note must be omitted when it equals its default: $json")
        assert(json.contains("\"qty\":50"), s"qty must be transformed to 50 on the wire: $json")
        assert(schema.decodeString[Json](json) == Result.Success(value), s"Json round-trip failed: ${schema.decodeString[Json](json)}")

        val pbBytes =
            given Schema[CFAllFeatures] = schema
            Protobuf.encode(value)
        val pbDecoded =
            given Schema[CFAllFeatures] = schema
            Protobuf.decode[CFAllFeatures](pbBytes)
        assert(pbDecoded == Result.Success(value), s"Protobuf round-trip failed: $pbDecoded")

        // An absent field falls to the configured supplier on both codecs.
        val jsonMissingCode = schema.decodeString[Json]("""{"tag":"widget","qty":50,"note":"present"}""")
        assert(
            jsonMissingCode == Result.Success(CFAllFeatures("widget", 5, 999, "present")),
            s"Json default supplier must fill absent code: $jsonMissingCode"
        )

        // strict decode rejects an unknown field on both codecs, despite the active read transform.
        val jsonUnknown = schema.decodeString[Json]("""{"tag":"w","qty":50,"code":7,"note":"x","extra":"surplus"}""")
        assert(
            jsonUnknown.isFailure && jsonUnknown.failure.exists(_.isInstanceOf[UnknownFieldException]),
            s"Json strict decode must reject the unknown field: $jsonUnknown"
        )

        val wideBytes =
            given Schema[CFAllFeaturesWide] = Schema[CFAllFeaturesWide]
            Protobuf.encode(CFAllFeaturesWide("w", 5, 7, "x", "surplus"))
        val pbUnknown =
            given Schema[CFAllFeatures] = schema
            Protobuf.decode[CFAllFeatures](wideBytes)
        assert(
            pbUnknown.isFailure && pbUnknown.failure.exists(_.isInstanceOf[UnknownFieldException]),
            s"Protobuf strict decode must reject the unknown field: $pbUnknown"
        )
    }

    // ===== #1716: repeated (List/Seq/...) and Map field round-trips =====

    "issue #1716 repeated and map fields" - {

        def pbRoundtrip[A](value: A)(using Schema[A], kyo.test.AssertScope): A =
            Protobuf.decode[A](Protobuf.encode(value)) match
                case Result.Success(a) => a
                case other             => fail(s"protobuf decode failed for $value: $other")

        "List[Int] field round-trips" in {
            val v = PB1716ListInt(1, List(1, 2, 3))
            assert(pbRoundtrip(v) == v)
        }

        "List[Int] field round-trips alongside a non-Int sibling" in {
            val v = PB1716NestedList(true, List(4, 5))
            assert(pbRoundtrip(v) == v)
        }

        "List of case classes (repeated embedded message) round-trips" in {
            val v = PB1716ListNested(7, List(PB1716NestedList(false, List(1)), PB1716NestedList(true, List(2, 3))))
            assert(pbRoundtrip(v) == v)
        }

        "single-element List round-trips" in {
            val v = PB1716ListInt(1, List(42))
            assert(pbRoundtrip(v) == v)
        }

        "Vector / Seq / Set / Chunk fields round-trip" in {
            val v = PB1716Collections(
                Vector(1, 2),
                Seq("a", "b"),
                Set(3, 4),
                Chunk(5, 6),
                Span.from(Array[Byte](7, 8)),
                Map("m" -> 9),
                Map(11  -> 12)
            )
            val decoded = pbRoundtrip(v)
            assert(decoded.v == v.v)
            assert(decoded.s.toSeq == v.s.toSeq)
            assert(decoded.set == v.set)
            assert(decoded.c == v.c)
            assert(decoded.sp.toArray.toSeq == v.sp.toArray.toSeq)
            assert(decoded.m == v.m)
            assert(decoded.n == v.n)
        }

        "absent repeated and map fields decode to empty values" in {
            val decoded = Protobuf.decode[PB1716Collections](Span.empty[Byte])
            assert(
                decoded == Result.Success(
                    PB1716Collections(
                        Vector.empty,
                        Seq.empty[String],
                        Set.empty,
                        Chunk.empty,
                        Span.empty[Byte],
                        Map.empty,
                        Map.empty
                    )
                )
            )
        }

        "schema-defined collection absent default is honored" in {
            val value = PB1716CustomCollection(PB1716Bag(Chunk(1, 2, 3)))
            assert(pbRoundtrip(value) == value)
            val decoded = Protobuf.decode[PB1716CustomCollection](Span.empty[Byte])
            assert(decoded == Result.Success(PB1716CustomCollection(PB1716Bag(Chunk.empty))))
        }

        "Map[String, Int] round-trips with keys preserved" in {
            val v = PB1716MapStr(9, Map("a" -> 1, "b" -> 2))
            assert(pbRoundtrip(v) == v)
        }

        "empty Map[String, Int] still round-trips" in {
            val v = PB1716MapStr(0, Map.empty)
            assert(pbRoundtrip(v) == v)
        }

        "Map[Int, Int] (non-String key) derives and round-trips" in {
            val v = PB1716MapInt(3, Map(10 -> 100, 20 -> 200))
            assert(pbRoundtrip(v) == v)
        }

        "Map[String, case class] round-trips" in {
            val v = PB1716MapMsg(Map("x" -> MTItem("pen", 1.5), "y" -> MTItem("ink", 2.0)))
            assert(pbRoundtrip(v) == v)
        }

        "cross-codec: Json round-trips the same shapes (no regression)" in {
            val a = PB1716ListNested(7, List(PB1716NestedList(false, List(1)), PB1716NestedList(true, List(2, 3))))
            assert(Schema[PB1716ListNested].decodeString[Json](Schema[PB1716ListNested].encodeString[Json](a)) == Result.Success(a))
            val b = PB1716MapStr(9, Map("a" -> 1, "b" -> 2))
            assert(Schema[PB1716MapStr].decodeString[Json](Schema[PB1716MapStr].encodeString[Json](b)) == Result.Success(b))
            val c = PB1716MapInt(3, Map(10 -> 100))
            assert(Schema[PB1716MapInt].decodeString[Json](Schema[PB1716MapInt].encodeString[Json](c)) == Result.Success(c))
        }

        "cross-codec: Yaml round-trips the same shapes (no regression)" in {
            val a = PB1716ListNested(7, List(PB1716NestedList(false, List(1)), PB1716NestedList(true, List(2, 3))))
            assert(Schema[PB1716ListNested].decodeString[Yaml](Schema[PB1716ListNested].encodeString[Yaml](a)) == Result.Success(a))
            val b = PB1716MapStr(9, Map("a" -> 1, "b" -> 2))
            assert(Schema[PB1716MapStr].decodeString[Yaml](Schema[PB1716MapStr].encodeString[Yaml](b)) == Result.Success(b))
            val c = PB1716MapInt(3, Map(10 -> 100))
            assert(Schema[PB1716MapInt].decodeString[Yaml](Schema[PB1716MapInt].encodeString[Yaml](c)) == Result.Success(c))
        }

        "cross-codec: Ion round-trips the same shapes (no regression)" in {
            val a = PB1716ListNested(7, List(PB1716NestedList(false, List(1)), PB1716NestedList(true, List(2, 3))))
            assert(Schema[PB1716ListNested].decodeString[Ion](Schema[PB1716ListNested].encodeString[Ion](a)) == Result.Success(a))
            val b = PB1716MapStr(9, Map("a" -> 1, "b" -> 2))
            assert(Schema[PB1716MapStr].decodeString[Ion](Schema[PB1716MapStr].encodeString[Ion](b)) == Result.Success(b))
            val c = PB1716MapInt(3, Map(10 -> 100))
            assert(Schema[PB1716MapInt].decodeString[Ion](Schema[PB1716MapInt].encodeString[Ion](c)) == Result.Success(c))
        }

        "cross-codec: MsgPack round-trips the same shapes (no regression)" in {
            val a = PB1716ListNested(7, List(PB1716NestedList(false, List(1)), PB1716NestedList(true, List(2, 3))))
            assert(Schema[PB1716ListNested].decode[MsgPack](Schema[PB1716ListNested].encode[MsgPack](a)) == Result.Success(a))
            val b = PB1716MapStr(9, Map("a" -> 1, "b" -> 2))
            assert(Schema[PB1716MapStr].decode[MsgPack](Schema[PB1716MapStr].encode[MsgPack](b)) == Result.Success(b))
            val c = PB1716MapInt(3, Map(10 -> 100))
            assert(Schema[PB1716MapInt].decode[MsgPack](Schema[PB1716MapInt].encode[MsgPack](c)) == Result.Success(c))
        }

        // Regression tests: empty string elements in a repeated field must not be silently dropped.
        // kyo encodes Seq("") as one LengthDelimited record of length 0; the reader must return it,
        // not treat it as a packed-scalar empty run.

        "Seq with one empty string element round-trips" in {
            val v = PB1716SeqStr(Seq(""))
            assert(pbRoundtrip(v) == v)
        }

        "Seq with empty string followed by non-empty string round-trips" in {
            val v = PB1716SeqStr(Seq("", "y"))
            assert(pbRoundtrip(v) == v)
        }

        "Seq with multiple empty strings mixed with non-empty strings round-trips" in {
            val v = PB1716SeqStr(Seq("", "a", "", "b"))
            assert(pbRoundtrip(v) == v)
        }

        "empty List[Int] field round-trips to empty" in {
            val v = PB1716ListInt(5, List.empty)
            assert(pbRoundtrip(v) == v)
        }

        "Map[Int, Int] protoSchema emits a valid map<sint32, sint32>" in {
            val schema = ProtoSchema.from[PB1716MapInt]
            assert(schema.contains("map<sint32, sint32> f"))
        }

        "opaque-type key reducing to a native scalar round-trips and is a native map key" in {
            val v = PB1716OpaqueKey(Map(PB1716Ids.UserId(1) -> "a", PB1716Ids.UserId(2) -> "b"))
            assert(pbRoundtrip(v) == v)
            val schema = ProtoSchema.from[PB1716OpaqueKey]
            assert(schema.contains("map<sint32, string> m"))
        }

        "value-class key (derives a message) round-trips via the entry-message form" in {
            given Protobuf = Protobuf(Protobuf.Config(conformance = Protobuf.Conformance.Permissive))
            val v          = PB1716VcKey(Map(Pid(1) -> "x", Pid(2) -> "y"))
            // pbRoundtrip captures the default given at its definition site; use inline encode/decode directly.
            assert(Protobuf.decode[PB1716VcKey](Protobuf.encode(v)) == Result.Success(v))
            // proto3 cannot use a message as a map key, so protoSchema describes a repeated entry message.
            val schema = ProtoSchema.from[PB1716VcKey]
            assert(schema.contains("repeated MEntry m") && schema.contains("message MEntry"))
        }

        "strict config rejects a non-proto3-native map key at encode" in {
            given Protobuf = Protobuf(Protobuf.Config(conformance = Protobuf.Conformance.Strict))
            val v          = PB1716VcKey(Map(Pid(1) -> "x"))
            val result     = scala.util.Try(Protobuf.encode(v))
            assert(result.isFailure && result.failed.get.isInstanceOf[SchemaNotSerializableException])
        }

        "strict config still allows native-keyed maps" in {
            given Protobuf = Protobuf(Protobuf.Config(conformance = Protobuf.Conformance.Strict))
            val v          = PB1716MapInt(3, Map(10 -> 100))
            assert(Protobuf.decode[PB1716MapInt](Protobuf.encode(v)) == Result.Success(v))
        }
    }

    "map decode depth-guard" - {

        "INV-PBC-07-nested-string-map-depth" in {
            // String-keyed maps go through mapStart/mapEnd, which honor the nesting-depth limit.
            // Depth levels: objectStart (case class) = 1, outer mapStart = 2, inner mapStart = 3 > maxDepth 2.
            val value  = PBStr2Map(Map("k" -> Map("inner" -> 1)))
            val bytes  = Protobuf.encode(value)
            val result = Protobuf.decode[PBStr2Map](bytes, 2, Json.DefaultMaxCollectionSize)
            assert(
                result.isFailure && result.failure.exists(_.isInstanceOf[LimitExceededException]),
                s"nested string-map exceeding maxDepth must be Result.Failure(LimitExceededException): $result"
            )
        }

        "INV-PBC-07-nested-nonstring-map-depth" in {
            // Non-string maps go through arrayStart + per-entry objectStart (arrayStart already calls checkDepth).
            // Depth levels: objectStart (case class) = 1, arrayStart for outer Map[Int,...] = 2,
            // objectStart for the first MapEntry = 3 > maxDepth 2.
            val value  = PBIntIntMap(Map(1 -> Map(2 -> 3)))
            val bytes  = Protobuf.encode(value)
            val result = Protobuf.decode[PBIntIntMap](bytes, 2, Json.DefaultMaxCollectionSize)
            assert(
                result.isFailure && result.failure.exists(_.isInstanceOf[LimitExceededException]),
                s"nested non-string-map exceeding maxDepth must be Result.Failure(LimitExceededException): $result"
            )
        }

        "INV-PBC-07-nested-list-depth" in {
            // Nested list: each level of List[List[...]] calls arrayStart which checks depth.
            // Depth levels: objectStart (case class) = 1, outer arrayStart = 2, inner arrayStart = 3 > maxDepth 2.
            val value  = PBListList(List(List(1)))
            val bytes  = Protobuf.encode(value)
            val result = Protobuf.decode[PBListList](bytes, 2, Json.DefaultMaxCollectionSize)
            assert(
                result.isFailure && result.failure.exists(_.isInstanceOf[LimitExceededException]),
                s"nested list exceeding maxDepth must be Result.Failure(LimitExceededException): $result"
            )
        }

        "INV-PBC-07-map-at-limit-decodes" in {
            // Same bytes as the exceeding test but maxDepth = 3: objectStart (depth 1) + outer mapStart (depth 2)
            // + inner mapStart (depth 3) is exactly at the limit. All three levels must succeed.
            val value  = PBStr2Map(Map("k" -> Map("inner" -> 1)))
            val bytes  = Protobuf.encode(value)
            val result = Protobuf.decode[PBStr2Map](bytes, 3, Json.DefaultMaxCollectionSize)
            assert(
                result == Result.Success(value),
                s"nested string-map at exactly maxDepth must be Result.Success: $result"
            )
        }

        "INV-PBC-11-hostile-frame-decode-totality" in {
            // Locked anti-case: 0x08 is a complete tag byte (field 1, Varint wire type) with no value body.
            // hasNextField consumes the tag (pos 0 -> 1 = data.length); any subsequent read or skip
            // of the missing value throws TruncatedInputException, which Result.catching intercepts.
            val truncated = Span.from(Array[Byte](0x08.toByte))
            // Second hostile input: tag 0x0a (field 1, LengthDelimited) followed by 0x80 (varint
            // continuation bit set, no terminator byte) - readVarint throws when reading the length.
            val overLarge = Span.from(Array[Byte](0x0a.toByte, 0x80.toByte))
            val inputs    = List(truncated, overLarge)
            for bytes <- inputs do
                val r1 = Protobuf.decode[MTCollectionHolder](bytes, Json.DefaultMaxDepth, Json.DefaultMaxCollectionSize)
                assert(
                    r1.isFailure && r1.failure.exists(_.isInstanceOf[DecodeException]),
                    s"list-path decode must return Result.Failure(_: DecodeException) for malformed input: $r1"
                )
                val r2 = Protobuf.decode[PB1716MapInt](bytes, Json.DefaultMaxDepth, Json.DefaultMaxCollectionSize)
                assert(
                    r2.isFailure && r2.failure.exists(_.isInstanceOf[DecodeException]),
                    s"map-path decode must return Result.Failure(_: DecodeException) for malformed input: $r2"
                )
            end for
        }

    }

    "packed repeated scalars" - {

        // Walk flat outer-message bytes and count records for (targetField, targetWireType).
        // Only visits the top-level tag/value pairs; does not descend into nested messages.
        def countRecs(bytes: Array[Byte], targetField: Int, targetWt: Int): Int =
            @tailrec def readVarnt(pos: Int, v: Long, sh: Int): (Long, Int) =
                if pos >= bytes.length then (v, pos)
                else
                    val b  = bytes(pos) & 0xff
                    val nv = v | ((b & 0x7f).toLong << sh)
                    if (b & 0x80) != 0 then readVarnt(pos + 1, nv, sh + 7)
                    else (nv, pos + 1)
            def skipVal(pos: Int, wt: Int): Int =
                wt match
                    case 0 => readVarnt(pos, 0L, 0)._2
                    case 1 => pos + 8
                    case 2 =>
                        val (len, p) = readVarnt(pos, 0L, 0)
                        p + len.toInt
                    case 5 => pos + 4
                    case _ => bytes.length
            @tailrec def walk(pos: Int, acc: Int): Int =
                if pos >= bytes.length then acc
                else
                    val (tagVal, p1) = readVarnt(pos, 0L, 0)
                    val fn           = (tagVal >>> 3).toInt
                    val wt           = (tagVal & 0x7).toInt
                    val hit          = if fn == targetField && wt == targetWt then 1 else 0
                    walk(skipVal(p1, wt), acc + hit)
            walk(0, 0)
        end countRecs

        "INV-PBC-04-scalar-packed-token-shape" in {
            val v       = PB1716ListInt(1, List(1, 2, 3))
            val encoded = Protobuf.encode(v).toArray
            val bField  = CodecMacro.fieldId("b")
            // Packed encoding: exactly one LengthDelimited record for field b.
            assert(
                countRecs(encoded, bField, 2) == 1,
                "field b must appear as exactly one LengthDelimited record (packed)"
            )
            // No per-element Varint records for field b.
            assert(
                countRecs(encoded, bField, 0) == 0,
                "packed form must emit no per-element Varint records for field b"
            )
            // The packed bytes decode correctly.
            assert(Protobuf.decode[PB1716ListInt](Span.from(encoded)) == Result.Success(v))
        }

        "INV-PBC-04-message-and-string-stay-per-element" in {
            // Repeated embedded messages must emit one LengthDelimited record per element,
            // not a single merged packed record.
            val msgs     = PB1716ListNested(1, List(PB1716NestedList(false, List(4)), PB1716NestedList(true, List(5, 6))))
            val msgBytes = Protobuf.encode(msgs).toArray
            val dField   = CodecMacro.fieldId("d")
            assert(
                countRecs(msgBytes, dField, 2) == 2,
                "two repeated message elements must produce two separate LengthDelimited records"
            )

            // Repeated strings must also emit one LengthDelimited record per element.
            val strs = PB1716Collections(
                Vector.empty,
                Seq("hello", "world"),
                Set.empty,
                Chunk.empty,
                Span.empty[Byte],
                Map.empty,
                Map.empty
            )
            val strBytes = Protobuf.encode(strs).toArray
            val sField   = CodecMacro.fieldId("s")
            assert(
                countRecs(strBytes, sField, 2) == 2,
                "two repeated string elements must produce two separate LengthDelimited records"
            )
        }

        "INV-PBC-03-dual-read-packed-and-unpacked" in {
            val aValue = 1
            val elems  = List(1, 2, 3)
            val holder = PB1716ListInt(aValue, elems)

            // Packed form produced by encode.
            val packed = Protobuf.encode(holder)

            // Hand-built unpacked form: one Varint tag per element, no arrayStart/arrayEnd.
            val uw = new ProtobufWriter
            uw.field("a", CodecMacro.fieldId("a"))
            uw.int(aValue)
            uw.field("b", CodecMacro.fieldId("b"))
            uw.int(1)
            uw.field("b", CodecMacro.fieldId("b"))
            uw.int(2)
            uw.field("b", CodecMacro.fieldId("b"))
            uw.int(3)
            val unpacked = Span.from(uw.resultBytes)

            // Both forms must decode to the identical value.
            assert(Protobuf.decode[PB1716ListInt](packed) == Result.Success(holder))
            assert(
                Protobuf.decode[PB1716ListInt](unpacked) == Result.Success(holder),
                "unpacked (one tag per element) form must decode identically to the packed form"
            )
        }

        "INV-PBC-03-mixed-producer" in {
            // Build packed bytes for elements [1, 2] via encode.
            val baseBytes = Protobuf.encode(PB1716ListInt(7, List(1, 2))).toArray

            // Append unpacked elements [3, 4] for the same field using the raw writer outside
            // an array context (inArray=false -> one Varint tag per element).
            val xw = new ProtobufWriter
            xw.field("b", CodecMacro.fieldId("b"))
            xw.int(3)
            xw.field("b", CodecMacro.fieldId("b"))
            xw.int(4)
            val extra = xw.resultBytes

            val mixed  = Span.from(baseBytes ++ extra)
            val result = Protobuf.decode[PB1716ListInt](mixed)
            assert(
                result == Result.Success(PB1716ListInt(7, List(1, 2, 3, 4))),
                s"mixed-producer (packed run then unpacked elements) must decode all elements in order: $result"
            )
        }

        "INV-PBC-09-packed-order-preserved" in {
            val v = PB1716ListInt(0, List(5, 4, 3, 2, 1))
            assert(
                Protobuf.decode[PB1716ListInt](Protobuf.encode(v)) == Result.Success(v),
                "packed decode must preserve write order (descending)"
            )
        }

        "INV-PBC-08-empty-collection-and-map-absent" in {
            val bField = CodecMacro.fieldId("b")

            // Empty List must produce no wire record for the repeated field.
            val emptyList      = PB1716ListInt(7, Nil)
            val emptyListBytes = Protobuf.encode(emptyList).toArray
            assert(
                countRecs(emptyListBytes, bField, 2) == 0 && countRecs(emptyListBytes, bField, 0) == 0,
                "encoding an empty List must produce no record for the repeated field"
            )
            // Decodes back to the original (absent field -> empty List).
            assert(Protobuf.decode[PB1716ListInt](Span.from(emptyListBytes)) == Result.Success(emptyList))

            // Empty Map must produce no wire record for the map field.
            val emptyMap      = PB1716MapStr(5, Map.empty)
            val emptyMapBytes = Protobuf.encode(emptyMap).toArray
            val fField        = CodecMacro.fieldId("f")
            assert(
                countRecs(emptyMapBytes, fField, 2) == 0,
                "encoding an empty Map must produce no record for the map field"
            )
            assert(Protobuf.decode[PB1716MapStr](Span.from(emptyMapBytes)) == Result.Success(emptyMap))
        }

        "INV-PBC-04-all-scalar-collections-roundtrip-packed" in {
            val v = PB1716Collections(
                Vector(10, 20, 30),
                Seq("x", "y"),
                Set(1, 2, 3),
                Chunk(4, 5, 6),
                Span.from(Array[Byte](7, 8, 9)),
                Map("a" -> 1, "b"   -> 2),
                Map(100 -> 200, 300 -> 400)
            )
            Protobuf.decode[PB1716Collections](Protobuf.encode(v)) match
                case Result.Success(d) =>
                    assert(d.v == v.v)
                    assert(d.s.toSeq == v.s.toSeq)
                    // Set is unordered; element equality suffices.
                    assert(d.set == v.set)
                    assert(d.c == v.c)
                    assert(d.sp.toArray.toSeq == v.sp.toArray.toSeq)
                    assert(d.m == v.m)
                    assert(d.n == v.n)
                case other => fail(s"PB1716Collections encode/decode failed: $other")
            end match
        }

    }

    // ===== fieldNumberAudit =====

    "fieldNumberAudit" - {

        "INV-PBC-06-number-equals-wire-formula" in {
            val rows = Protobuf.fieldNumberAudit[PBAuditPerson]
            assert(rows.nonEmpty)
            rows.foreach { row =>
                assert(
                    row.number == CodecMacro.fieldId(row.name),
                    s"row '${row.path}': number ${row.number} != fieldId('${row.name}') ${CodecMacro.fieldId(row.name)}"
                )
            }
        }

        "INV-PBC-06-dotted-path-recursion" in {
            val rows  = Protobuf.fieldNumberAudit[PBAuditPerson]
            val paths = rows.map(_.path).toSet
            assert(paths.contains("name"), s"expected path 'name' in $paths")
            assert(paths.contains("inner"), s"expected path 'inner' in $paths")
            assert(paths.contains("inner.id"), s"expected path 'inner.id' in $paths")
            val innerIdRow = rows.find(_.path == "inner.id").get
            assert(innerIdRow.name == "id")
        }

        "INV-PBC-06-pinned-flag" in {
            val pBPinned: Schema[PBAuditPerson] = Schema[PBAuditPerson].fieldId(_.name)(7)
            val rows                            = Protobuf.fieldNumberAudit[PBAuditPerson](using pBPinned)
            val nameRow                         = rows.find(_.name == "name").get
            assert(nameRow.pinned == true, s"expected pinned=true for 'name', got ${nameRow.pinned}")
            assert(nameRow.number == 7, s"expected number=7 for 'name', got ${nameRow.number}")
            rows.filter(_.name != "name").foreach { row =>
                assert(row.pinned == false, s"expected pinned=false for '${row.path}', got ${row.pinned}")
            }
        }

        "INV-PBC-12-reserved-flag-and-control" in {
            val rows    = Protobuf.fieldNumberAudit[PBReserved]
            val inBand  = rows.find(_.name == "bAt").get
            val control = rows.find(_.name == "ctrl").get
            assert(inBand.inReservedRange == true, s"'bAt' (number=${inBand.number}) should be in reserved range 19000-19999")
            assert(control.inReservedRange == false, s"'ctrl' (number=${control.number}) should NOT be in reserved range 19000-19999")
        }

        "INV-PBC-06-traversal-shape-and-type-reuse" in {
            val rows  = Protobuf.fieldNumberAudit[PBAuditReuse]
            val paths = rows.map(_.path)
            // PBAuditInner is reused: its fields appear at both the list-element path and the map-value path.
            assert(paths.contains("list"), s"expected 'list' row in $paths")
            assert(paths.contains("map"), s"expected 'map' row in $paths")
            assert(paths.contains("ints"), s"expected 'ints' row in $paths")
            assert(paths.contains("list.id"), s"expected 'list.id' row (PBAuditInner fields at list occurrence) in $paths")
            assert(paths.contains("map.value.id"), s"expected 'map.value.id' row (PBAuditInner fields at map-value occurrence) in $paths")
            // ints: List[Int] - Int is a primitive, so no per-element row.
            assert(!paths.exists(_.startsWith("ints.")), s"unexpected sub-path of 'ints' (Int is primitive) in $paths")
        }

        "INV-PBC-06-recursive-type-terminates" in {
            val rows  = Protobuf.fieldNumberAudit[PBAuditTree]
            val paths = rows.map(_.path)
            // The audit must terminate and emit exactly the two top-level fields.
            assert(rows.size == 2, s"expected exactly 2 rows for PBAuditTree, got ${rows.size}: $paths")
            assert(paths.contains("value"), s"expected path 'value' in $paths")
            assert(paths.contains("children"), s"expected path 'children' in $paths")
            // Recursion guard: children.value / children.children must NOT appear.
            assert(!paths.contains("children.value"), s"unexpected 'children.value' - recursion guard failed")
            assert(!paths.contains("children.children"), s"unexpected 'children.children' - recursion guard failed")
        }

    }

    "protoSchema wire-true numbers and pinning" - {

        "INV-PBC-05-protoSchema-number-equals-wire" in {
            // Every message field in protoSchema output must carry its CodecMacro.fieldId number.
            val output = Protobuf.protoSchema[PBAuditPerson]
            List("name", "inner", "id").foreach { fieldName =>
                val line = output.linesIterator.find(l => l.contains(s" $fieldName = ")).getOrElse(
                    fail(s"field '$fieldName' not found in protoSchema output:\n$output")
                )
                val numStr   = line.split("=").last.trim.takeWhile(_.isDigit)
                val num      = numStr.toInt
                val expected = kyo.internal.CodecMacro.fieldId(fieldName)
                assert(num == expected, s"protoSchema number for '$fieldName' is $num but wire number (CodecMacro.fieldId) is $expected")
            }
        }

        "INV-PBC-05-no-idx-plus-one" in {
            // protoSchema emits the actual wire field number (the name hash), not the declaration
            // position: the first field's number equals CodecMacro.fieldId("name"), which is not 1.
            val output = Protobuf.protoSchema[PBAuditPerson]
            val line = output.linesIterator.find(_.contains(" name = ")).getOrElse(
                fail(s"field 'name' not found in protoSchema output:\n$output")
            )
            val num      = line.split("=").last.trim.takeWhile(_.isDigit).toInt
            val expected = kyo.internal.CodecMacro.fieldId("name")
            assert(num != 1, s"field 'name' must not use idx+1 numbering; hash-derived number is $expected not 1")
            assert(num == expected, s"field 'name' must equal CodecMacro.fieldId('name')=$expected; got $num")
        }

        "INV-PBC-05-structural-numbers-preserved" in {
            // oneof variants use idx+1 (proto3-required); MapEntry key=1/value=2 (proto3-structural).
            val sealedOutput = Protobuf.protoSchema[Protobuf1517Sealed]
            // Alpha is first variant (idx=0), must be = 1; Beta is second (idx=1), must be = 2.
            assert(sealedOutput.contains("Alpha alpha = 1;"), s"first oneof variant must be = 1:\n$sealedOutput")
            assert(sealedOutput.contains("Beta beta = 2;"), s"second oneof variant must be = 2:\n$sealedOutput")

            // A map with a non-proto3-native key (Pid is a product) is emitted as a repeated entry message.
            // The entry message's key and value field numbers are proto3-structural (always 1 and 2).
            val vcKeyOutput = Protobuf.protoSchema[PB1716VcKey]
            assert(vcKeyOutput.contains("key = 1;"), s"MapEntry key must be = 1:\n$vcKeyOutput")
            assert(vcKeyOutput.contains("value = 2;"), s"MapEntry value must be = 2:\n$vcKeyOutput")
        }

        "INV-PBC-05-provenance-comment-on-hash-derived" in {
            // Every hash-derived field declaration in PBAuditPerson / PBAuditInner must carry the nudge comment.
            val output = Protobuf.protoSchema[PBAuditPerson]
            List("name", "inner", "id").foreach { fieldName =>
                val line = output.linesIterator.find(l => l.contains(s" $fieldName = ")).getOrElse(
                    fail(s"field '$fieldName' not found in protoSchema output:\n$output")
                )
                assert(
                    line.contains("hash-derived field number; pin via Schema.fieldId"),
                    s"hash-derived field '$fieldName' line missing provenance nudge: '$line'"
                )
            }
        }

        "INV-PBC-12-reserved-WARNING-comment" in {
            // Sub-case A: hash-derived-in-band. PBReserved.bAt hashes to 19506.
            val reservedOutput = Protobuf.protoSchema[PBReserved]
            val bAtLine        = reservedOutput.linesIterator.find(_.contains(" bAt = ")).getOrElse("")
            val ctrlLine       = reservedOutput.linesIterator.find(_.contains(" ctrl = ")).getOrElse("")
            assert(
                bAtLine.contains("WARNING: in proto3 reserved range 19000-19999"),
                s"'bAt' (hash-derived in reserved band) must carry the WARNING: '$bAtLine'"
            )
            assert(
                !ctrlLine.contains("WARNING"),
                s"'ctrl' (out-of-band) must NOT carry the WARNING: '$ctrlLine'"
            )
            assert(
                ctrlLine.contains("hash-derived field number; pin via Schema.fieldId"),
                s"'ctrl' must carry the ordinary hash-derived nudge: '$ctrlLine'"
            )

            // Sub-case B: pinned-in-band. A field pinned to 19500 still triggers the WARNING.
            given pinnedInBandSchema: Schema[PBReservedPinnedHolder] = Schema[PBReservedPinnedHolder].fieldId(_.x)(19500)
            val pinnedOutput                                         = Protobuf.protoSchema[PBReservedPinnedHolder]
            val xLine                                                = pinnedOutput.linesIterator.find(_.contains(" x = ")).getOrElse("")
            assert(
                xLine.contains("WARNING: in proto3 reserved range 19000-19999"),
                s"pinned-in-band field must still carry the WARNING (unconditional on range): '$xLine'"
            )
        }

        "INV-PBC-PIN-i-override-round-trip-wire-true" in {
            // A field pinned via Schema.fieldId must encode with the pinned number and decode back correctly.
            val pinnedSchema: Schema[PBAuditPerson] = Schema[PBAuditPerson].fieldId(_.name)(7)
            given Schema[PBAuditPerson]             = pinnedSchema
            val value                               = PBAuditPerson("Alice", PBAuditInner(42))
            val bytes                               = Protobuf.encode(value)
            val result                              = Protobuf.decode[PBAuditPerson](bytes)
            assert(result == Result.Success(value), s"pinned-field round-trip failed: $result")
        }

        "INV-PBC-PIN-i-pinned-field-no-provenance-comment" in {
            // A pinned out-of-band field carries no comment; a non-pinned sibling still carries the nudge.
            given pinnedSchema: Schema[PBAuditPerson] = Schema[PBAuditPerson].fieldId(_.name)(7)
            val output                                = Protobuf.protoSchema[PBAuditPerson]
            val nameLine = output.linesIterator.find(_.contains(" name = ")).getOrElse(
                fail(s"'name' field not found in protoSchema output:\n$output")
            )
            assert(nameLine.contains("= 7;"), s"pinned field 'name' must emit = 7;: '$nameLine'")
            assert(!nameLine.contains("//"), s"pinned field 'name' must carry no comment: '$nameLine'")
            val innerLine = output.linesIterator.find(_.contains(" inner = ")).getOrElse(
                fail(s"'inner' field not found in protoSchema output:\n$output")
            )
            assert(
                innerLine.contains("hash-derived field number; pin via Schema.fieldId"),
                s"non-pinned 'inner' must still carry the hash-derived nudge: '$innerLine'"
            )
        }

    }

    "protoSchema provenance opt-out" - {

        "default config emits hash-derived provenance nudge" in {
            val output = Protobuf.protoSchema[PBAuditPerson]
            List("name", "inner", "id").foreach { fieldName =>
                val line = output.linesIterator.find(l => l.contains(s" $fieldName = ")).getOrElse(
                    fail(s"field '$fieldName' not found in protoSchema output:\n$output")
                )
                assert(
                    line.contains("hash-derived field number; pin via Schema.fieldId"),
                    s"default config: hash-derived field '$fieldName' must carry the provenance nudge: '$line'"
                )
            }
        }

        "suppressed: no pin nudge but fields still present" in {
            given Protobuf = Protobuf(Protobuf.Config(protoSchemaProvenance = false))
            val output     = Protobuf.protoSchema[PBAuditPerson]
            List("name", "inner", "id").foreach { fieldName =>
                val line = output.linesIterator.find(l => l.contains(s" $fieldName = ")).getOrElse(
                    fail(s"field '$fieldName' not found in suppressed protoSchema output:\n$output")
                )
                assert(
                    !line.contains("pin via Schema.fieldId"),
                    s"suppressed config: field '$fieldName' must not carry the pin nudge: '$line'"
                )
            }
        }

        "reserved-range WARNING always emitted even when suppressed" in {
            given Protobuf = Protobuf(Protobuf.Config(protoSchemaProvenance = false))
            val output     = Protobuf.protoSchema[PBReserved]
            val bAtLine = output.linesIterator.find(_.contains(" bAt = ")).getOrElse(
                fail(s"'bAt' field not found in protoSchema output:\n$output")
            )
            assert(
                bAtLine.contains("WARNING: in proto3 reserved range 19000-19999"),
                s"reserved-range WARNING must be emitted even when protoSchemaProvenance=false: '$bAtLine'"
            )
        }

    }

    "rename round-trip" - {

        "programmatic rename field encodes and decodes via Protobuf" in {
            given Schema[PBRenameSimple] =
                Schema[PBRenameSimple].rename("id", "wire_id").asInstanceOf[Schema[PBRenameSimple]]
            val value  = PBRenameSimple(42, "hello")
            val bytes  = Protobuf.encode(value)
            val result = Protobuf.decode[PBRenameSimple](bytes)
            assert(result == Result.Success(value), s"programmatic rename round-trip failed: $result")
        }

        "@rename annotation field encodes and decodes via Protobuf" in {
            val value  = PBRenameAnnotated(42, "hello")
            val bytes  = Protobuf.encode(value)
            val result = Protobuf.decode[PBRenameAnnotated](bytes)
            assert(result == Result.Success(value), s"@rename annotation round-trip failed: $result")
        }

        "programmatic rename composes with explicit fieldId pin" in {
            val pinnedThenRenamed: Schema[PBRenameSimple] =
                Schema[PBRenameSimple].fieldId(_.id)(7).rename("id", "wire_id").asInstanceOf[Schema[PBRenameSimple]]
            given Schema[PBRenameSimple] = pinnedThenRenamed
            val value                    = PBRenameSimple(99, "compose")
            val bytes                    = Protobuf.encode(value)
            val result                   = Protobuf.decode[PBRenameSimple](bytes)
            assert(result == Result.Success(value), s"rename+fieldId compose round-trip failed: $result")
        }

    }

end ProtobufTest

// Top-level to avoid issues with derives Schema inside nested definitions
case class WithUnitField(done: Unit) derives Schema

// #1716 fixtures: repeated and map fields.
case class PB1716ListInt(a: Int, b: List[Int]) derives Schema, CanEqual
case class PB1716NestedList(a: Boolean, b: List[Int]) derives Schema, CanEqual
case class PB1716ListNested(a: Int, d: List[PB1716NestedList]) derives Schema, CanEqual
case class PB1716Collections(
    v: Vector[Int],
    s: Seq[String],
    set: Set[Int],
    c: Chunk[Int],
    sp: Span[Byte],
    m: Map[String, Int],
    n: Map[Int, Int]
) derives Schema, CanEqual
case class PB1716MapStr(a: Int, f: Map[String, Int]) derives Schema, CanEqual
case class PB1716MapInt(a: Int, f: Map[Int, Int]) derives Schema, CanEqual
case class PB1716MapMsg(m: Map[String, MTItem]) derives Schema, CanEqual
case class PB1716Bag(values: Chunk[Int]) derives CanEqual
object PB1716Bag:
    given Schema[PB1716Bag] =
        lazy val inner = summon[Schema[Int]]
        Schema.init[PB1716Bag](
            writeFn = (value, writer) =>
                writer.arrayStart(value.values.size)
                value.values.foreach(inner.serializeWrite(_, writer))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = Chunk.newBuilder[Int]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                PB1716Bag(builder.result())
            ,
            absentDefaultValue = Maybe(PB1716Bag(Chunk.empty)),
            structure = Structure.Type.Collection(
                "PB1716Bag",
                Tag[PB1716Bag].asInstanceOf[Tag[Any]],
                inner.structure
            )
        )
    end given
end PB1716Bag
case class PB1716CustomCollection(bag: PB1716Bag) derives Schema, CanEqual

// Opaque-type key whose Schema reduces to a native scalar (sint32): a native proto3 map key.
object PB1716Ids:
    opaque type UserId = Int
    object UserId:
        def apply(i: Int): UserId            = i
        extension (u: UserId) def value: Int = u
        given Schema[UserId]                 = summon[Schema[Int]].transform(apply)(_.value)
        given CanEqual[UserId, UserId]       = CanEqual.derived
    end UserId
end PB1716Ids

case class PB1716OpaqueKey(m: Map[PB1716Ids.UserId, String]) derives Schema, CanEqual

// Value-class key that derives a message: not a proto3 map key, encoded as an entry message.
case class Pid(value: Int) derives Schema, CanEqual
case class PB1716VcKey(m: Map[Pid, String]) derives Schema, CanEqual

// Types for error-path tests
case class MTCollectionHolder(items: List[Int]) derives Schema, CanEqual

// Dedicated to the Protobuf.decode[SealedTrait] tests above.
// Named distinctly to avoid conflicts with similar all-no-arg enum types
// defined in StructureTest (those are already covered by full round-trip
// through Protobuf in StructureTest).
sealed trait Protobuf1517Sealed derives Schema, CanEqual
object Protobuf1517Sealed:
    case class Alpha(value: Int)    extends Protobuf1517Sealed derives CanEqual
    case object Beta                extends Protobuf1517Sealed derives CanEqual
    case class Gamma(label: String) extends Protobuf1517Sealed derives CanEqual
end Protobuf1517Sealed

enum Protobuf1517Enum derives Schema, CanEqual:
    case One
    case Two
    case Three
end Protobuf1517Enum

case class Protobuf1517Holder(inner: Protobuf1517Sealed) derives Schema, CanEqual

case class CFPerson(name: String, age: Int) derives CanEqual, Schema

// All-four-features composition fixtures: note carries a compile-time default so whenDefault-omit
// has something to compare against; CFAllFeaturesWide shares the first four fields and adds one
// extra to produce unknown-field wire bytes for the strict-decode leg.
case class CFAllFeatures(tag: String, qty: Int, code: Int = 0, note: String = "skip") derives CanEqual, Schema
case class CFAllFeaturesWide(tag: String, qty: Int, code: Int, note: String, extra: String) derives CanEqual, Schema

// Isolates the decode-default Protobuf interaction (no transform confound): a present field
// reported by its numeric id must not be overwritten by the configured default supplier.
case class CFDefaulted(name: String, score: Int = 0) derives CanEqual, Schema

// Map decode depth-guard fixtures.
// A two-level string-keyed map: objectStart (depth 1) + outer mapStart (depth 2) + inner mapStart (depth 3).
case class PBStr2Map(m: Map[String, Map[String, Int]]) derives Schema, CanEqual
// A two-level non-string map: objectStart (depth 1) + arrayStart (depth 2) + entry objectStart (depth 3).
case class PBIntIntMap(m: Map[Int, Map[Int, Int]]) derives Schema, CanEqual
// A two-level nested list: objectStart (depth 1) + outer arrayStart (depth 2) + inner arrayStart (depth 3).
case class PBListList(m: List[List[Int]]) derives Schema, CanEqual
case class CFPersonWide(name: String, age: Int, extra: String) derives CanEqual, Schema

// fieldNumberAudit fixtures.
case class PBAuditInner(id: Int) derives Schema, CanEqual
case class PBAuditPerson(name: String, inner: PBAuditInner) derives Schema, CanEqual
case class PBAuditReuse(list: List[PBAuditInner], map: Map[String, PBAuditInner], ints: List[Int]) derives Schema, CanEqual
case class PBAuditTree(value: Int, children: List[PBAuditTree]) derives Schema, CanEqual
// bAt hashes to 19506 (in proto3 reserved range 19000-19999); ctrl is the out-of-band control field.
case class PBReserved(bAt: Int, ctrl: Int) derives Schema, CanEqual

// Holder for testing that the reserved-range WARNING fires unconditionally on pinned-in-band numbers.
case class PBReservedPinnedHolder(x: Int) derives Schema, CanEqual

// Fixture for empty-string element regression tests.
case class PB1716SeqStr(items: Seq[String]) derives Schema, CanEqual

// Fixtures for rename Protobuf round-trip regression tests.
case class PBRenameSimple(id: Int, label: String) derives Schema, CanEqual
case class PBRenameAnnotated(@rename("wire_id") id: Int, @rename("wire_label") label: String) derives Schema, CanEqual
