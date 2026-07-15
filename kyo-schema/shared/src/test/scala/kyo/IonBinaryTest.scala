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
            val result =
                given Schema[Int] = Schema[Int]
                IonBinary.decode[Int](encoded)
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

end IonBinaryTest
