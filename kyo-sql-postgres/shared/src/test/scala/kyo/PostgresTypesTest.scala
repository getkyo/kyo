package kyo

import kyo.internal.postgres.BoundParam
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.PostgresRowCodec
import kyo.internal.postgres.PostgresRowReader
import kyo.internal.postgres.TypeRegistry

/** Unit tests for [[PostgresTypes]], the column codecs for the types only PostgreSQL has.
  *
  * Every codec here goes out through the extension channel, so each test asserts what the payload carries: the type name PostgreSQL will
  * resolve an OID for, and the bytes in PostgreSQL's own wire form for that type. The write goes through the real
  * [[kyo.internal.postgres.PostgresParamWriter]] and the read through the real [[kyo.internal.postgres.PostgresRowReader]], which is what
  * makes the byte assertions here the bytes the server receives. The rejection half, what happens when one of these reaches a MySQL
  * backend, lives in `kyo/SqlSchemaWriterExtensionRejectionTest.scala`.
  */
class PostgresTypesTest extends Test:

    /** The OIDs the session resolves for the extension types this suite writes.
      *
      * A payload names its type; the writer turns that name into an OID, either from its builtin table or from the session registry. Going
      * through the real writer means the name is only observable as the OID it resolved to, so the two are mapped back here and every
      * assertion below stays in type-name terms.
      */
    private val customOids: Map[String, Int] = Map("hstore" -> 90001, "mood" -> 90002, "point" -> 90003)

    /** The six builtin range OIDs, which `PostgresParamWriter` resolves without a registry entry. */
    private val rangeOids: Map[String, Int] = Map(
        "int4range" -> 3904,
        "int8range" -> 3926,
        "numrange"  -> 3906,
        "daterange" -> 3912,
        "tsrange"   -> 3908,
        "tstzrange" -> 3910
    )

    private val registry: TypeRegistry = TypeRegistry(customOids)

    private val nameOf: Map[Int, String] = (customOids ++ rangeOids).map((name, oid) => oid -> name)

    /** The bind parameters `column` emits for `value`, through the real PostgreSQL writer. */
    private def paramsOf[A](column: SqlSchema.Column[A], value: A): Chunk[BoundParam[?]] =
        val writer = new PostgresParamWriter(registry)
        column.write(value, writer)
        writer.params
    end paramsOf

    /** The single extension payload `value` wrote, as a (typeName, bytes) pair. */
    private def payload[A](value: A)(using column: SqlSchema.Column[A], as: kyo.test.AssertScope): (String, Chunk[Byte]) =
        val params = paramsOf(column, value)
        assert(params.size == 1, s"expected one extension column, got ${params.size}")
        val bytes = params(0).encoded match
            case Maybe.Present(b) => Chunk.from(b.toArray)
            case Maybe.Absent     => fail("expected the extension param to carry bytes")
        (nameOf.getOrElse(params(0).encoder.oid, s"oid ${params(0).encoder.oid}"), bytes)
    end payload

    /** Reads `bytes` back as a single column arriving in `format`, through the real PostgreSQL row reader. */
    private def readColumn[A](bytes: Seq[Byte], format: SqlCodec.Format)(using column: SqlSchema.Column[A]): A =
        val row = new SqlRow(
            Chunk(Maybe.Present(Span.from(bytes.toArray))),
            Chunk(SqlRow.Column("col", 0)),
            PostgresRowCodec(format)
        )
        column.read(new PostgresRowReader(row, format))
    end readColumn

    /** Writes `value` and reads it back from the payload it produced. */
    private def roundTrip[A](value: A)(using column: SqlSchema.Column[A], as: kyo.test.AssertScope): A =
        val params = paramsOf(column, value)
        assert(params.size == 1, s"expected one column, got ${params.size}")
        val bytes = params(0).encoded match
            case Maybe.Present(b) => b.toArray.toSeq
            case Maybe.Absent     => fail("expected the param to carry bytes")
        readColumn(bytes, params(0).encoder.format)
    end roundTrip

    /** A big-endian Int32 as a byte sequence, the length prefix both wire forms here use. */
    private def int32(value: Int): Seq[Byte] =
        Seq(((value >>> 24) & 0xff).toByte, ((value >>> 16) & 0xff).toByte, ((value >>> 8) & 0xff).toByte, (value & 0xff).toByte)

    // ── HStore ────────────────────────────────────────────────────────────────

    "HStore writes the hstore binary form: count, then length-prefixed keys and values" in {
        val (typeName, bytes) = payload(PostgresTypes.HStore(Map("k" -> Maybe("v"))))
        assert(typeName == "hstore")
        val expected = int32(1) ++ int32(1) ++ Seq('k'.toByte) ++ int32(1) ++ Seq('v'.toByte)
        assert(bytes.toSeq == expected)
    }

    "HStore writes a NULL value as a length of -1" in {
        val (_, bytes) = payload(PostgresTypes.HStore(Map("k" -> Maybe.Absent)))
        val expected   = int32(1) ++ int32(1) ++ Seq('k'.toByte) ++ int32(-1)
        assert(bytes.toSeq == expected)
    }

    "HStore round-trips entries, including a NULL value and an empty map" in {
        val cases = Seq(
            PostgresTypes.HStore(Map.empty),
            PostgresTypes.HStore(Map("k" -> Maybe("v"))),
            PostgresTypes.HStore(Map("a" -> Maybe("1"), "b" -> Maybe.Absent, "ç" -> Maybe("ü")))
        )
        cases.foreach(original => assert(roundTrip(original) == original, s"round-trip mismatch for $original"))
        succeed
    }

    // A second hstore parser would be free to read a negative entry count, enter no iteration, and answer with an
    // empty map, where the backend's `HstoreReader` rejects the same header as malformed. An empty map is the
    // plausible wrong answer a decode must never give, so it is the case worth pinning. The reads below go through
    // raw bytes rather than the round-trip helper, because a well-formed payload is exactly what cannot see the
    // difference.

    /** Reads an hstore payload straight from bytes in `format`, the way a column of unknown shape reaches the codec. */
    private def readHStore(bytes: Seq[Byte], format: SqlCodec.Format = SqlCodec.Format.Binary): PostgresTypes.HStore =
        readColumn[PostgresTypes.HStore](bytes, format)

    "an hstore payload with a negative entry count is refused, not read as an empty map" in {
        val ex = intercept[SqlDecodeHstoreFormatException](readHStore(int32(-1)))
        assert(ex.count == -1, s"the refused count must be reported, got ${ex.count}")
    }

    "an hstore payload shorter than its header is refused" in {
        val ex = intercept[SqlDecodeInsufficientBytesException](readHStore(Seq[Byte](0, 0)))
        assert(ex.actual == 2, s"expected the two available bytes reported, got ${ex.actual}")
    }

    "a text-format hstore decodes to its entries" in {
        // Every column of a `simpleQuery` result is text, so this rendering is one an hstore column really
        // arrives in. Bytes handed back with no format attached reach the binary header parser instead: the four
        // ASCII bytes of `"a"=` read as an entry count of 576725053 and the decode fails where it should hand back
        // the two entries. `HstoreReader` can read either rendering; the channel is what says which one these
        // bytes are.
        val bytes = """"a"=>"1", "b"=>NULL""".getBytes(java.nio.charset.StandardCharsets.UTF_8).toSeq
        assert(readHStore(bytes, SqlCodec.Format.Text) == PostgresTypes.HStore(Map("a" -> Maybe("1"), "b" -> Maybe.Absent)))
    }

    // ── Range ─────────────────────────────────────────────────────────────────

    "Range[Int] writes flags and one element per bound, in order" in {
        val range  = PostgresTypes.Range(PostgresTypes.Range.Bound.Inclusive(1), PostgresTypes.Range.Bound.Exclusive(10))
        val params = paramsOf(summon[SqlSchema.Column[PostgresTypes.Range[Int]]], range)
        assert(params.size == 1, s"a range is one column however many elements it carries, got ${params.size}")
        assert(params(0).encoder.oid == rangeOids("int4range"), "an Int element resolves the builtin int4range type")
        assert(params(0).encoder.format == SqlCodec.Format.Binary, "the range payload states the form its layout fixes")
        params(0).encoded match
            case Maybe.Present(bytes) =>
                // flags 0x02 (lower inclusive, upper exclusive), then each int4 element as int32 length 4 plus its
                // own four bytes, in bound order. Both the composition order and the element bytes are the real
                // writer's, so this is the payload the server receives.
                assert(bytes.toArray.toSeq == Seq[Byte](0x02, 0, 0, 0, 4, 0, 0, 0, 1, 0, 0, 0, 4, 0, 0, 0, 10))
            case Maybe.Absent => fail("expected the range param to carry bytes")
        end match
    }

    "a text-format range column is refused by name rather than read as the empty range" in {
        // `[1,10)` opens with 0x5B, whose low bit is the binary form's EMPTY flag, so the header parser reads
        // "the empty range" out of a range holding ten values. With the format on the payload the refusal names
        // what is actually missing, which is a text-range parser.
        val text = "[1,10)".getBytes(java.nio.charset.StandardCharsets.UTF_8).toSeq
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            val _ = readColumn[PostgresTypes.Range[Int]](text, SqlCodec.Format.Text)
        }
        assert(ex.feature == "reading a range value in the text wire format", s"unexpected feature: ${ex.feature}")
        assert(ex.dialect == kyo.db.Idiom.Id("postgres"))
    }

    "Range[Int] flags record which ends are inclusive and which are unbounded" in {
        def flags(range: PostgresTypes.Range[Int]): Int = payload(range)._2.head.toInt & 0xff
        assert(flags(PostgresTypes.Range(PostgresTypes.Range.Bound.Inclusive(1), PostgresTypes.Range.Bound.Inclusive(2))) == (0x02 | 0x04))
        assert(flags(PostgresTypes.Range(PostgresTypes.Range.Bound.Exclusive(1), PostgresTypes.Range.Bound.Exclusive(2))) == 0x00)
        assert(flags(PostgresTypes.Range(PostgresTypes.Range.Bound.Unbounded, PostgresTypes.Range.Bound.Inclusive(2))) == (0x08 | 0x04))
        assert(flags(PostgresTypes.Range(PostgresTypes.Range.Bound.Inclusive(1), PostgresTypes.Range.Bound.Unbounded)) == (0x02 | 0x10))
    }

    "Range round-trips every combination of bounds" in {
        val cases = Seq(
            PostgresTypes.Range(PostgresTypes.Range.Bound.Inclusive(1), PostgresTypes.Range.Bound.Exclusive(10)),
            PostgresTypes.Range(PostgresTypes.Range.Bound.Exclusive(-5), PostgresTypes.Range.Bound.Inclusive(0)),
            PostgresTypes.Range(PostgresTypes.Range.Bound.Unbounded, PostgresTypes.Range.Bound.Exclusive(7)),
            PostgresTypes.Range[Int](PostgresTypes.Range.Bound.Unbounded, PostgresTypes.Range.Bound.Unbounded)
        )
        cases.foreach(original => assert(roundTrip(original) == original, s"round-trip mismatch for $original"))
        succeed
    }

    "each builtin element type resolves its own range type name" in {
        val longRange = PostgresTypes.Range(PostgresTypes.Range.Bound.Inclusive(1L), PostgresTypes.Range.Bound.Exclusive(10L))
        assert(payload(longRange)._1 == "int8range")
        val dateRange = PostgresTypes.Range(
            PostgresTypes.Range.Bound.Inclusive(java.time.LocalDate.of(2026, 1, 1)),
            PostgresTypes.Range.Bound.Exclusive(java.time.LocalDate.of(2026, 2, 1))
        )
        assert(payload(dateRange)._1 == "daterange")
        val tsRange = PostgresTypes.Range(
            PostgresTypes.Range.Bound.Inclusive(java.time.LocalDateTime.of(2026, 1, 1, 0, 0)),
            PostgresTypes.Range.Bound.Exclusive(java.time.LocalDateTime.of(2026, 2, 1, 0, 0))
        )
        assert(payload(tsRange)._1 == "tsrange")
    }

    "a numrange round-trips, its elements re-encoded in the payload's binary form" in {
        // A standalone `numeric` bind goes out as its ASCII rendering (planner-plan parity with the static
        // renderer), but a binary range holds binary elements, so the element position selects the binary
        // sibling encoder instead of refusing. `Range[BigDecimal]` compiling is only honest if it also binds.
        val decRange =
            PostgresTypes.Range(
                PostgresTypes.Range.Bound.Inclusive(BigDecimal("1.25")),
                PostgresTypes.Range.Bound.Exclusive(BigDecimal("10.5"))
            )
        assert(payload(decRange)._1 == "numrange")
        assert(roundTrip(decRange) == decRange)
    }

    "an element type with no builtin PostgreSQL range does not compile" in {
        // PostgreSQL has exactly six builtin range types, so RangeKind is a closed set and String is not in it.
        // Rejecting this at compile time is the point: the alternative is a payload the server rejects at execution.
        typeCheckFailure("summon[SqlSchema.Column[PostgresTypes.Range[String]]]")
        typeCheckFailure("summon[SqlSchema.Column[PostgresTypes.Range[Boolean]]]")
    }

    "a range end cannot be absent, and RangeKind makes that unreachable by construction" in {
        // The binary format has no absent end, so writing one would silently change the value's meaning. Every RangeKind
        // element type encodes to a column that always carries a value, so the writer's guard is not reachable through
        // this codec at all. The guard itself is exercised directly in PostgresParamWriterTest.
        typeCheckFailure("summon[SqlSchema.Column[PostgresTypes.Range[Maybe[Int]]]]")
    }

    "the empty range is rejected on read rather than read back as the range of every value" in {
        // PostgreSQL supports the empty range and sends it; what cannot hold it is Range[A], whose two Bounds have no
        // spelling for "holds no values". So the refusal is a decode failure naming the target type, not an unsupported
        // feature naming the backend, and the assertion below pins both halves.
        //
        // Flags 0x01 alone: the server's EMPTY range.
        val ex = intercept[SqlDecodeEmptyRangeException] {
            val _ = readColumn[PostgresTypes.Range[Int]](Seq[Byte](0x01), SqlCodec.Format.Binary)
        }
        // `Range[Int]`, not `Range[int]`: the element name comes from `ConcreteTag.showType`, which renders the
        // Scala type, where the erased-class spelling would render the primitive's lowercase name.
        assert(ex.scalaType == "Range[Int]", s"the refusal must name the target type, got '${ex.scalaType}'")
        assert(ex.getMessage.contains("a pair of bounds cannot express a range that holds no values"), ex.getMessage)
    }

    // ── custom ────────────────────────────────────────────────────────────────

    "custom carries the write and read the caller supplied, in one column" in {
        given column: SqlSchema.Column[PostgresTypesPoint] =
            PostgresTypes.custom[PostgresTypesPoint] { (p, w) =>
                w.string(s"${p.x},${p.y}")
            } { r =>
                val parts = r.string().split(',')
                PostgresTypesPoint(parts(0).toInt, parts(1).toInt)
            }

        val original = PostgresTypesPoint(3, -4)
        val params   = paramsOf(column, original)
        assert(params.size == 1, "a custom column occupies exactly one column")
        // The write reached the `string` vocabulary method, so the param is a plain TEXT one carrying the rendering.
        assert(params(0).encoder.oid == kyo.internal.postgres.types.PostgresEncoder.OID_TEXT)
        assert(params(0).encoded.map(b => new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8)) == Maybe("3,-4"))
        assert(roundTrip(original) == original)
    }

    // ── pgEnum ────────────────────────────────────────────────────────────────

    "pgEnum writes the variant label under the enum's type name and reads it back" in {
        given column: SqlSchema.Column[PostgresTypesMood] = PostgresTypes.pgEnum[PostgresTypesMood]("mood")

        PostgresTypesMood.values.foreach { value =>
            val (typeName, bytes) = payload(value)
            assert(typeName == "mood")
            assert(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8) == value.toString)
            assert(roundTrip(value) == value)
        }
        succeed
    }

    "pgEnum decode raises a typed failure on a label that names no variant" in {
        given column: SqlSchema.Column[PostgresTypesMood] = PostgresTypes.pgEnum[PostgresTypesMood]("mood")
        val unknown                                       = "Furious".getBytes(java.nio.charset.StandardCharsets.UTF_8).toSeq
        Abort.run(SqlRow.Codec.catching(readColumn[PostgresTypesMood](unknown, SqlCodec.Format.Binary))).eval match
            case Result.Failure(e: SqlDecodeException) =>
                assert(e.getMessage.contains("Furious"), s"the failure should name the label: ${e.getMessage}")
            case other => fail(s"expected a typed decode failure, got $other")
        end match
    }

end PostgresTypesTest

// Fixtures. Both are top-level so ConcreteTag derives cleanly.
case class PostgresTypesPoint(x: Int, y: Int) derives CanEqual

enum PostgresTypesMood derives CanEqual:
    case Happy, Sad, Bored
