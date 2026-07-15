package kyo

case class BsonPerson(name: String, age: Int) derives CanEqual, Schema
case class BsonNested(person: BsonPerson) derives CanEqual, Schema
case class BsonBytes(data: Span[Byte]) derives Schema
case class BsonInts(values: List[Int]) derives CanEqual, Schema
case class BsonTimes(instant: java.time.Instant, duration: java.time.Duration, kyoDuration: kyo.Duration) derives CanEqual, Schema
case class BsonDecimal(value: BigDecimal, big: BigInt) derives CanEqual, Schema
case class BsonDecimalOnly(value: BigDecimal) derives CanEqual, Schema
case class BsonDecimalDouble(value: BigDecimal) derives CanEqual, Schema

class BsonTest extends kyo.test.Test[Any]:

    "BSON" - {

        "encodes stable document bytes" in {
            val bytes = Bson.encode(BsonPerson("Alice", 30)).toArray.toSeq

            assert(bytes == Seq[Byte](
                30,
                0,
                0,
                0,
                0x02,
                'n'.toByte,
                'a'.toByte,
                'm'.toByte,
                'e'.toByte,
                0,
                6,
                0,
                0,
                0,
                'A'.toByte,
                'l'.toByte,
                'i'.toByte,
                'c'.toByte,
                'e'.toByte,
                0,
                0x10,
                'a'.toByte,
                'g'.toByte,
                'e'.toByte,
                0,
                30,
                0,
                0,
                0,
                0
            ))
        }

        "round trips a product document" in {
            val value = BsonPerson("Alice", 30)

            assert(Bson.decode[BsonPerson](Bson.encode(value)).getOrThrow == value)
        }

        "defaults Config and the contextual Bson to Bson.Config.Default" in {
            assert(Bson.Config() == Bson.Config.Default)
            assert(Bson().config == Bson.Config.Default)
            assert(summon[Bson].config == Bson.Config.Default)
        }

        "explicit config helpers match contextual helpers" in {
            val value  = BsonPerson("Bob", 41)
            val config = Bson.Config(maxDepth = 8, maxCollectionSize = 16)

            assert(CodecTestSupport.sameBytes(Bson.encode(value, config), Bson.encodeBytes(value, config)))
            assert(Bson.decodeBytes[BsonPerson](Bson.encode(value, config), config).getOrThrow == value)
        }

        "decode surfaces a non-serializable schema as Result.Panic, not an uncaught throw" in {
            val encoded = Bson.encode(BsonPerson("Alice", 30))
            val result =
                given Schema[Int] = Schema[Int]
                Bson.decode[Int](encoded)
            result match
                case Result.Panic(ex: SchemaNotSerializableException) =>
                    assert(ex.getMessage.contains("does not have serialization"))
                case other =>
                    fail(s"Expected Result.Panic(SchemaNotSerializableException) but got $other")
            end match
        }

        "rejects top-level scalars before returning bytes" in {
            val ex = intercept[SchemaNotSerializableException](Bson.encode(1))
            assert(ex.detail.contains("top-level document"))
        }

        "rejects top-level arrays before returning bytes" in {
            val ex = intercept[SchemaNotSerializableException](Bson.encode(List(1, 2, 3)))
            assert(ex.detail.contains("top-level document"))
        }

        "rejects a top-level null before returning bytes" in {
            val ex = intercept[SchemaNotSerializableException](Bson.encode(Maybe.empty[BsonPerson]))
            assert(ex.detail.contains("top-level document"))
        }

        "enforces configured decode depth" in {
            val encoded = Bson.encode(BsonNested(BsonPerson("Alice", 30)))

            assert(Bson.decode[BsonNested](encoded, Bson.Config(maxDepth = 1)).isFailure)
        }

        "enforces configured collection size while parsing" in {
            assert(Bson.decode[BsonPerson](Bson.encode(BsonPerson("Alice", 30)), Bson.Config(maxCollectionSize = 1)).isFailure)
            assert(Bson.decode[BsonInts](Bson.encode(BsonInts(List(1, 2))), Bson.Config(maxCollectionSize = 1)).isFailure)
        }

        "applies explicit decode limits through the 3-arg overloads over a differing contextual bson" in {
            given customBson: Bson = Bson(Bson.Config(maxDepth = 1, maxCollectionSize = 1))

            val nested = Bson.encode(BsonNested(BsonPerson("Alice", 30)))
            assert(Bson.decode[BsonNested](nested, maxDepth = 8, maxCollectionSize = 8).getOrThrow == BsonNested(BsonPerson("Alice", 30)))
            assert(Bson.decode[BsonNested](nested, maxDepth = 1, maxCollectionSize = 8).isFailure)
            assert(Bson.decodeBytes[BsonNested](
                nested,
                maxDepth = 8,
                maxCollectionSize = 8
            ).getOrThrow == BsonNested(BsonPerson("Alice", 30)))
            assert(Bson.decodeBytes[BsonNested](nested, maxDepth = 1, maxCollectionSize = 8).isFailure)

            val person = Bson.encode(BsonPerson("Bob", 41))
            assert(Bson.decode[BsonPerson](person, maxDepth = 8, maxCollectionSize = 8).getOrThrow == BsonPerson("Bob", 41))
            assert(Bson.decode[BsonPerson](person, maxDepth = 8, maxCollectionSize = 1).isFailure)
            assert(Bson.decodeBytes[BsonPerson](person, maxDepth = 8, maxCollectionSize = 8).getOrThrow == BsonPerson("Bob", 41))
            assert(Bson.decodeBytes[BsonPerson](person, maxDepth = 8, maxCollectionSize = 1).isFailure)
        }

        "controls single-arg decode and decodeBytes through a custom contextual bson" in {
            given Bson = Bson(Bson.Config(maxDepth = 1))

            val encoded = Bson.encode(BsonNested(BsonPerson("Alice", 30)))

            assert(Bson.decode[BsonNested](encoded).isFailure)
            assert(Bson.decodeBytes[BsonNested](encoded).isFailure)
        }

        "encodes binary subtype 0 bytes" in {
            val data  = Span.from(Array[Byte](1, 2, 3))
            val bytes = Bson.encode(BsonBytes(data)).toArray.toSeq

            assert(bytes == Seq[Byte](
                19,
                0,
                0,
                0,
                0x05,
                'd'.toByte,
                'a'.toByte,
                't'.toByte,
                'a'.toByte,
                0,
                3,
                0,
                0,
                0,
                0,
                1,
                2,
                3,
                0
            ))
            assert(CodecTestSupport.sameBytes(Bson.decode[BsonBytes](Span.from(bytes.toArray)).getOrThrow.data, data))

            val invalidSubtype = bytes.toArray
            invalidSubtype(14) = 0x80.toByte
            assert(Bson.decode[BsonBytes](Span.from(invalidSubtype)).isFailure)
        }

        "decodes non-generic BSON binary subtypes" in {
            val uuidSubtype = Span.from(Array[Byte](
                32,
                0,
                0,
                0,
                0x05,
                'd'.toByte,
                'a'.toByte,
                't'.toByte,
                'a'.toByte,
                0,
                16,
                0,
                0,
                0,
                0x04,
                0,
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                9,
                10,
                11,
                12,
                13,
                14,
                15,
                0
            ))
            assert(Bson.decode[BsonBytes](uuidSubtype).isFailure)

            val oldSubtype = Span.from(Array[Byte](
                23,
                0,
                0,
                0,
                0x05,
                'd'.toByte,
                'a'.toByte,
                't'.toByte,
                'a'.toByte,
                0,
                7,
                0,
                0,
                0,
                0x02,
                3,
                0,
                0,
                0,
                1,
                2,
                3,
                0
            ))
            assert(Bson.decode[BsonBytes](oldSubtype).getOrThrow.data.toArray.toSeq == Seq[Byte](1, 2, 3))
        }

        "encodes arrays as BSON documents with numeric cstring keys" in {
            val bytes = Bson.encode(BsonInts(List(1, 2, 3))).toArray.toSeq

            assert(bytes == Seq[Byte](
                39,
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
                26,
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
                '1'.toByte,
                0,
                2,
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
            ))
            assert(Bson.decode[BsonInts](Span.from(bytes.toArray)).getOrThrow == BsonInts(List(1, 2, 3)))

            val invalidArrayKey = bytes.toArray
            invalidArrayKey(24) = '9'.toByte
            assert(Bson.decode[BsonInts](Span.from(invalidArrayKey)).isFailure)
        }

        "rejects malformed documents" in {
            val missingTerminator = Span.from(Array[Byte](5, 0, 0, 0, 1))
            assert(Bson.decode[BsonPerson](missingTerminator).isFailure)

            val trailing = Span.from(Array[Byte](5, 0, 0, 0, 0, 0))
            assert(Bson.decode[BsonPerson](trailing).isFailure)
        }

        "rejects element payloads that cross their containing document length" in {
            val stringOverrun = Span.from(Array[Byte](
                15,
                0,
                0,
                0,
                0x02,
                'n'.toByte,
                'a'.toByte,
                'm'.toByte,
                'e'.toByte,
                0,
                6,
                0,
                0,
                0,
                0,
                'A'.toByte,
                'l'.toByte,
                'i'.toByte,
                'c'.toByte,
                'e'.toByte,
                0
            ))
            assert(Bson.decode[BsonPerson](stringOverrun).isFailure)

            val binaryOverrun = Span.from(Array[Byte](
                15,
                0,
                0,
                0,
                0x05,
                'd'.toByte,
                'a'.toByte,
                't'.toByte,
                'a'.toByte,
                0,
                3,
                0,
                0,
                0,
                0,
                1,
                2,
                3,
                0
            ))
            assert(Bson.decode[BsonBytes](binaryOverrun).isFailure)

            val nestedOverrun = Span.from(Array[Byte](
                13,
                0,
                0,
                0,
                0x03,
                'p'.toByte,
                'e'.toByte,
                'r'.toByte,
                's'.toByte,
                'o'.toByte,
                'n'.toByte,
                0,
                0,
                5,
                0,
                0,
                0,
                0
            ))
            assert(Bson.decode[BsonNested](nestedOverrun).isFailure)
        }

        "rejects overflowing declared lengths as decode failures" in {
            val hugeString = Span.from(Array[Byte](
                15,
                0,
                0,
                0,
                0x02,
                'n'.toByte,
                'a'.toByte,
                'm'.toByte,
                'e'.toByte,
                0,
                -1,
                -1,
                -1,
                0x7f,
                0
            ))
            assert(Bson.decode[BsonPerson](hugeString).isFailure)

            val hugeBinary = Span.from(Array[Byte](
                15,
                0,
                0,
                0,
                0x05,
                'd'.toByte,
                'a'.toByte,
                't'.toByte,
                'a'.toByte,
                0,
                -1,
                -1,
                -1,
                0x7f,
                0
            ))
            assert(Bson.decode[BsonBytes](hugeBinary).isFailure)
        }

        "rejects malformed UTF-8 strings and field names" in {
            val badString = Span.from(Array[Byte](
                17,
                0,
                0,
                0,
                0x02,
                'n'.toByte,
                'a'.toByte,
                'm'.toByte,
                'e'.toByte,
                0,
                3,
                0,
                0,
                0,
                -61,
                40,
                0,
                0
            ))
            assert(Bson.decode[BsonPerson](badString).isFailure)

            val badFieldName = Span.from(Array[Byte](
                13, 0, 0, 0,
                0x10, -61, 40, 0,
                1, 0, 0, 0,
                0
            ))
            assert(Bson.decode[BsonPerson](badFieldName).isFailure)
        }

        "returns decode failures for non-finite doubles read as BigDecimal" in {
            def document(bits: Long): Span[Byte] =
                val bytes = Array[Byte](
                    20,
                    0,
                    0,
                    0,
                    0x01,
                    'v'.toByte,
                    'a'.toByte,
                    'l'.toByte,
                    'u'.toByte,
                    'e'.toByte,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                )
                var i = 0
                while i < 8 do
                    bytes(11 + i) = ((bits >>> (i * 8)) & 0xff).toByte
                    i += 1
                end while
                Span.from(bytes)
            end document

            assert(Bson.decode[BsonDecimalDouble](document(java.lang.Double.doubleToLongBits(Double.NaN))).isFailure)
            assert(Bson.decode[BsonDecimalDouble](document(java.lang.Double.doubleToLongBits(Double.PositiveInfinity))).isFailure)
            assert(Bson.decode[BsonDecimalDouble](document(java.lang.Double.doubleToLongBits(Double.NegativeInfinity))).isFailure)
        }

        "round trips time and duration fields" in {
            val value = BsonTimes(
                java.time.Instant.parse("2026-07-09T20:30:45.123Z"),
                java.time.Duration.ofSeconds(12, 345),
                kyo.Duration.fromNanos(123456789L)
            )

            assert(Bson.decode[BsonTimes](Bson.encode(value)).getOrThrow == value)
        }

        "rejects sub-millisecond instants" in {
            val value = BsonTimes(
                java.time.Instant.parse("2026-07-09T20:30:45.123456Z"),
                java.time.Duration.ZERO,
                kyo.Duration.fromNanos(0)
            )

            val ex = intercept[SchemaNotSerializableException](Bson.encode(value))
            assert(ex.detail.contains("millisecond precision"))
        }

        "round trips big numbers through BSON scalar-compatible values" in {
            val value = BsonDecimal(BigDecimal("123456789.0123456789"), BigInt(Long.MaxValue))

            assert(Bson.decode[BsonDecimal](Bson.encode(value)).getOrThrow == value)
        }

        "encodes BigDecimal as BSON Decimal128" in {
            val bytes = Bson.encode(BsonDecimalOnly(BigDecimal(1))).toArray.toSeq

            assert(bytes == Seq[Byte](
                28,
                0,
                0,
                0,
                0x13,
                'v'.toByte,
                'a'.toByte,
                'l'.toByte,
                'u'.toByte,
                'e'.toByte,
                0,
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                64,
                48,
                0
            ))
        }
    }

    // Non-String-key Dict round-trips through the real codec. Each entry is a two-field
    // {key, value} record.
    "dictSchema non-String-key Dict" - {

        "round-trips a non-String-key Dict" in {
            val holder  = MTIntStringDict(Dict(1 -> "one", 2 -> "two", 3 -> "three"))
            val decoded = Bson.decode[MTIntStringDict](Bson.encode(holder)).getOrThrow
            assert(decoded.d.get(1) == Maybe("one"))
            assert(decoded.d.get(2) == Maybe("two"))
            assert(decoded.d.get(3) == Maybe("three"))
            assert(decoded.d.size == 3)
        }

        "round-trips a non-String-key Dict with non-empty collection values" in {
            val holder  = MTIntChunkDict(Dict(1 -> Chunk("a", "b"), 2 -> Chunk("c")))
            val decoded = Bson.decode[MTIntChunkDict](Bson.encode(holder)).getOrThrow
            assert(decoded.d.get(1) == Maybe(Chunk("a", "b")))
            assert(decoded.d.get(2) == Maybe(Chunk("c")))
        }

    }
end BsonTest
