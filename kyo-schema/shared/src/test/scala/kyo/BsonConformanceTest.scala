package kyo

import scala.reflect.ClassTag

// Document-shaped probe type: BSON has no top-level-scalar encoding, so every malformed-wire
// fixture below needs a concrete document-shaped target to decode against.
case class BCFProbe(v: Int) derives Schema

case class BsonListOfLists(values: List[List[Int]]) derives CanEqual, Schema

// Four-field probe with a fixed declaration order, used to pin the OrderedDict-backed
// document store's field-order round-trip.
case class BsonOrderedDoc(_id: Int, name: String, version: Int, tags: List[String]) derives CanEqual, Schema

// Twelve-field probe with a fixed non-alphabetical declaration order. OrderedDict switches
// from its flat small representation to a TreeSeqMap-backed large representation above 8
// entries, so this fixture pins field-order preservation past that switch.
case class BsonWideDoc(
    zz: Int,
    aa: Int,
    mm: Int,
    bb: Int,
    yy: Int,
    cc: Int,
    xx: Int,
    dd: Int,
    ww: Int,
    ee: Int,
    vv: Int,
    ff: Int
) derives CanEqual, Schema

class BsonConformanceTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def bin(values: Int*): Span[Byte] =
        Span.from(values.map(_.toByte).toArray)

    // Asserts the decode failure is the specific leaf exception E and that its message
    // contains messageSubstring, so each fixture proves it failed for its intended reason
    // rather than merely failing.
    private def assertDecodeFailure[E <: DecodeException](input: Span[Byte], messageSubstring: String)(using
        ct: ClassTag[E],
        scope: kyo.test.AssertScope
    ): Unit =
        Bson.decode[BCFProbe](input).failure match
            case Maybe.Present(e) =>
                assert(
                    ct.runtimeClass.isInstance(e),
                    s"expected ${ct.runtimeClass.getSimpleName} but got ${e.getClass.getSimpleName}: ${e.getMessage}"
                )
                assert(
                    e.getMessage.contains(messageSubstring),
                    s"expected message to contain \"$messageSubstring\" but got: ${e.getMessage}"
                )
            case Maybe.Absent =>
                fail(s"Expected a decode failure containing \"$messageSubstring\"")
        end match
    end assertDecodeFailure

    private def assertDecodeFailureAs[A: Schema, E <: DecodeException](input: Span[Byte], messageSubstring: String)(using
        ct: ClassTag[E],
        scope: kyo.test.AssertScope
    ): Unit =
        Bson.decode[A](input).failure match
            case Maybe.Present(e) =>
                assert(
                    ct.runtimeClass.isInstance(e),
                    s"expected ${ct.runtimeClass.getSimpleName} but got ${e.getClass.getSimpleName}: ${e.getMessage}"
                )
                assert(
                    e.getMessage.contains(messageSubstring),
                    s"expected message to contain \"$messageSubstring\" but got: ${e.getMessage}"
                )
            case Maybe.Absent =>
                fail(s"Expected a decode failure containing \"$messageSubstring\"")
        end match
    end assertDecodeFailureAs

    // Single-element document `{v: <reserved tag>}`: the reserved tag is rejected inside
    // Parser.parseElement before any payload byte is read, so the fixture needs no payload.
    private def reservedTagDocument(tag: Int): Span[Byte] =
        bin(8, 0, 0, 0, tag, 'v'.toByte, 0, 0)

    // Single-element document `{data: <binary subtype>}` carrying a 3-byte payload, matching
    // BsonTest's binary subtype 0 fixture shape with the subtype byte parameterized.
    private def binarySubtypeDocument(subtype: Int): Span[Byte] =
        bin(19, 0, 0, 0, 0x05, 100, 97, 116, 97, 0, 3, 0, 0, 0, subtype, 1, 2, 3, 0)

    // Reads the document's fields through the public introspection surface (readStructure),
    // which walks the reader's positional cursor in wire order and preserves it in the
    // returned Chunk, so callers can assert on field name and value order without reaching
    // into the private codec vocabulary.
    private def recordFields(reader: Codec.Reader)(using scope: kyo.test.AssertScope): Chunk[(String, Structure.Value)] =
        reader match
            case introspecting: Codec.IntrospectingReader =>
                introspecting.readStructure() match
                    case Structure.Value.Record(fields) => fields
                    case other                          => fail(s"expected a record structure, got $other")
                end match
            case other => fail(s"expected introspecting reader, got $other")
    end recordFields

    "wire validation" - {

        "rejects reserved element type ObjectId" in {
            assertDecodeFailure[ParseException](reservedTagDocument(0x07), "supported BSON element type 0x7")
        }

        "rejects reserved element type Regular expression" in {
            assertDecodeFailure[ParseException](reservedTagDocument(0x0b), "supported BSON element type 0xb")
        }

        "rejects reserved element type Timestamp" in {
            assertDecodeFailure[ParseException](reservedTagDocument(0x11), "supported BSON element type 0x11")
        }

        "rejects reserved element type MinKey" in {
            assertDecodeFailure[ParseException](reservedTagDocument(0xff), "supported BSON element type 0xff")
        }

        "rejects binary subtype 0x01 (function)" in {
            assertDecodeFailureAs[BsonBytes, ParseException](binarySubtypeDocument(0x01), "rejected 0x1")
        }

        "rejects binary subtype 0x03 (UUID legacy)" in {
            assertDecodeFailureAs[BsonBytes, ParseException](binarySubtypeDocument(0x03), "rejected 0x3")
        }

        "rejects binary subtype 0x05 (MD5)" in {
            assertDecodeFailureAs[BsonBytes, ParseException](binarySubtypeDocument(0x05), "rejected 0x5")
        }

        "rejects binary subtype 0x80 (user-defined)" in {
            assertDecodeFailureAs[BsonBytes, ParseException](binarySubtypeDocument(0x80), "rejected 0x80")
        }

        "rejects old binary subtype 2 with an inconsistent nested length" in {
            val mismatchedOldSubtype = bin(
                23, 0, 0, 0,
                0x05, 100, 97, 116, 97, 0,
                7, 0, 0, 0,
                0x02,
                5, 0, 0, 0,
                1, 2, 3,
                0
            )
            assertDecodeFailureAs[BsonBytes, ParseException](mismatchedOldSubtype, "old binary inner length")
        }

        "rejects an array with a missing index" in {
            val missingIndex = bin(
                32,
                0,
                0,
                0,
                0x04,
                'v'.toByte,
                'a'.toByte,
                'l'.toByte,
                'u'.toByte,
                'e'.toByte,
                's'.toByte,
                0,
                19,
                0,
                0,
                0,
                0x10,
                '0'.toByte,
                0,
                1,
                0,
                0,
                0,
                0x10,
                '2'.toByte,
                0,
                3,
                0,
                0,
                0,
                0,
                0
            )
            assertDecodeFailureAs[BsonInts, ParseException](missingIndex, "array index 1")
        }

        "rejects an array with a duplicate index" in {
            val duplicateIndex = bin(
                32,
                0,
                0,
                0,
                0x04,
                'v'.toByte,
                'a'.toByte,
                'l'.toByte,
                'u'.toByte,
                'e'.toByte,
                's'.toByte,
                0,
                19,
                0,
                0,
                0,
                0x10,
                '0'.toByte,
                0,
                1,
                0,
                0,
                0,
                0x10,
                '0'.toByte,
                0,
                3,
                0,
                0,
                0,
                0,
                0
            )
            assertDecodeFailureAs[BsonInts, ParseException](duplicateIndex, "array index 1")
        }

        "rejects an array with a non-decimal key" in {
            val nonDecimalKey = bin(
                32,
                0,
                0,
                0,
                0x04,
                'v'.toByte,
                'a'.toByte,
                'l'.toByte,
                'u'.toByte,
                'e'.toByte,
                's'.toByte,
                0,
                19,
                0,
                0,
                0,
                0x10,
                '0'.toByte,
                0,
                1,
                0,
                0,
                0,
                0x10,
                'a'.toByte,
                0,
                3,
                0,
                0,
                0,
                0,
                0
            )
            assertDecodeFailureAs[BsonInts, ParseException](nonDecimalKey, "array index 1")
        }

        "rejects an array with out-of-order indexes" in {
            val outOfOrder = bin(
                32,
                0,
                0,
                0,
                0x04,
                'v'.toByte,
                'a'.toByte,
                'l'.toByte,
                'u'.toByte,
                'e'.toByte,
                's'.toByte,
                0,
                19,
                0,
                0,
                0,
                0x10,
                '1'.toByte,
                0,
                1,
                0,
                0,
                0,
                0x10,
                '0'.toByte,
                0,
                3,
                0,
                0,
                0,
                0,
                0
            )
            assertDecodeFailureAs[BsonInts, ParseException](outOfOrder, "array index 0")
        }

        "readStructure rejects a document with a reserved element type" in {
            discard(intercept[ParseException] {
                Bson().newReader(reservedTagDocument(0x07)) match
                    case reader: Codec.IntrospectingReader => reader.readStructure()
                    case other                             => fail(s"expected introspecting reader, got $other")
                end match
            })
        }

        "readStructure rejects a document with a rejected binary subtype" in {
            discard(intercept[ParseException] {
                Bson().newReader(binarySubtypeDocument(0x01)) match
                    case reader: Codec.IntrospectingReader => reader.readStructure()
                    case other                             => fail(s"expected introspecting reader, got $other")
                end match
            })
        }

        "rejects a BigDecimal with more than 34 significant digits" in {
            val tooManyDigits = BigDecimal(BigInt("1" * 35))
            discard(intercept[SchemaNotSerializableException](Bson.encode(BsonDecimalOnly(tooManyDigits))))
        }

        "rejects a BigDecimal with exponent above the Decimal128 range" in {
            val tooLargeExponent = BigDecimal(BigInt(1), -6200)
            discard(intercept[SchemaNotSerializableException](Bson.encode(BsonDecimalOnly(tooLargeExponent))))
        }

        "rejects a BigDecimal with exponent below the Decimal128 range" in {
            val tooSmallExponent = BigDecimal(BigInt(1), 6200)
            discard(intercept[SchemaNotSerializableException](Bson.encode(BsonDecimalOnly(tooSmallExponent))))
        }

        "rejects a BigInt above Long.MaxValue during encode" in {
            val value = BsonDecimal(BigDecimal(1), BigInt(Long.MaxValue) + 1)
            discard(intercept[SchemaNotSerializableException](Bson.encode(value)))
        }

        "rejects a BigInt below Long.MinValue during encode" in {
            val value = BsonDecimal(BigDecimal(1), BigInt(Long.MinValue) - 1)
            discard(intercept[SchemaNotSerializableException](Bson.encode(value)))
        }
    }

    "limits and structure" - {

        "enforces depth limits through readStructure" in {
            val encoded = Bson.encode(BsonNested(BsonPerson("Alice", 30)))
            val reader  = Bson().newReader(encoded)
            reader.resetLimits(maxDepth = 1, maxCollectionSize = Bson.DefaultMaxCollectionSize)
            reader match
                case introspecting: Codec.IntrospectingReader =>
                    discard(intercept[LimitExceededException](introspecting.readStructure()))
                case other => fail(s"expected introspecting reader, got $other")
            end match
        }

        "enforces collection-size limits through readStructure" in {
            val encoded = Bson.encode(BsonInts(List(1, 2)))
            val reader  = Bson().newReader(encoded)
            reader.resetLimits(maxDepth = Bson.DefaultMaxDepth, maxCollectionSize = 1)
            reader match
                case introspecting: Codec.IntrospectingReader =>
                    discard(intercept[LimitExceededException](introspecting.readStructure()))
                case other => fail(s"expected introspecting reader, got $other")
            end match
        }

        "enforces depth limits through captureValue" in {
            val encoded = Bson.encode(BsonListOfLists(List(List(1))))
            val reader  = Bson().newReader(encoded)
            reader.resetLimits(maxDepth = 1, maxCollectionSize = Bson.DefaultMaxCollectionSize)
            discard(reader.objectStart())
            reader.fieldParse()
            val captured = reader.captureValue()
            captured match
                case introspecting: Codec.IntrospectingReader =>
                    discard(intercept[LimitExceededException](introspecting.readStructure()))
                case other => fail(s"expected introspecting reader, got $other")
            end match
        }

        "enforces collection-size limits through captureValue" in {
            val encoded = Bson.encode(BsonInts(List(1, 2)))
            val reader  = Bson().newReader(encoded)
            reader.resetLimits(maxDepth = Bson.DefaultMaxDepth, maxCollectionSize = 1)
            discard(reader.objectStart())
            reader.fieldParse()
            val captured = reader.captureValue()
            captured match
                case introspecting: Codec.IntrospectingReader =>
                    discard(intercept[LimitExceededException](introspecting.readStructure()))
                case other => fail(s"expected introspecting reader, got $other")
            end match
        }
    }

    "OrderedDict document store" - {

        "well-formed multi-field document round-trips field order and re-encodes byte-identically" in {
            val value   = BsonOrderedDoc(1, "kyo", 2, List("a", "b"))
            val encoded = Bson.encode(value)
            val fields  = recordFields(Bson().newReader(encoded))
            assert(fields.map(_._1) == Chunk("_id", "name", "version", "tags"))
            val decoded   = Bson.decode[BsonOrderedDoc](encoded).getOrThrow
            val reencoded = Bson.encode(decoded)
            assert(CodecTestSupport.sameBytes(reencoded, encoded))
        }

        "reader consumes document fields in exact declared wire order (non-alphabetical)" in {
            val nonAlphabetical = bin(
                26,
                0,
                0,
                0,
                0x10,
                'z'.toByte,
                0,
                1,
                0,
                0,
                0,
                0x10,
                'a'.toByte,
                0,
                2,
                0,
                0,
                0,
                0x10,
                'm'.toByte,
                0,
                3,
                0,
                0,
                0,
                0
            )
            val fields = recordFields(Bson().newReader(nonAlphabetical))
            assert(fields == Chunk(
                "z" -> Structure.Value.Integer(1),
                "a" -> Structure.Value.Integer(2),
                "m" -> Structure.Value.Integer(3)
            ))
        }

        "duplicate BSON document field names decode last-value-wins at first position" in {
            val duplicateFieldNames = bin(
                33,
                0,
                0,
                0,
                0x10,
                'd'.toByte,
                'u'.toByte,
                'p'.toByte,
                0,
                10,
                0,
                0,
                0,
                0x10,
                'k'.toByte,
                'e'.toByte,
                'e'.toByte,
                'p'.toByte,
                0,
                99,
                0,
                0,
                0,
                0x10,
                'd'.toByte,
                'u'.toByte,
                'p'.toByte,
                0,
                20,
                0,
                0,
                0,
                0
            )
            val fields = recordFields(Bson().newReader(duplicateFieldNames))
            assert(fields == Chunk(
                "dup"  -> Structure.Value.Integer(20),
                "keep" -> Structure.Value.Integer(99)
            ))
        }

        "large document above the small/large representation threshold round-trips declared field order" in {
            val value   = BsonWideDoc(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
            val encoded = Bson.encode(value)
            // Wire length: 4-byte document length prefix, 12 int32 elements each contributing
            // 1 type-tag byte + a 2-character name + its null terminator + a 4-byte value
            // (1 + 3 + 4 = 8 bytes per element), plus the 1-byte document terminator:
            // 4 + 12 * 8 + 1 = 101 bytes.
            assert(encoded.size == 101)
            val fields = recordFields(Bson().newReader(encoded))
            assert(fields.map(_._1) == Chunk("zz", "aa", "mm", "bb", "yy", "cc", "xx", "dd", "ww", "ee", "vv", "ff"))
            val decoded   = Bson.decode[BsonWideDoc](encoded).getOrThrow
            val reencoded = Bson.encode(decoded)
            assert(CodecTestSupport.sameBytes(reencoded, encoded))
        }
    }

end BsonConformanceTest
