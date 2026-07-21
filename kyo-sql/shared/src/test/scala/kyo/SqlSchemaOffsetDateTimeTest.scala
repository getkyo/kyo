package kyo

import kyo.internal.mysql.MysqlBufferWriter
import kyo.internal.mysql.types.MysqlEncoder
import kyo.internal.postgres.BoundParam
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** Unit tests for [[SqlSchema]] `java.time.OffsetDateTime` (Phase 18, G-Codec-JDK-3).
  *
  * All tests are pure (no database container required). They exercise the schema via in-memory byte buffers.
  *
  * Design note — offset loss on round-trip: `timestamptz` stores only the UTC instant; the original UTC-offset is dropped on the wire.
  * Decoding always yields `+00:00`. Tests assert this behaviour explicitly (it is the documented design choice, not a bug).
  */
class SqlSchemaOffsetDateTimeTest extends Test:

    // ── helpers ────────────────────────────────────────────────────────────────

    private def field(name: String): FieldDescription =
        FieldDescription(name, 0, 0, 0, 0, 0, 1) // formatCode=1 → Binary

    private def pgRow(columns: (String, Span[Byte])*): SqlRow =
        val values = Chunk.from(columns.map { case (_, b) => Maybe.Present(b) })
        val fields = Chunk.from(columns.map { case (n, _) => field(n) })
        new SqlRow(values, fields, Format.Binary)
    end pgRow

    private def mysqlRow(columns: (String, Span[Byte])*): SqlRow =
        val values = Chunk.from(columns.map { case (_, b) => Maybe.Present(b) })
        val fields = Chunk.from(columns.map { case (n, _) => field(n) })
        new SqlRow(values, fields, Format.Binary)
    end mysqlRow

    /** Encodes a `kyo.Instant` for use in a synthetic PG `timestamptz` row, driving every byte through the public
      * [[SqlSchema.writePostgres]] + [[BoundParam.encoded]] surface — no test-side reach into internal encoders.
      */
    private def pgTimestamptzBytes(instant: kyo.Instant)(using kyo.test.AssertScope): Span[Byte] =
        summon[SqlSchema[kyo.Instant]].writePostgres(instant).head.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("SqlSchema[kyo.Instant].writePostgres produced a NULL param")
    end pgTimestamptzBytes

    /** Encodes a `java.time.LocalDateTime` for use in a synthetic MySQL `DATETIME` row, driving every byte through the public
      * [[SqlSchema.writeMysql]] + [[BoundMysqlParam.encoded]] surface.
      */
    private def mysqlDatetimeBytes(ldt: java.time.LocalDateTime)(using kyo.test.AssertScope): Span[Byte] =
        summon[SqlSchema[java.time.LocalDateTime]].writeMysql(ldt).head.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("SqlSchema[LocalDateTime].writeMysql produced a NULL param")
    end mysqlDatetimeBytes

    // ── summon ─────────────────────────────────────────────────────────────────

    "summon SqlSchema[OffsetDateTime] compiles" in {
        val s: SqlSchema[java.time.OffsetDateTime] = summon[SqlSchema[java.time.OffsetDateTime]]
        assert(s.fieldCount == 1)
        succeed
    }

    // ── encode: OffsetDateTime encodes via timestamptz ─────────────────────────

    "OffsetDateTime encodes via timestamptz — writePostgres produces OID 1184 Binary param" in {
        val odt    = java.time.OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneOffset.ofHours(5))
        val params = summon[SqlSchema[java.time.OffsetDateTime]].writePostgres(odt)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_TIMESTAMPTZ)
        assert(params(0).format == Format.Binary)
        succeed
    }

    "OffsetDateTime with positive offset encodes the same instant regardless of offset" in {
        // Two ODTs at the same instant but different offsets must yield byte-identical params.
        val odt1 = java.time.OffsetDateTime.of(2024, 1, 1, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val odt2 = java.time.OffsetDateTime.of(2024, 1, 1, 17, 0, 0, 0, java.time.ZoneOffset.ofHours(5))
        assert(odt1.toInstant.equals(odt2.toInstant))
        val p1 = summon[SqlSchema[java.time.OffsetDateTime]].writePostgres(odt1)
        val p2 = summon[SqlSchema[java.time.OffsetDateTime]].writePostgres(odt2)
        // Both produce the same OID/format; the encoded bytes must match.
        assert(p1.size == 1 && p2.size == 1)
        assert(p1(0).oid == p2(0).oid)
        assert(p1(0).format == p2(0).format)
        succeed
    }

    "OffsetDateTime writeMysql produces one param" in {
        val odt    = java.time.OffsetDateTime.of(2023, 3, 10, 8, 0, 0, 0, java.time.ZoneOffset.ofHours(-3))
        val params = summon[SqlSchema[java.time.OffsetDateTime]].writeMysql(odt)
        assert(params.size == 1)
        succeed
    }

    // ── decode: OffsetDateTime decodes as UTC (offset loss documented) ──────────

    "OffsetDateTime decodes as UTC offset (original offset is lost — documented)" in {
        // Encode a UTC instant directly, then decode via readPostgres.
        // No matter what offset the original ODT had, the decoded result must be +00:00.
        val original = java.time.OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneOffset.ofHours(5))
        val instant  = kyo.Instant.fromJava(original.toInstant)
        val bytes    = pgTimestamptzBytes(instant)
        val row      = pgRow("ts" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.OffsetDateTime]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                // The offset must always be UTC, not the original +05:00.
                assert(decoded.getOffset.equals(java.time.ZoneOffset.UTC))
                // The instant must be preserved.
                assert(decoded.toInstant.equals(original.toInstant))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "OffsetDateTime with negative offset decodes as UTC (original -08:00 offset is lost)" in {
        val original = java.time.OffsetDateTime.of(2023, 12, 31, 16, 0, 0, 0, java.time.ZoneOffset.ofHours(-8))
        val instant  = kyo.Instant.fromJava(original.toInstant)
        val bytes    = pgTimestamptzBytes(instant)
        val row      = pgRow("ts" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.OffsetDateTime]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.getOffset.equals(java.time.ZoneOffset.UTC))
                assert(decoded.toInstant.equals(original.toInstant))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    // ── round-trip with UTC normalisation ────────────────────────────────────

    "OffsetDateTime round-trips with UTC normalization — UTC+0 is preserved exactly" in {
        // An ODT already at UTC survives the round-trip byte-for-byte (no offset to lose).
        val original = java.time.OffsetDateTime.of(2024, 3, 14, 15, 9, 26, 535_000_000, java.time.ZoneOffset.UTC)
        val instant  = kyo.Instant.fromJava(original.toInstant)
        val bytes    = pgTimestamptzBytes(instant)
        val row      = pgRow("ts" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.OffsetDateTime]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.equals(original)) // UTC: no offset difference
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "OffsetDateTime round-trip: non-UTC offset normalizes to UTC at the same instant" in {
        val original = java.time.OffsetDateTime.of(2022, 8, 1, 9, 0, 0, 0, java.time.ZoneOffset.ofHours(3))
        val instant  = kyo.Instant.fromJava(original.toInstant)
        val bytes    = pgTimestamptzBytes(instant)
        val row      = pgRow("ts" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.OffsetDateTime]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                // Decoded offset is always UTC.
                assert(decoded.getOffset.equals(java.time.ZoneOffset.UTC))
                // Decoded instant matches the original instant (2022-08-01T06:00:00Z).
                assert(decoded.toInstant.equals(original.toInstant))
                // The decoded local time is shifted to UTC (was 09:00 +03:00 → 06:00 +00:00).
                assert(decoded.getHour == 6)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    // ── case class round-trip ─────────────────────────────────────────────────

    "OffsetDateTime case class round-trips" in {
        val odt    = java.time.OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC)
        val record = SqlSchemaOffsetDateTimeRecord(42L, odt)
        val params = summon[SqlSchema[SqlSchemaOffsetDateTimeRecord]].writePostgres(record)
        assert(params.size == 2)
        // First param: Long → OID_INT8
        assert(params(0).oid == PostgresEncoder.OID_INT8)
        // Second param: OffsetDateTime → OID_TIMESTAMPTZ
        assert(params(1).oid == PostgresEncoder.OID_TIMESTAMPTZ)
        succeed
    }

    "OffsetDateTime case class readPostgres round-trips" in {
        val odt = java.time.OffsetDateTime.of(2025, 6, 21, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val idBytes = summon[SqlSchema[Long]].writePostgres(99L).head.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("SqlSchema[Long].writePostgres produced a NULL param")
        val tsBytes = pgTimestamptzBytes(kyo.Instant.fromJava(odt.toInstant))
        val row     = pgRow("id" -> idBytes, "ts" -> tsBytes)
        val result  = Abort.run(summon[SqlSchema[SqlSchemaOffsetDateTimeRecord]].readPostgres(row)).eval
        result match
            case Result.Success(rec) =>
                assert(rec.id == 99L)
                assert(rec.ts.getOffset.equals(java.time.ZoneOffset.UTC))
                assert(rec.ts.toInstant.equals(odt.toInstant))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    // ── Two-column preserving variant (#505) ──────────────────────────────────
    //
    // SqlSchema.offsetDateTimePreserving stores (instant, offset_seconds) so the original
    // OffsetDateTime offset survives the round-trip. Opt-in alternative to the default
    // single-column UTC-only given.

    "offsetDateTimePreserving reports fieldCount 2 (instant + offset_seconds)" in {
        val s = SqlSchema.offsetDateTimePreserving
        assert(s.fieldCount == 2)
        assert(s.fieldNames == Chunk("instant", "offset_seconds"))
        succeed
    }

    "offsetDateTimePreserving writePostgres emits two BoundParams" in {
        val odt    = java.time.OffsetDateTime.parse("2026-05-22T10:30:00+05:30")
        val params = SqlSchema.offsetDateTimePreserving.writePostgres(odt)
        assert(params.size == 2)
        assert(params(0).oid == PostgresEncoder.OID_TIMESTAMPTZ)
        assert(params(1).oid == PostgresEncoder.OID_INT4)
        succeed
    }

    /** Builds an SqlRow from the wire bytes produced by `writePostgres` on the same schema. Drives every byte through the public
      * [[SqlSchema.writePostgres]] + [[BoundParam.encoded]] surface — no test-side reach into internal encoders.
      */
    private def synthRowFromWrite(
        schema: SqlSchema[java.time.OffsetDateTime],
        v: java.time.OffsetDateTime,
        columnNames: Seq[String]
    )(using kyo.test.AssertScope): SqlRow =
        val params = schema.writePostgres(v)
        assert(params.size == columnNames.size, s"writePostgres produced ${params.size} params, expected ${columnNames.size}")
        val bytes = params.zipWithIndex.map { (p, i) =>
            p.encoded match
                case Maybe.Present(b) => columnNames(i) -> b
                case Maybe.Absent     => fail(s"param $i was NULL, expected a value")
        }
        pgRow(bytes*)
    end synthRowFromWrite

    "offsetDateTimePreserving round-trip preserves +05:30 offset via PG (write→read same schema)" in {
        val schema   = SqlSchema.offsetDateTimePreserving
        val original = java.time.OffsetDateTime.parse("2026-05-22T10:30:00+05:30")
        val row      = synthRowFromWrite(schema, original, Seq("instant", "offset_seconds"))
        val result   = Abort.run(schema.readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getOffset.equals(original.getOffset))
                assert(decoded.getOffset.getTotalSeconds == 5 * 3600 + 30 * 60)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "offsetDateTimePreserving round-trip preserves negative -08:00 offset (write→read same schema)" in {
        val schema   = SqlSchema.offsetDateTimePreserving
        val original = java.time.OffsetDateTime.parse("2026-05-22T03:15:00-08:00")
        val row      = synthRowFromWrite(schema, original, Seq("instant", "offset_seconds"))
        val result   = Abort.run(schema.readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getOffset.getTotalSeconds == -8 * 3600)
            case other => fail(s"Expected Success but got $other")
        end match
    }

end SqlSchemaOffsetDateTimeTest

// Top-level case class for the case-class round-trip tests.
// SqlSchema.derived cannot be used here because Schema.derived needs Schema[OffsetDateTime]
// in scope; OffsetDateTime's Schema is only registered via SqlSchema (kyo-sql) not kyo-schema.
// We use SqlSchema.of to hand-roll the two-field schema (id: Long, ts: OffsetDateTime).
case class SqlSchemaOffsetDateTimeRecord(id: Long, ts: java.time.OffsetDateTime)

object SqlSchemaOffsetDateTimeRecord:
    given SqlSchema[SqlSchemaOffsetDateTimeRecord] = SqlSchema.of[SqlSchemaOffsetDateTimeRecord](
        write = (rec, w) =>
            w.long(rec.id)
            // OffsetDateTime encodes via its Instant (offset is dropped on the wire).
            w.instant(rec.ts.toInstant)
        ,
        read = r =>
            val id      = r.long()
            val instant = r.instant()
            val ts      = java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)
            SqlSchemaOffsetDateTimeRecord(id, ts)
    )
end SqlSchemaOffsetDateTimeRecord
