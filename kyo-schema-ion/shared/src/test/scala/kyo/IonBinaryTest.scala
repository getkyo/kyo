package kyo

class IonBinaryTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def bytes(value: Span[Byte]): Seq[Int] =
        value.toArray.toSeq.map(_ & 0xff)

    private def byteText(value: Span[Byte]): String =
        bytes(value).mkString(",")

    private def containsAscii(value: Span[Byte], text: String): Boolean =
        val haystack = value.toArray.toSeq.map(_ & 0xff)
        val needle   = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).toSeq.map(_ & 0xff)
        haystack.sliding(needle.size).contains(needle)
    end containsAscii

    private def sameValue[A](actual: A, expected: A)(using CanEqual[A, A]): Boolean =
        (actual, expected) match
            case (a: Array[?], b: Array[?]) =>
                java.util.Arrays.equals(a.asInstanceOf[Array[Byte]], b.asInstanceOf[Array[Byte]])
            case _ => actual == expected
    end sameValue

    private def roundTrip[A](value: A)(using Schema[A], CanEqual[A, A], kyo.test.AssertScope): Unit =
        assert(sameValue(IonBinary.decode[A](IonBinary.encode[A](value)).getOrThrow, value))
        assert(sameValue(Ion.decodeBinary[A](Ion.encodeBinary[A](value)).getOrThrow, value))
        val config = Ion.Config(format = Ion.Format.Binary)
        assert(sameValue(Ion.decode[A](Ion.encodeBytes[A](value, config), config).getOrThrow, value))
    end roundTrip

    "writer policy" - {

        "starts with the Ion 1.0 binary version marker" in {
            assert(bytes(IonBinary.encode(1)).take(4).mkString(",") == "224,1,0,234")
        }

        "emits stable bytes for scalar values" in {
            assert(byteText(IonBinary.encode[Option[Int]](None)) == "224,1,0,234,15")
            assert(byteText(IonBinary.encode(true)) == "224,1,0,234,17")
            assert(byteText(IonBinary.encode(false)) == "224,1,0,234,16")
            assert(byteText(IonBinary.encode(0)) == "224,1,0,234,32")
            assert(byteText(IonBinary.encode(7)) == "224,1,0,234,33,7")
            assert(byteText(IonBinary.encode(-7)) == "224,1,0,234,49,7")
            assert(byteText(IonBinary.encode("")) == "224,1,0,234,128")
            assert(byteText(IonBinary.encode(Span.from(Array[Byte](1, 2, 3)))) == "224,1,0,234,163,1,2,3")
        }

        "emits a local symbol table for structs" in {
            val encoded = bytes(IonBinary.encode(MTPerson("Alice", 30)))
            assert(encoded.take(4).mkString(",") == "224,1,0,234")
            assert(encoded.contains(0xee))
            assert(IonBinary.decode[MTPerson](Span.from(encoded.map(_.toByte).toArray)).getOrThrow == MTPerson("Alice", 30))
        }
    }

    "round trips" - {

        "representative schema values" in {
            roundTrip(MTPerson("Alice", 30))
            roundTrip(MTSmallTeam(MTPerson("Alice", 30), 5))
            roundTrip(MTPerson("Alice", 30) :: MTPerson("Bob", 25) :: Nil)
            roundTrip(Map("long key name" -> 1, "spaced key" -> 2))
            roundTrip(Span.from(Array[Byte](0, 1, -1)))
            roundTrip(BigInt("123456789012345678901234567890"))
            roundTrip(BigDecimal("12345.6789"))
            roundTrip(java.time.Instant.parse("2024-01-02T03:04:05.123456789Z"))
            roundTrip(java.time.Duration.ofSeconds(12, 345))
            roundTrip(kyo.Duration.fromNanos(123456789L))
            val shape: MTShape = MTCircle(2.5)
            roundTrip(shape)
        }

        "kyo.Duration uses the integer binary codec path" in {
            val duration = kyo.Duration.fromNanos(123456789L)
            assert(byteText(IonBinary.encode(duration)) == "224,1,0,234,36,7,91,205,21")
            assert(IonBinary.decode[kyo.Duration](IonBinary.encode(duration)).getOrThrow == duration)

            val config = Ion.Config(format = Ion.Format.Binary)
            assert(Ion.decode[kyo.Duration](Ion.encodeBytes(duration, config), config).getOrThrow == duration)
        }

        "no-config Ion helpers remain text with contextual binary Ion" in {
            given Ion = Ion(Ion.Config(format = Ion.Format.Binary))
            val value = MTPerson("Alice", 30)
            val text  = """{name:"Alice",age:30}"""
            assert(Ion.encode(value) == text)
            assert(new String(Ion.encodeBytes(value).toArray, java.nio.charset.StandardCharsets.UTF_8) == text)
            assert(Ion.decode[MTPerson](text).getOrThrow == value)
        }

        "binary string helpers reject" in {
            val config = Ion.Config(format = Ion.Format.Binary)
            assert(Result.catching[SchemaException](Ion.encode(
                MTPerson("Alice", 30),
                config
            )).failure.exists(_.isInstanceOf[SchemaNotSerializableException]))
            assert(Ion.decode[MTPerson]("{}", config).failure.exists(_.isInstanceOf[ParseException]))
        }

        "rejects an Instant whose proleptic year cannot fit the binary timestamp year field" in {
            val negativeYear = java.time.ZonedDateTime.of(-1, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant
            assert(Result.catching[SchemaException](
                IonBinary.encode(negativeYear)
            ).failure.exists(_.isInstanceOf[SchemaNotSerializableException]))
        }

        "configured binary limits are enforced" in {
            val value   = MTSmallTeam(MTPerson("Alice", 30), 5)
            val encoded = Ion.encodeBytes(value, Ion.Config(format = Ion.Format.Binary))
            assert(Ion.decode[MTSmallTeam](
                encoded,
                Ion.Config(format = Ion.Format.Binary, maxDepth = 1)
            ).failure.exists(_.isInstanceOf[LimitExceededException]))
            given Ion = Ion(Ion.Config(format = Ion.Format.Binary, maxDepth = 1))
            assert(Schema[MTSmallTeam].decode[Ion](encoded).failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        "configured annotation writing wraps binary output" in {
            val value          = IonAnnotated(7)
            val defaultBytes   = Ion.encodeBytes(value, Ion.Config(format = Ion.Format.Binary))
            val annotated      = Ion.Config(format = Ion.Format.Binary, annotationEmissionMode = Ion.AnnotationEmissionMode.Emit)
            val annotatedBytes = Ion.encodeBytes(value, annotated)

            assert(!containsAscii(defaultBytes, "kyo.IonRootAnnotation"))
            assert(!containsAscii(defaultBytes, "kyo.IonFieldAnnotation"))
            assert(containsAscii(annotatedBytes, "kyo.IonRootAnnotation"))
            assert(containsAscii(annotatedBytes, "kyo.IonFieldAnnotation"))
            assert(bytes(annotatedBytes).contains(0xee))
            val decoded = Ion.decode[IonAnnotated](annotatedBytes, annotated).getOrThrow
            assert(decoded == value)

            given Ion      = Ion(annotated)
            val codecBytes = summon[Schema[IonAnnotated]].encode[Ion](value)
            assert(bytes(codecBytes).contains(0xee))
            val codecDecoded = summon[Schema[IonAnnotated]].decode[Ion](codecBytes).getOrThrow
            assert(codecDecoded == value)
        }

        "configured annotation writing mirrors spec annotation scenarios in binary" in {
            val value          = IonSpecAnnotated(100)
            val annotated      = Ion.Config(format = Ion.Format.Binary, annotationEmissionMode = Ion.AnnotationEmissionMode.Emit)
            val annotatedBytes = Ion.encodeBytes(value, annotated)

            assert(containsAscii(annotatedBytes, "kyo.IonSpecCustomTypeAnnotation"))
            assert(containsAscii(annotatedBytes, "kyo.IonSpecInt32Annotation"))
            assert(containsAscii(annotatedBytes, "kyo.IonSpecDegreesAnnotation"))
            assert(containsAscii(annotatedBytes, "kyo.IonSpecCelsiusAnnotation"))
            assert(bytes(annotatedBytes).count(_ == 0xee) >= 2)
            assert(Ion.decode[IonSpecAnnotated](annotatedBytes, annotated).getOrThrow == value)
        }

        "configured annotation writing pins the concrete symbol for a nested class marker and a case object marker in binary" in {
            val value          = IonNestedAnnotated(7)
            val annotated      = Ion.Config(format = Ion.Format.Binary, annotationEmissionMode = Ion.AnnotationEmissionMode.Emit)
            val annotatedBytes = Ion.encodeBytes(value, annotated)

            assert(containsAscii(annotatedBytes, "kyo.IonAnnotationMarkers$NestedRootMarker"))
            assert(containsAscii(annotatedBytes, "kyo.IonCaseFieldMarker"))
            assert(Ion.decode[IonNestedAnnotated](annotatedBytes, annotated).getOrThrow == value)
        }

        "decode surfaces a non-serializable schema as Result.Panic, not an uncaught throw" in {
            val encoded = IonBinary.encode(1)
            // A structural type has no given, so Schema[A] builds the navigation-only schema, which has no serialization.
            val result =
                given Schema[Record.~["name", String]] = Schema[Record.~["name", String]]
                IonBinary.decode[Record.~["name", String]](encoded)
            result match
                case Result.Panic(ex: SchemaNotSerializableException) =>
                    assert(ex.getMessage.contains("does not have serialization"))
                case other =>
                    fail(s"Expected Result.Panic(SchemaNotSerializableException) but got $other")
            end match
        }

        "encodeBytes and decodeBytes are direct aliases of encode and decode" in {
            val value   = MTPerson("Alice", 30)
            val encoded = IonBinary.encodeBytes(value)
            assert(CodecTestSupport.sameBytes(encoded, IonBinary.encode(value)))
            assert(IonBinary.decodeBytes[MTPerson](encoded).getOrThrow == value)
        }
    }

    "structure values" - {

        "materializes binary values through readStructure" in {
            val fields = Chunk.newBuilder[(String, Structure.Value)]
            fields += "data" -> Structure.Value.Bytes(Span.from(Array[Byte](1, 2)))
            fields += "at"   -> Structure.Value.Instant(java.time.Instant.parse("2024-01-02T03:04:05Z"))
            val value  = Structure.Value.Record(fields.result())
            val schema = summon[Schema[Structure.Value]]
            val bytes  = schema.encode[IonBinary](value)
            assert(schema.decode[IonBinary](bytes).getOrThrow == value)
        }
    }

    // Non-String-key Dict round-trips through the real codec. Each entry is a two-field
    // {key, value} record.
    "dictSchema non-String-key Dict" - {

        "round-trips a non-String-key Dict" in {
            val holder  = MTIntStringDict(Dict(1 -> "one", 2 -> "two", 3 -> "three"))
            val decoded = IonBinary.decode[MTIntStringDict](IonBinary.encode(holder)).getOrThrow
            assert(decoded.d.get(1) == Maybe("one"))
            assert(decoded.d.get(2) == Maybe("two"))
            assert(decoded.d.get(3) == Maybe("three"))
            assert(decoded.d.size == 3)
        }

        "round-trips a non-String-key Dict with non-empty collection values" in {
            val holder  = MTIntChunkDict(Dict(1 -> Chunk("a", "b"), 2 -> Chunk("c")))
            val decoded = IonBinary.decode[MTIntChunkDict](IonBinary.encode(holder)).getOrThrow
            assert(decoded.d.get(1) == Maybe(Chunk("a", "b")))
            assert(decoded.d.get(2) == Maybe(Chunk("c")))
        }

    }

    // OrderedDict Schema given: insertion-order round-trip.
    "OrderedDict Schema given" - {

        "OrderedDict[String, V] field preserves insertion order across encode/decode" in {
            val holder =
                MTOrderedDictConfig(OrderedDict("zeta" -> 30, "alpha" -> 3, "mike" -> 8080, "bravo" -> 5, "yankee" -> 100, "delta" -> 42))
            val decoded = IonBinary.decode[MTOrderedDictConfig](IonBinary.encode(holder)).getOrThrow
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
            val withOmit = omit.encode[IonBinary](value)
            assert(
                withOmit.size < plain.encode[IonBinary](value).size,
                "empty String-key OrderedDict must be dropped from the wire under omitEmptyCollections"
            )
            val decoded = omit.decode[IonBinary](withOmit).getOrThrow
            assert(decoded.name == value.name && decoded.count == value.count)
            assert(decoded.settings.is(value.settings))
        }

        "empty Dict[Int, V] field (non-String key) is omitted from the wire and round-trips" in {
            val plain    = Schema[MTIntStringDictRecord]
            val omit     = Schema[MTIntStringDictRecord].omitEmptyCollections
            val value    = MTIntStringDictRecord("alice", Dict.empty[Int, String], 7)
            val withOmit = omit.encode[IonBinary](value)
            assert(
                withOmit.size < plain.encode[IonBinary](value).size,
                "empty non-String-key Dict must be dropped from the wire under omitEmptyCollections"
            )
            val decoded = omit.decode[IonBinary](withOmit).getOrThrow
            assert(decoded.name == value.name && decoded.count == value.count)
            assert(decoded.byId.is(value.byId))
        }

    }

    "an integer beyond a Long read into an Int fails naming the value it read" in {
        val big = BigInt(2).pow(70)
        IonBinary.decode[Int](IonBinary.encode[BigInt](big)) match
            case Result.Failure(e: RangeException) =>
                val stated = e.getMessage.linesIterator.find(_.contains("out of range")).getOrElse(e.getMessage)
                assert(stated.contains(big.toString), stated)
            case other => fail(s"expected a RangeException, got $other")
        end match
    }

    private def wirePin[A](value: A, wire: String, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        wireDecodes(value, wire, schema)
        wireWrites(value, wire, schema)
    end wirePin

    private def wireDecodes[A](value: A, wire: String, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        given Schema[A] = schema
        assert(IonBinary.decode[A](CodecTestSupport.unhex(wire)) == Result.succeed(value))
    end wireDecodes

    private def wireWrites[A](value: A, wire: String, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        given Schema[A] = schema
        assert(CodecTestSupport.hex(IonBinary.encode(value)) == wire)
    end wireWrites

    "wire pins" - {
        "a record with a collection and a nested record" in {
            wirePin(
                WCValues.record,
                "e00100eaeeacab8183dea787bea483616765866163746976658573636f7265847461677385696e6e65728178856c6162656cdea38483416e6e8a21298b118c4840040000000000008db4816181628ed78f21039082696e",
                summon[Schema[WCRecord]]
            )
        }
        "fields renamed and aliased by annotation: the renamed-last order decodes and declaration order is written" in {
            wireDecodes(
                WCValues.renamed,
                "e00100eaee9b9a8183de9687be9388686f6d654369747989757365725f6e616d65dd8a864c6973626f6e8b83616e6e",
                summon[Schema[WCRenamed]]
            )
            wireWrites(
                WCValues.renamed,
                "e00100eaee9b9a8183de9687be9389757365725f6e616d6588686f6d6543697479dd8a83616e6e8b864c6973626f6e",
                summon[Schema[WCRenamed]]
            )
        }
        "fields under a naming convention, one renamed by annotation" in {
            wirePin(WCValues.cased, "e00100eaee96958183de9187be8e8a66697273745f6e616d65824944d88a83416e6e8b2109", summon[Schema[WCCased]])
        }
        "a flattened record: the nested form decodes and the flat form is written" in {
            wireDecodes(
                WCValues.person,
                "e00100eaee9f9e8183de9a87be97876164647265737386737472656574877a6970436f6465de988483416e6e8ade908b874d61696e2053748c853937323031",
                summon[Schema[WCPerson]]
            )
            wireWrites(
                WCValues.person,
                "e00100eaee97968183de9287be8f86737472656574877a6970436f6465de958483416e6e8a874d61696e2053748b853937323031",
                summon[Schema[WCPerson]]
            )
        }
        "a flattened record under a naming convention: the nested form decodes" in {
            wireDecodes(
                WCValues.personC,
                "e00100eaeeacab8183dea787bea48966756c6c5f6e616d658c686f6d655f6164647265737388636974794e616d65835a4950de9d8a87416e6e204c65658bde918c88506f72746c616e648d853937323031",
                summon[Schema[WCPersonCased]]
            )
        }
        "a variant under the wrapper form" in {
            wirePin(
                WCValues.circle: WCShape,
                "e00100eaee98978183de9387be90885743436972636c6586726164697573dc8ada8b483ff8000000000000",
                summon[Schema[WCShape]]
            )
        }
        "a case-object variant under the wrapper form" in {
            wirePin(WCValues.empty: WCShape, "e00100eaee8e8d8183da87b8875743456d707479d28ad0", summon[Schema[WCShape]])
        }
        "a variant under a discriminator" in {
            wirePin(
                WCValues.circle: WCShape,
                "e00100eaee93928183de8e87bc847479706586726164697573de948a885743436972636c658b483ff8000000000000",
                WCShapes.discriminated
            )
        }
        "a case-object variant under a discriminator" in {
            wirePin(WCValues.empty: WCShape, "e00100eaee8b8a8183d787b58474797065d98a875743456d707479", WCShapes.discriminated)
        }
        "a variant under the adjacent form" in {
            wirePin(
                WCValues.square: WCShape,
                "e00100eaee8f8e8183db87b9817481638473696465de8f8a8857435371756172658bd38c2104",
                WCShapes.adjacent
            )
        }
        "a variant under tupleTagged" in {
            wirePin(WCValues.square: WCShape, "e00100eaee8b8a8183d787b58473696465bd885743537175617265d38a2104", WCShapes.tupleTagged)
        }
        "a variant under tupleFlat" in {
            wirePin(WCValues.square: WCShape, "e00100eabb8857435371756172652104", WCShapes.tupleFlat)
        }
        "a variant under untagged" in {
            wirePin(WCValues.square: WCShape, "e00100eaee8b8a8183d787b58473696465d38a2104", WCShapes.untagged)
        }
        "a variant under a naming convention with an alias" in {
            wirePin(
                WCValues.circle: WCShape,
                "e00100eaee93928183de8e87bc847479706586726164697573de958a8977635f636972636c658b483ff8000000000000",
                WCShapes.snake
            )
        }
        "a renamed variant under an annotated discriminator" in {
            wirePin(WCValues.opened: WCEvent, "e00100eaee8e8d8183da87b8846b696e64826964db8a866f70656e65648b2101", summon[Schema[WCEvent]])
        }
        "a variant under an annotated discriminator" in {
            wirePin(
                WCValues.closed: WCEvent,
                "e00100eaee97968183de9287be8f846b696e6482696486726561736f6ede938a885743436c6f7365648b21028c84646f6e65",
                summon[Schema[WCEvent]]
            )
        }
        "a record of maps of every key kind: the pair form decodes and the object form is written" in {
            wireDecodes(
                WCValues.maps,
                "e00100eaeec7c68183dec287bebf8662794e616d6581618162856279496e74836b65798576616c75658662794c6f6e67866279436861728862795265636f72648178856c6162656c8462794964dec78ad68b21018c21028dbe92d88e21018f836f6e65d88e21028f8374776f90b6d58e210a8f1191b7d68e81788f210192bcdb8ed693210194816b8f210595b9d88e836964318f2107",
                summon[Schema[WCMaps]]
            )
            wireWrites(
                WCValues.maps,
                "e00100eaeecbca8183dec687bec38662794e616d6581618162856279496e74836b65798576616c75658662794c6f6e67866279436861728862795265636f72648178856c6162656c846279496483696431dec18ad68b21018c21028dbe92d88e21018f836f6e65d88e21028f8374776f90b6d58e210a8f1191b7d68e81788f210192bcdb8ed693210194816b8f210595d3962107",
                summon[Schema[WCMaps]]
            )
        }
        "a map keyed by String" in {
            wirePin(WCValues.mapByName, "e00100eaee8c8b8183d887b6816d81618162d88ad68b21018c2102", summon[Schema[WCMapByName]])
        }
        "a map keyed by Int" in {
            wirePin(
                WCValues.mapByInt,
                "e00100eaee93928183de8e87bc816d836b65798576616c7565de958abe92d88b21018c836f6e65d88b21028c8374776f",
                summon[Schema[WCMapByInt]]
            )
        }
        "a map keyed by Long" in {
            wirePin(WCValues.mapByLong, "e00100eaee93928183de8e87bc816d836b65798576616c7565d88ab6d58b210a8c11", summon[Schema[WCMapByLong]])
        }
        "a map keyed by Char" in {
            wirePin(
                WCValues.mapByChar,
                "e00100eaee93928183de8e87bc816d836b65798576616c7565d98ab7d68b81788c2101",
                summon[Schema[WCMapByChar]]
            )
        }
        "a map keyed by a record" in {
            wirePin(
                WCValues.mapByRecord,
                "e00100eaee9c9b8183de9787be94816d836b65798178856c6162656c8576616c7565de8e8abcdb8bd68c21018d816b8e2105",
                summon[Schema[WCMapByRecord]]
            )
        }
        "a map keyed by a string-backed type: the pair form decodes and the object form is written" in {
            wireDecodes(
                WCValues.mapById,
                "e00100eaee93928183de8e87bc816d836b65798576616c7565de958abe92d88b836964318c2107d88b836964328c2108",
                summon[Schema[WCMapById]]
            )
            wireWrites(WCValues.mapById, "e00100eaee908f8183dc87ba816d8369643183696432d88ad68b21078c2108", summon[Schema[WCMapById]])
        }
        "fields holding their defaults" in {
            wirePin(
                WCValues.defaultsAll,
                "e00100eaee99988183de9487be9185636f756e74856c6162656c8473697a65dc8481648a21078b81788c2103",
                summon[Schema[WCDefaults]]
            )
        }
        "fields overriding their defaults: the bytes written" in {
            wireWrites(
                WCValues.defaultsSet,
                "e00100eaee9e9d8183de9987be9685636f756e74856c6162656c846e6f74658473697a65de8e8481648a21018b81798c816e8d0f",
                summon[Schema[WCDefaults]]
            )
        }
        "present optional fields" in {
            wirePin(
                WCValues.maybePresent,
                "e00100eaee98978183de9387be908161816281638178856c6162656c8164de918a21018b81738cd68d21028e81638f2104",
                summon[Schema[WCMaybe]]
            )
        }
        "absent optional fields" in {
            wirePin(WCValues.maybeAbsent, "e00100ead0", summon[Schema[WCMaybe]])
        }
        "absent optional fields under omitNone" in {
            wirePin(WCValues.maybeAbsent, "e00100ead0", WCMaybes.omitNone)
        }
        "a numbered variant under a discriminator" in {
            wirePin(WCNumA(5): WCNumbered, "e00100eaee8d8c8183d987b784747970658178d68a21018b2105", summon[Schema[WCNumbered]])
        }
        "a second numbered variant under a discriminator" in {
            wirePin(WCNumB("b"): WCNumbered, "e00100eaee8d8c8183d987b784747970658173d68a21028b8162", summon[Schema[WCNumbered]])
        }
        "a numbered variant under the wrapper form, written by name" in {
            wirePin(
                WCWrapA(3): WCNumberedWrapped,
                "e00100eaee908f8183dc87ba87574357726170418178d58ad38b2103",
                summon[Schema[WCNumberedWrapped]]
            )
        }
        "a numbered case object under the wrapper form, written by name" in {
            wirePin(WCWrapB: WCNumberedWrapped, "e00100eaee8e8d8183da87b88757435772617042d28ad0", summon[Schema[WCNumberedWrapped]])
        }
        "a tagOnly variant" in {
            wirePin(WCLow: WCLevel, "e00100ea8557434c6f77", summon[Schema[WCLevel]])
        }
        "a renamed tagOnly variant" in {
            wirePin(WCHigh: WCLevel, "e00100ea826869", summon[Schema[WCLevel]])
        }
        "a known variant beside a catch-all" in {
            wirePin(WCKnown(1): WCOpen, "e00100eaee8d8c8183d987b784747970658178dc8a8757434b6e6f776e8b2101", summon[Schema[WCOpen]])
        }
        "a catch-all variant" in {
            wirePin(WCValues.other: WCOpen, "e00100eaee8d8c8183d987b784747970658179d88a837a7a7a8b2101", summon[Schema[WCOpen]])
        }
    }

    "an Absent field whose default is Present round-trips as Absent" in {
        val decoded = IonBinary.decode[WCDefaults](IonBinary.encode(WCValues.defaultsSet))
        assert(decoded == Result.succeed(WCValues.defaultsSet), s"decoded $decoded")
    }

end IonBinaryTest
