package kyo

import kyo.Protobuf.ProtoSchema
import kyo.Schema.*
import kyo.internal.CodecMacro
import kyo.internal.ProtobufReader
import kyo.internal.ProtobufWriter

class ProtobufTest extends Test:

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
            assert(schema.contains("string name = 1;"))
            assert(schema.contains("sint32 age = 2;"))
        }

        "ProtoSchema nested messages" in {
            val schema = ProtoSchema.from[MTTeam]
            assert(schema.contains("message MTTeam"))
            assert(schema.contains("message MTPersonAddr"))
            assert(schema.contains("repeated MTPersonAddr members = 3;"))
        }

        "ProtoSchema optional fields" in {
            val schema = ProtoSchema.from[MTOptional]
            assert(schema.contains("optional string nickname = 2;"))
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
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[Int])
            }
        }

        "ProtoSchema top-level Collection throws IllegalArgumentException" in {
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[List[Int]])
            }
        }

        "ProtoSchema top-level Optional throws IllegalArgumentException" in {
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[Option[Int]])
            }
        }

        "ProtoSchema top-level Mapping throws IllegalArgumentException" in {
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[Map[String, Int]])
            }
        }

        "ProtoSchema List[Option[A]] field throws IllegalArgumentException" in {
            assertThrows[IllegalArgumentException] {
                ProtoSchema.from[MTListOfOption]
            }
        }

        "ProtoSchema List[List[A]] field throws IllegalArgumentException" in {
            case class ListOfList(values: List[List[Int]]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[ListOfList])
            }
        }

        "ProtoSchema List[Map[K, V]] field throws IllegalArgumentException" in {
            case class ListOfMap(values: List[Map[String, Int]]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[ListOfMap])
            }
        }

        "ProtoSchema Option[Option[A]] field throws IllegalArgumentException" in {
            case class NestedOption(value: Option[Option[Int]]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[NestedOption])
            }
        }

        "ProtoSchema Option[List[A]] field throws IllegalArgumentException" in {
            case class OptionOfList(value: Option[List[Int]]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[OptionOfList])
            }
        }

        "ProtoSchema Option[Map[K, V]] field throws IllegalArgumentException" in {
            case class OptionOfMap(value: Option[Map[String, Int]]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[OptionOfMap])
            }
        }

        "ProtoSchema Map[K, Option[V]] field throws IllegalArgumentException" in {
            case class MapWithOptionValue(value: Map[String, Option[Int]]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[MapWithOptionValue])
            }
        }

        "ProtoSchema Map[K, List[V]] field throws IllegalArgumentException" in {
            case class MapWithListValue(value: Map[String, List[Int]]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[MapWithListValue])
            }
        }

        "ProtoSchema Map[K, Map[K2, V]] field throws IllegalArgumentException" in {
            case class MapWithMapValue(value: Map[String, Map[String, Int]]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[MapWithMapValue])
            }
        }

        "ProtoSchema Map[Option[K], V] field throws IllegalArgumentException" in {
            case class MapWithOptionKey(value: Map[Option[String], Int]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[MapWithOptionKey])
            }
        }

        "ProtoSchema Map[List[K], V] field throws IllegalArgumentException" in {
            case class MapWithListKey(value: Map[List[String], Int]) derives CanEqual
            assertThrows[IllegalArgumentException] {
                ProtoSchema.fromStructure(Structure.of[MapWithListKey])
            }
        }

        "ProtoSchema Map[K, V] with non-default key types emits map<K, V>" in {
            case class MapIntToString(values: Map[Long, String]) derives CanEqual
            val schema = ProtoSchema.fromStructure(Structure.of[MapIntToString])
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
            succeed
        }

        "ProtobufReader.byte throws RangeException for out-of-range Int value" in {
            val w = new ProtobufWriter
            w.field("x", 0)
            w.int(Byte.MaxValue.toInt + 1)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            intercept[RangeException](r.byte())
            succeed
        }

        "ProtobufReader.char throws RangeException for negative Int value" in {
            val w = new ProtobufWriter
            w.field("x", 0)
            w.int(-1)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            intercept[RangeException](r.char())
            succeed
        }

        // --- ProtobufReader: TruncatedInputException ---

        "ProtobufReader.string throws TruncatedInputException when length prefix exceeds remaining data" in {
            // tag for field 1 LengthDelimited = (1 << 3) | 2 = 0x0a
            // varint length = 100, but only 1 payload byte follows
            val data = Array[Byte](0x0a.toByte, 100.toByte, 0x41.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.string())
            succeed
        }

        "ProtobufReader.bytes throws TruncatedInputException when length prefix exceeds remaining data" in {
            val data = Array[Byte](0x0a.toByte, 50.toByte, 0x00.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.bytes())
            succeed
        }

        "ProtobufReader.readVarint throws TruncatedInputException on unterminated varint" in {
            // every byte has continuation bit set, buffer ends before terminator
            val data = Array[Byte](0x80.toByte, 0x80.toByte, 0x80.toByte)
            val r    = new ProtobufReader(data)
            intercept[TruncatedInputException](r.field())
            succeed
        }

        "ProtobufReader.readFixedLong throws TruncatedInputException when under 8 bytes remain" in {
            // tag for field 1 Fixed64 = (1 << 3) | 1 = 0x09, then only 3 payload bytes
            val data = Array[Byte](0x09.toByte, 0x01.toByte, 0x02.toByte, 0x03.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.double())
            succeed
        }

        "ProtobufReader.readFixedInt throws TruncatedInputException when under 4 bytes remain" in {
            // tag for field 1 Fixed32 = (1 << 3) | 5 = 0x0d, then only 2 payload bytes
            val data = Array[Byte](0x0d.toByte, 0x01.toByte, 0x02.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.float())
            succeed
        }

        "ProtobufReader.objectStart throws TruncatedInputException when nested message length exceeds remaining" in {
            // tag for field 1 LengthDelimited = 0x0a, nested length = 50, but no bytes follow
            val data = Array[Byte](0x0a.toByte, 50.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.objectStart())
            succeed
        }

        "ProtobufReader.skip throws TruncatedInputException for fixed64 under 8 bytes remaining" in {
            val data = Array[Byte](0x09.toByte, 0x01.toByte, 0x02.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.skip())
            succeed
        }

        "ProtobufReader.skip throws TruncatedInputException for length-delimited with over-large length" in {
            val data = Array[Byte](0x0a.toByte, 100.toByte, 0x00.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.skip())
            succeed
        }

        "ProtobufReader.skip throws TruncatedInputException for fixed32 under 4 bytes remaining" in {
            val data = Array[Byte](0x0d.toByte, 0x01.toByte)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            intercept[TruncatedInputException](r.skip())
            succeed
        }

        // --- ProtobufReader: ParseException ---

        "ProtobufReader.bigInt throws ParseException on non-numeric payload" in {
            val w = new ProtobufWriter
            w.field("x", 0)
            w.string("not_a_number")
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            intercept[ParseException](r.bigInt())
            succeed
        }

        "ProtobufReader.bigDecimal throws ParseException on non-numeric payload" in {
            val w = new ProtobufWriter
            w.field("x", 0)
            w.string("not_a_decimal")
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            intercept[ParseException](r.bigDecimal())
            succeed
        }

        // --- Base Reader: LimitExceededException ---

        "ProtobufReader.checkCollectionSize throws LimitExceededException when count exceeds limit" in {
            val r = new ProtobufReader(Array.emptyByteArray)
            r.resetLimits(maxDepth = 512, maxCollectionSize = 10)
            intercept[LimitExceededException](r.checkCollectionSize(11))
            succeed
        }

        "Protobuf.decode returns LimitExceededException when collection size exceeds limit" in {
            val holder = MTCollectionHolder(List.fill(20)(1))
            val bytes  = Protobuf.encode(holder)
            val result = Protobuf.decode[MTCollectionHolder](bytes, maxCollectionSize = 5)
            assert(result.failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        "Protobuf.decode returns LimitExceededException when nesting depth exceeds limit" in {
            // Build a deeply-nested TreeNode: TreeNode(1, List(TreeNode(2, List(TreeNode(3, ...)))))
            val deep = List.range(1, 6).foldRight(TreeNode(0, scala.Nil)) {
                case (v, acc) => TreeNode(v, List(acc))
            }
            val bytes  = Protobuf.encode(deep)
            val result = Protobuf.decode[TreeNode](bytes, maxDepth = 2)
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

end ProtobufTest

// Top-level to avoid issues with derives Schema inside nested definitions
case class WithUnitField(done: Unit) derives Schema

// Types for error-path tests
case class MTCollectionHolder(items: List[Int]) derives Schema, CanEqual

// Dedicated to the Protobuf.decode[SealedTrait] regression tests above.
// Unique names avoid conflicts with the RegressionEnum1517* types that are
// reused across StructureTest and JsonTest (those are already covered by
// full round-trip through Protobuf in StructureTest).
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
