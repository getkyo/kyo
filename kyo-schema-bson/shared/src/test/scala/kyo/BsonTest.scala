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
            // A structural type has no given, so Schema[A] builds the navigation-only schema, which has no serialization.
            val result =
                given Schema[Record.~["name", String]] = Schema[Record.~["name", String]]
                Bson.decode[Record.~["name", String]](encoded)
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

    // OrderedDict Schema given: insertion-order round-trip.
    "OrderedDict Schema given" - {

        "OrderedDict[String, V] field preserves insertion order across encode/decode" in {
            val holder =
                MTOrderedDictConfig(OrderedDict("zeta" -> 30, "alpha" -> 3, "mike" -> 8080, "bravo" -> 5, "yankee" -> 100, "delta" -> 42))
            val decoded = Bson.decode[MTOrderedDictConfig](Bson.encode(holder)).getOrThrow
            assert(decoded.settings.toChunk.map(_._1) == Chunk("zeta", "alpha", "mike", "bravo", "yankee", "delta"))
        }

    }

    // omitEmptyCollections on OrderedDict/Dict fields: an empty field must be dropped from the
    // wire (fewer bytes than the same value encoded without the omit policy) and round-trip back
    // to the empty value, matching Map/Chunk/List/Vector/Set/Seq behavior.
    "omitEmptyCollections on OrderedDict/Dict fields" - {

        "empty OrderedDict[String, V] field is omitted from the wire and round-trips" in {
            val plain    = Schema[MTOrderedDictRecord]
            val omit     = Schema[MTOrderedDictRecord].omitEmptyCollections
            val value    = MTOrderedDictRecord("alice", OrderedDict.empty[String, Int], 7)
            val withOmit = omit.encode[Bson](value)
            assert(
                withOmit.size < plain.encode[Bson](value).size,
                "empty String-key OrderedDict must be dropped from the wire under omitEmptyCollections"
            )
            val decoded = omit.decode[Bson](withOmit).getOrThrow
            assert(decoded.name == value.name && decoded.count == value.count)
            assert(decoded.settings.is(value.settings))
        }

        "empty Dict[Int, V] field (non-String key) is omitted from the wire and round-trips" in {
            val plain    = Schema[MTIntStringDictRecord]
            val omit     = Schema[MTIntStringDictRecord].omitEmptyCollections
            val value    = MTIntStringDictRecord("alice", Dict.empty[Int, String], 7)
            val withOmit = omit.encode[Bson](value)
            assert(
                withOmit.size < plain.encode[Bson](value).size,
                "empty non-String-key Dict must be dropped from the wire under omitEmptyCollections"
            )
            val decoded = omit.decode[Bson](withOmit).getOrThrow
            assert(decoded.name == value.name && decoded.count == value.count)
            assert(decoded.byId.is(value.byId))
        }

    }

    private def wirePin[A](value: A, wire: String, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        wireDecodes(value, wire, schema)
        wireWrites(value, wire, schema)
    end wirePin

    private def wireDecodes[A](value: A, wire: String, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        given Schema[A]          = schema
        given CanEqual[Any, Any] = CanEqual.derived
        assert(Bson.decode[A](CodecTestSupport.unhex(wire)) == Result.succeed(value))
    end wireDecodes

    private def wireWrites[A](value: A, wire: String, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        given Schema[A] = schema
        assert(CodecTestSupport.hex(Bson.encode(value)) == wire)
    end wireWrites

    private def wireRefuses[E <: Throwable](using ConcreteTag[E])[A](value: A, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        given Schema[A] = schema
        assert(Result.catching[E](Bson.encode(value)).isFailure)
    end wireRefuses

    "wire pins" - {
        "a record with a collection and a nested record" in {
            wirePin(
                WCValues.record,
                "72000000026e616d650004000000416e6e001061676500290000000861637469766500010173636f7265000000000000000440047461677300170000000230000200000061000231000200000062000003696e6e6572001a00000010780003000000026c6162656c0003000000696e000000",
                summon[Schema[WCRecord]]
            )
        }
        "fields renamed and aliased by annotation: the renamed-last order decodes and declaration order is written" in {
            wireDecodes(
                WCValues.renamed,
                "2d00000002686f6d654369747900070000004c6973626f6e0002757365725f6e616d650004000000616e6e0000",
                summon[Schema[WCRenamed]]
            )
            wireWrites(
                WCValues.renamed,
                "2d00000002757365725f6e616d650004000000616e6e0002686f6d654369747900070000004c6973626f6e0000",
                summon[Schema[WCRenamed]]
            )
        }
        "fields under a naming convention, one renamed by annotation" in {
            wirePin(WCValues.cased, "210000000266697273745f6e616d650004000000416e6e00104944000900000000", summon[Schema[WCCased]])
        }
        "a flattened record: the nested form decodes and the flat form is written" in {
            wireDecodes(
                WCValues.person,
                "48000000026e616d650004000000416e6e000361646472657373002c0000000273747265657400080000004d61696e20537400027a6970436f646500060000003937323031000000",
                summon[Schema[WCPerson]]
            )
            wireWrites(
                WCValues.person,
                "3a000000026e616d650004000000416e6e000273747265657400080000004d61696e20537400027a6970436f6465000600000039373230310000",
                summon[Schema[WCPerson]]
            )
        }
        "a flattened record under a naming convention: the nested form decodes" in {
            wireDecodes(
                WCValues.personC,
                "550000000266756c6c5f6e616d650008000000416e6e204c65650003686f6d655f61646472657373002b00000002636974794e616d650009000000506f72746c616e6400025a495000060000003937323031000000",
                summon[Schema[WCPersonCased]]
            )
        }
        "a variant under the wrapper form" in {
            wirePin(
                WCValues.circle: WCShape,
                "24000000035743436972636c6500150000000172616469757300000000000000f83f0000",
                summon[Schema[WCShape]]
            )
        }
        "a case-object variant under the wrapper form" in {
            wirePin(WCValues.empty: WCShape, "13000000035743456d70747900050000000000", summon[Schema[WCShape]])
        }
        "a variant under a discriminator" in {
            wirePin(
                WCValues.circle: WCShape,
                "28000000027479706500090000005743436972636c65000172616469757300000000000000f83f00",
                WCShapes.discriminated
            )
        }
        "a case-object variant under a discriminator" in {
            wirePin(WCValues.empty: WCShape, "17000000027479706500080000005743456d7074790000", WCShapes.discriminated)
        }
        "a variant under the adjacent form" in {
            wirePin(
                WCValues.square: WCShape,
                "27000000027400090000005743537175617265000363000f000000107369646500040000000000",
                WCShapes.adjacent
            )
        }
        "a variant under tupleTagged is refused" in {
            wireRefuses[RepresentationUnsupportedException](WCValues.square: WCShape, WCShapes.tupleTagged)
        }
        "a variant under tupleFlat is refused" in {
            wireRefuses[RepresentationUnsupportedException](WCValues.square: WCShape, WCShapes.tupleFlat)
        }
        "a variant under untagged is refused" in {
            wireRefuses[RepresentationUnsupportedException](WCValues.square: WCShape, WCShapes.untagged)
        }
        "a variant under a naming convention with an alias" in {
            wirePin(
                WCValues.circle: WCShape,
                "290000000274797065000a00000077635f636972636c65000172616469757300000000000000f83f00",
                WCShapes.snake
            )
        }
        "a renamed variant under an annotated discriminator" in {
            wirePin(WCValues.opened: WCEvent, "1e000000026b696e6400070000006f70656e656400106964000100000000", summon[Schema[WCEvent]])
        }
        "a variant under an annotated discriminator" in {
            wirePin(
                WCValues.closed: WCEvent,
                "31000000026b696e6400090000005743436c6f73656400106964000200000002726561736f6e0005000000646f6e650000",
                summon[Schema[WCEvent]]
            )
        }
        "a record of maps of every key kind: the pair form decodes and the object form is written" in {
            wireDecodes(
                WCValues.maps,
                "2c0100000362794e616d650013000000106100010000001062000200000000046279496e7400450000000330001d000000106b657900010000000276616c756500040000006f6e6500000331001d000000106b657900020000000276616c7565000400000074776f0000000462794c6f6e6700220000000330001a000000126b6579000a000000000000000876616c7565000100000462794368617200230000000330001b000000026b6579000200000078001076616c7565000100000000000462795265636f726400360000000330002e000000036b6579001900000010780001000000026c6162656c00020000006b00001076616c756500050000000000046279496400250000000330001d000000026b65790004000000696431001076616c75650007000000000000",
                summon[Schema[WCMaps]]
            )
            wireWrites(
                WCValues.maps,
                "150100000362794e616d650013000000106100010000001062000200000000046279496e7400450000000330001d000000106b657900010000000276616c756500040000006f6e6500000331001d000000106b657900020000000276616c7565000400000074776f0000000462794c6f6e6700220000000330001a000000126b6579000a000000000000000876616c7565000100000462794368617200230000000330001b000000026b6579000200000078001076616c7565000100000000000462795265636f726400360000000330002e000000036b6579001900000010780001000000026c6162656c00020000006b00001076616c7565000500000000000362794964000e0000001069643100070000000000",
                summon[Schema[WCMaps]]
            )
        }
        "a map keyed by String" in {
            wirePin(WCValues.mapByName, "1b000000036d001300000010610001000000106200020000000000", summon[Schema[WCMapByName]])
        }
        "a map keyed by Int" in {
            wirePin(
                WCValues.mapByInt,
                "4d000000046d00450000000330001d000000106b657900010000000276616c756500040000006f6e6500000331001d000000106b657900020000000276616c7565000400000074776f00000000",
                summon[Schema[WCMapByInt]]
            )
        }
        "a map keyed by Long" in {
            wirePin(
                WCValues.mapByLong,
                "2a000000046d00220000000330001a000000126b6579000a000000000000000876616c75650001000000",
                summon[Schema[WCMapByLong]]
            )
        }
        "a map keyed by Char" in {
            wirePin(
                WCValues.mapByChar,
                "2b000000046d00230000000330001b000000026b6579000200000078001076616c75650001000000000000",
                summon[Schema[WCMapByChar]]
            )
        }
        "a map keyed by a record" in {
            wirePin(
                WCValues.mapByRecord,
                "3e000000046d00360000000330002e000000036b6579001900000010780001000000026c6162656c00020000006b00001076616c75650005000000000000",
                summon[Schema[WCMapByRecord]]
            )
        }
        "a map keyed by a string-backed type: the pair form decodes and the object form is written" in {
            wireDecodes(
                WCValues.mapById,
                "4d000000046d00450000000330001d000000026b65790004000000696431001076616c75650007000000000331001d000000026b65790004000000696432001076616c75650008000000000000",
                summon[Schema[WCMapById]]
            )
            wireWrites(WCValues.mapById, "1f000000036d00170000001069643100070000001069643200080000000000", summon[Schema[WCMapById]])
        }
        "fields holding their defaults" in {
            wirePin(
                WCValues.defaultsAll,
                "33000000026e616d650002000000640010636f756e740007000000026c6162656c000200000078001073697a65000300000000",
                summon[Schema[WCDefaults]]
            )
        }
        "fields overriding their defaults: the bytes written" in {
            wireWrites(
                WCValues.defaultsSet,
                "3b000000026e616d650002000000640010636f756e740001000000026c6162656c00020000007900026e6f746500020000006e000a73697a650000",
                summon[Schema[WCDefaults]]
            )
        }
        "present optional fields" in {
            wirePin(
                WCValues.maybePresent,
                "38000000106100010000000262000200000073000363001900000010780002000000026c6162656c00020000006300001064000400000000",
                summon[Schema[WCMaybe]]
            )
        }
        "absent optional fields" in {
            wirePin(WCValues.maybeAbsent, "0500000000", summon[Schema[WCMaybe]])
        }
        "absent optional fields under omitNone" in {
            wirePin(WCValues.maybeAbsent, "0500000000", WCMaybes.omitNone)
        }
        "a numbered variant under a discriminator" in {
            wirePin(WCNumA(5): WCNumbered, "16000000107479706500010000001078000500000000", summon[Schema[WCNumbered]])
        }
        "a second numbered variant under a discriminator" in {
            wirePin(WCNumB("b"): WCNumbered, "180000001074797065000200000002730002000000620000", summon[Schema[WCNumbered]])
        }
        "a numbered variant under the wrapper form, written by name" in {
            wirePin(
                WCWrapA(3): WCNumberedWrapped,
                "1a0000000357435772617041000c000000107800030000000000",
                summon[Schema[WCNumberedWrapped]]
            )
        }
        "a numbered case object under the wrapper form, written by name" in {
            wirePin(WCWrapB: WCNumberedWrapped, "13000000035743577261704200050000000000", summon[Schema[WCNumberedWrapped]])
        }
        "a tagOnly variant is refused" in {
            wireRefuses[RepresentationUnsupportedException](WCLow: WCLevel, summon[Schema[WCLevel]])
        }
        "a renamed tagOnly variant is refused" in {
            wireRefuses[RepresentationUnsupportedException](WCHigh: WCLevel, summon[Schema[WCLevel]])
        }
        "a known variant beside a catch-all" in {
            wirePin(WCKnown(1): WCOpen, "1e0000000274797065000800000057434b6e6f776e001078000100000000", summon[Schema[WCOpen]])
        }
        "a catch-all variant" in {
            wirePin(WCValues.other: WCOpen, "1a000000027479706500040000007a7a7a001079000100000000", summon[Schema[WCOpen]])
        }
    }

    "an Absent field whose default is Present round-trips as Absent" in {
        given CanEqual[Any, Any] = CanEqual.derived
        val decoded              = Bson.decode[WCDefaults](Bson.encode(WCValues.defaultsSet))
        assert(decoded == Result.succeed(WCValues.defaultsSet), s"decoded $decoded")
    }

    "a Short out of range or with a fraction fails with one exception type, read directly or from a captured value" in {
        val (direct, captured) = CodecTestSupport.shortNarrowing[Bson]
        assert(direct == Chunk("RangeException", "TypeMismatchException"), direct.toString)
        assert(captured == direct, s"direct: $direct, captured: $captured")
    }

    "a record cut off before its end is truncated input" in {
        val kind = CodecTestSupport.truncation[Bson]
        assert(kind == "TruncatedInputException", kind)
    }
end BsonTest
