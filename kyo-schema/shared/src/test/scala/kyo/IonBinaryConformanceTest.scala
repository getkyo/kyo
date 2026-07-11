package kyo

// --- Cross-format round-trip sweep fixtures: a deterministic value space covering
// products, lists, maps, options, a sum carrying bytes, instants, durations, bigints, and
// bigdecimals. Every fixture is a fixed literal, never randomly generated. ---

case class IBCFPoint(x: Int, y: Int) derives CanEqual, Schema
case class IBCFProduct(name: String, point: IBCFPoint) derives CanEqual, Schema

sealed trait IBCFSum derives CanEqual, Schema
case class IBCFSumCount(count: Int) extends IBCFSum derives CanEqual

sealed trait IBCFBytesSum derives Schema
case class IBCFBytesPayload(data: Span[Byte]) extends IBCFBytesSum
case class IBCFBytesTag(tag: String)          extends IBCFBytesSum derives CanEqual

case class IBCFCombined(
    product: IBCFProduct,
    items: List[Int],
    tags: Map[String, Int],
    nickname: Option[String],
    sum: IBCFSum,
    at: java.time.Instant,
    span: java.time.Duration,
    big: BigInt,
    dec: BigDecimal
) derives CanEqual, Schema

class IonBinaryConformanceTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def bin(values: Int*): Span[Byte] =
        Span.from(values.map(_.toByte).toArray)

    private def assertDecodeFailure(input: Span[Byte], expected: String)(using kyo.test.AssertScope): Unit =
        val result = IonBinary.decode[Int](input)
        if result.failure.isEmpty then fail(s"Expected decode failure for $expected")
        assert(result.failure.get.isInstanceOf[DecodeException])
    end assertDecodeFailure

    private def assertDecodeFailureAs[A: Schema](input: Span[Byte], expected: String)(using kyo.test.AssertScope): Unit =
        val result = IonBinary.decode[A](input)
        if result.failure.isEmpty then fail(s"Expected decode failure for $expected")
        assert(result.failure.get.isInstanceOf[DecodeException])
    end assertDecodeFailureAs

    // Text is the oracle: binary-encode-decode must match text-encode-decode, which must match
    // the original value.
    private def assertCrossFormatRoundTrip[A](value: A)(using
        schema: Schema[A],
        ce: CanEqual[A, A],
        scope: kyo.test.AssertScope
    ): Unit =
        val textDecoded = Ion.decode[A](Ion.encode(value)).getOrThrow
        assert(textDecoded == value, s"Ion text oracle mismatch: $textDecoded != $value")
        val binaryDecoded = IonBinary.decode[A](IonBinary.encode(value)).getOrThrow
        assert(binaryDecoded == textDecoded, s"Ion Binary mismatch vs text oracle: $binaryDecoded != $textDecoded")
    end assertCrossFormatRoundTrip

    private def assertBytesCrossFormatRoundTrip(value: Span[Byte])(using kyo.test.AssertScope): Unit =
        val textDecoded = Ion.decode[Span[Byte]](Ion.encode(value)).getOrThrow
        assert(textDecoded.toArray.toSeq == value.toArray.toSeq, s"Ion text oracle mismatch")
        val binaryDecoded = IonBinary.decode[Span[Byte]](IonBinary.encode(value)).getOrThrow
        assert(binaryDecoded.toArray.toSeq == textDecoded.toArray.toSeq, s"Ion Binary mismatch vs text oracle")
    end assertBytesCrossFormatRoundTrip

    "wire validation" - {

        "requires a valid version marker" in {
            assertDecodeFailure(Span.empty, "missing marker")
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xeb, 0x20), "bad marker")
        }

        "rejects unterminated variable integers and lengths" in {
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0x2e, 0x01), "unterminated VarUInt length")
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0x51, 0x01), "unterminated VarInt exponent")
        }

        "rejects truncated payloads and trailing values" in {
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0x82, 0x61), "truncated string")
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0x20, 0x20), "trailing value")
        }

        "rejects illegal descriptor shapes" in {
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0x12), "bad bool")
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0x30), "negative zero")
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0x41, 0x00), "bad float length")
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0xbf), "null list")
        }

        "rejects malformed decimal coefficients" in {
            assertDecodeFailureAs[BigDecimal](bin(0xe0, 0x01, 0x00, 0xea, 0x51, 0xc0), "negative zero decimal exponent")
            assertDecodeFailureAs[BigDecimal](bin(0xe0, 0x01, 0x00, 0xea, 0x52, 0x80, 0x80), "negative zero decimal coefficient")
        }

        "rejects invalid and reduced timestamp shapes" in {
            assertDecodeFailureAs[java.time.Instant](
                bin(0xe0, 0x01, 0x00, 0xea, 0x68, 0xc0, 0x0f, 0xe8, 0x81, 0x82, 0x83, 0x84, 0x85),
                "unknown offset"
            )
            assertDecodeFailureAs[java.time.Instant](
                bin(0xe0, 0x01, 0x00, 0xea, 0x68, 0x80, 0x0f, 0xe8, 0x8d, 0x82, 0x83, 0x84, 0x85),
                "bad month"
            )
            assertDecodeFailureAs[java.time.Instant](
                bin(0xe0, 0x01, 0x00, 0xea, 0x67, 0x80, 0x0f, 0xe8, 0x81, 0x82, 0x83, 0x84),
                "missing second"
            )
            assertDecodeFailureAs[Structure.Value](
                bin(0xe0, 0x01, 0x00, 0xea, 0x68, 0xc0, 0x0f, 0xe8, 0x81, 0x82, 0x83, 0x84, 0x85),
                "readStructure unknown offset"
            )
        }

        "rejects unresolved symbol ids" in {
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0x71, 0x0a), "symbol id")
        }

        "rejects annotation wrapper boundary and SID errors" in {
            assertDecodeFailure(
                bin(0xe0, 0x01, 0x00, 0xea, 0xe6, 0x83, 0x81, 0x81, 0x20, 0x01, 0xff),
                "outer wrapper longer than embedded wrapper"
            )
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0xe3, 0x84, 0x81, 0x81), "outer wrapper shorter than embedded wrapper")
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0xe3, 0x82, 0x80, 0x20), "zero annotation count")
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0xe4, 0x83, 0x81, 0x80, 0x20), "annotation SID zero")
        }

        "rejects malformed local symbol tables" in {
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0xe7, 0x86, 0x81, 0x83, 0xd3, 0x87, 0x81, 0x78, 0x20), "symbols not list")
            assertDecodeFailure(
                bin(0xe0, 0x01, 0x00, 0xea, 0xe8, 0x87, 0x81, 0x83, 0xd4, 0x87, 0xb2, 0x21, 0x01, 0x20),
                "non-string symbol"
            )
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0xe7, 0x86, 0x81, 0x83, 0xd3, 0x86, 0xb1, 0x80, 0x20), "unsupported imports")
            assertDecodeFailure(bin(0xe0, 0x01, 0x00, 0xea, 0xd2, 0x8a, 0x20), "unresolved field SID")
        }

        "rejects truncated nested container payloads" in {
            assertDecodeFailureAs[List[String]](bin(0xe0, 0x01, 0x00, 0xea, 0xb2, 0x82, 0x61), "nested string payload")
        }

        "accepts top-level NOP padding" in {
            val input = bin(0xe0, 0x01, 0x00, 0xea, 0x01, 0xff, 0x21, 0x01, 0x02, 0xff, 0xff)
            assert(IonBinary.decode[Int](input).getOrThrow == 1)
        }
    }

    "limits and structure" - {

        "enforces collection and depth limits through ordinary reads" in {
            val list = IonBinary.encode(List(List(1)))
            assert(IonBinary.decode[List[List[Int]]](list, maxDepth = 1).failure.exists(_.isInstanceOf[LimitExceededException]))
            assert(IonBinary.decode[List[Int]](
                IonBinary.encode(List(1, 2)),
                maxCollectionSize = 1
            ).failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        "enforces depth limits through skipped unknown fields" in {
            val writer = IonBinary().newWriter()
            writer.objectStart("MTPerson", 3)
            writer.field("name", 0)
            writer.string("Alice")
            writer.field("age", 1)
            writer.int(30)
            writer.field("extra", 2)
            writer.arrayStart(1)
            writer.arrayStart(1)
            writer.int(1)
            writer.arrayEnd()
            writer.arrayEnd()
            writer.objectEnd()
            assert(IonBinary.decode[MTPerson](writer.result(), maxDepth = 1).failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        "enforces depth limits through captureValue" in {
            val reader = IonBinary().newReader(IonBinary.encode(List(List(1))))
            reader.resetLimits(maxDepth = 1, maxCollectionSize = IonBinary.DefaultMaxCollectionSize)
            discard(reader.arrayStart())
            assert(reader.hasNextElement())
            discard(intercept[LimitExceededException](reader.captureValue()))
        }

        "enforces collection-size limits through captureValue" in {
            val reader = IonBinary().newReader(IonBinary.encode(List(1, 2)))
            reader.resetLimits(maxDepth = IonBinary.DefaultMaxDepth, maxCollectionSize = 1)
            discard(intercept[LimitExceededException](reader.captureValue()))
        }

        "readStructure materializes representable values" in {
            val input = IonBinary.encode(Structure.Value.Record(Chunk(
                "n" -> Structure.Value.Integer(1),
                "s" -> Structure.Value.Sequence(Chunk(Structure.Value.Str("x")))
            )))
            IonBinary().newReader(input) match
                case reader: Codec.IntrospectingReader =>
                    assert(reader.readStructure() == Structure.Value.Record(Chunk(
                        "n" -> Structure.Value.Integer(1),
                        "s" -> Structure.Value.Sequence(Chunk(Structure.Value.Str("x")))
                    )))
                case other => fail(s"expected introspecting reader, got $other")
            end match
        }

        "readStructure rejects clob metadata" in {
            val input = bin(0xe0, 0x01, 0x00, 0xea, 0x91, 0x61)
            IonBinary().newReader(input) match
                case reader: Codec.IntrospectingReader =>
                    intercept[ParseException](reader.readStructure())
                case other => fail(s"expected introspecting reader, got $other")
            end match
            ()
        }
    }

    "cross-format round-trip sweep" - {

        "round trips a simple product" in {
            assertCrossFormatRoundTrip(IBCFPoint(3, 4))
        }

        "round trips a nested product" in {
            assertCrossFormatRoundTrip(IBCFProduct("origin", IBCFPoint(0, 0)))
        }

        "round trips a list of ints" in {
            assertCrossFormatRoundTrip(List(1, 2, 3))
        }

        "round trips an empty list" in {
            assertCrossFormatRoundTrip(List.empty[Int])
        }

        "round trips a list of products" in {
            assertCrossFormatRoundTrip(List(IBCFPoint(1, 1), IBCFPoint(2, 2)))
        }

        "round trips a map" in {
            assertCrossFormatRoundTrip(Map("a" -> 1, "b" -> 2))
        }

        "round trips an empty map" in {
            assertCrossFormatRoundTrip(Map.empty[String, Int])
        }

        "round trips a map of lists" in {
            assertCrossFormatRoundTrip(Map("a" -> List(1, 2), "b" -> List(3)))
        }

        "round trips a present option" in {
            assertCrossFormatRoundTrip(Some(5): Option[Int])
        }

        "round trips an absent option" in {
            assertCrossFormatRoundTrip(None: Option[Int])
        }

        "round trips a sum variant" in {
            assertCrossFormatRoundTrip(IBCFSumCount(7): IBCFSum)
        }

        "round trips an instant" in {
            assertCrossFormatRoundTrip(java.time.Instant.parse("2026-07-10T12:00:00.250Z"))
        }

        "round trips a duration" in {
            assertCrossFormatRoundTrip(java.time.Duration.ofSeconds(45, 123456789))
        }

        "round trips a positive bigint" in {
            assertCrossFormatRoundTrip(BigInt("123456789012345678901234567890"))
        }

        "round trips a negative bigint" in {
            assertCrossFormatRoundTrip(BigInt("-987654321098765432109876543210"))
        }

        "round trips a fractional bigdecimal" in {
            assertCrossFormatRoundTrip(BigDecimal("3.14159265358979323846"))
        }

        "round trips a negative-exponent bigdecimal" in {
            assertCrossFormatRoundTrip(BigDecimal("-2.5E-10"))
        }

        "round trips bytes standalone" in {
            assertBytesCrossFormatRoundTrip(Span.from(Array[Byte](1, 2, 3, 4, 5)))
        }

        "round trips a sum carrying bytes" in {
            val value: IBCFBytesSum = IBCFBytesPayload(Span.from(Array[Byte](9, 8, 7)))
            val textDecoded         = Ion.decode[IBCFBytesSum](Ion.encode(value)).getOrThrow
            val binaryDecoded       = IonBinary.decode[IBCFBytesSum](IonBinary.encode(value)).getOrThrow
            (textDecoded, binaryDecoded) match
                case (IBCFBytesPayload(textData), IBCFBytesPayload(binaryData)) =>
                    assert(textData.toArray.toSeq == Seq[Byte](9, 8, 7), s"Ion text oracle mismatch: $textDecoded")
                    assert(binaryData.toArray.toSeq == textData.toArray.toSeq, s"Ion Binary mismatch vs text oracle: $binaryDecoded")
                case other =>
                    fail(s"expected both decodes to be IBCFBytesPayload, got $other")
            end match
        }

        "round trips a combined product systematically composing every category" in {
            val value = IBCFCombined(
                product = IBCFProduct("Alice", IBCFPoint(1, 2)),
                items = List(1, 2, 3),
                tags = Map("a" -> 1, "b" -> 2),
                nickname = Some("Ally"),
                sum = IBCFSumCount(9),
                at = java.time.Instant.parse("2026-01-02T03:04:05.678Z"),
                span = java.time.Duration.ofMinutes(90).plusSeconds(30),
                big = BigInt("42424242424242424242"),
                dec = BigDecimal("123.456")
            )
            assertCrossFormatRoundTrip(value)
        }
    }

end IonBinaryConformanceTest
