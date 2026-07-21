package kyo

import kyo.internal.mysql.MysqlBufferWriter
import kyo.internal.mysql.types.MysqlEncoder
import kyo.internal.postgres.BoundParam
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** Unit tests for [[SqlSchema]] `java.time.ZonedDateTime` (Phase 19, G-Codec-JDK-4).
  *
  * All tests are pure (no database container required). They exercise the schema via in-memory byte buffers.
  *
  * Design note — zone loss on round-trip: `timestamptz` stores only the UTC instant; the original IANA zone ID is dropped on the wire.
  * Decoding always yields a UTC-zoned value. Tests assert this behaviour explicitly (it is the documented design choice, not a bug).
  */
class SqlSchemaZonedDateTimeTest extends Test:

    // ── helpers ────────────────────────────────────────────────────────────────

    private def field(name: String): FieldDescription =
        FieldDescription(name, 0, 0, 0, 0, 0, 1) // formatCode=1 → Binary

    private def pgRow(columns: (String, Span[Byte])*): SqlRow =
        val values = Chunk.from(columns.map { case (_, b) => Maybe.Present(b) })
        val fields = Chunk.from(columns.map { case (n, _) => field(n) })
        new SqlRow(values, fields, Format.Binary)
    end pgRow

    /** Encodes a `kyo.Instant` for use in a synthetic PG `timestamptz` row, driving every byte through the public
      * [[SqlSchema.writePostgres]] + [[BoundParam.encoded]] surface — no test-side reach into internal encoders.
      */
    private def pgTimestamptzBytes(instant: kyo.Instant)(using kyo.test.AssertScope): Span[Byte] =
        summon[SqlSchema[kyo.Instant]].writePostgres(instant).head.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("SqlSchema[kyo.Instant].writePostgres produced a NULL param")
    end pgTimestamptzBytes

    // ── summon ─────────────────────────────────────────────────────────────────

    "summon SqlSchema[ZonedDateTime] compiles" in {
        val s: SqlSchema[java.time.ZonedDateTime] = summon[SqlSchema[java.time.ZonedDateTime]]
        assert(s.fieldCount == 1)
        succeed
    }

    // ── encode: ZonedDateTime encodes via timestamptz ──────────────────────────

    "ZonedDateTime encodes via timestamptz — writePostgres produces OID 1184 Binary param" in {
        val zdt    = java.time.ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneId.of("America/New_York"))
        val params = summon[SqlSchema[java.time.ZonedDateTime]].writePostgres(zdt)
        assert(params.size == 1)
        assert(params(0).oid == PostgresEncoder.OID_TIMESTAMPTZ)
        assert(params(0).format == Format.Binary)
        succeed
    }

    "ZonedDateTime with same instant in different zones encodes identically" in {
        // Two ZDTs at the same instant but different zones must yield the same OID/format.
        // Asia/Tokyo is UTC+9: 12:00 UTC = 21:00 Tokyo
        val zdt1 = java.time.ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val zdt2 = java.time.ZonedDateTime.of(2024, 1, 1, 21, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo"))
        assert(zdt1.toInstant.equals(zdt2.toInstant))
        val p1 = summon[SqlSchema[java.time.ZonedDateTime]].writePostgres(zdt1)
        val p2 = summon[SqlSchema[java.time.ZonedDateTime]].writePostgres(zdt2)
        assert(p1.size == 1 && p2.size == 1)
        assert(p1(0).oid == p2(0).oid)
        assert(p1(0).format == p2(0).format)
        succeed
    }

    "ZonedDateTime writeMysql produces one param" in {
        val zdt    = java.time.ZonedDateTime.of(2023, 3, 10, 8, 0, 0, 0, java.time.ZoneId.of("Europe/Paris"))
        val params = summon[SqlSchema[java.time.ZonedDateTime]].writeMysql(zdt)
        assert(params.size == 1)
        succeed
    }

    // ── decode: ZonedDateTime decodes with UTC zone (zone loss documented) ─────

    "ZonedDateTime decodes with UTC zone (original zone lost — documented)" in {
        // Encode a UTC instant directly, then decode via readPostgres.
        // No matter what zone the original ZDT had, the decoded result must be UTC.
        val original = java.time.ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, java.time.ZoneId.of("America/New_York"))
        val instant  = kyo.Instant.fromJava(original.toInstant)
        val bytes    = pgTimestamptzBytes(instant)
        val row      = pgRow("ts" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.ZonedDateTime]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                // The zone must always be UTC, not the original America/New_York.
                assert(decoded.getZone.equals(java.time.ZoneOffset.UTC))
                // The instant must be preserved.
                assert(decoded.toInstant.equals(original.toInstant))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "ZonedDateTime with Europe/Paris zone decodes as UTC (original zone is lost)" in {
        val original = java.time.ZonedDateTime.of(2023, 12, 31, 16, 0, 0, 0, java.time.ZoneId.of("Europe/Paris"))
        val instant  = kyo.Instant.fromJava(original.toInstant)
        val bytes    = pgTimestamptzBytes(instant)
        val row      = pgRow("ts" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.ZonedDateTime]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.getZone.equals(java.time.ZoneOffset.UTC))
                assert(decoded.toInstant.equals(original.toInstant))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    // ── round-trip with UTC normalization ─────────────────────────────────────

    "ZonedDateTime round-trips with UTC normalization — UTC zone is preserved exactly" in {
        // A ZDT already at UTC survives the round-trip (no zone to lose).
        val original = java.time.ZonedDateTime.of(2024, 3, 14, 15, 9, 26, 535_000_000, java.time.ZoneOffset.UTC)
        val instant  = kyo.Instant.fromJava(original.toInstant)
        val bytes    = pgTimestamptzBytes(instant)
        val row      = pgRow("ts" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.ZonedDateTime]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getZone.equals(java.time.ZoneOffset.UTC))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "ZonedDateTime round-trip: non-UTC zone normalizes to UTC at the same instant" in {
        val original = java.time.ZonedDateTime.of(2022, 8, 1, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo"))
        val instant  = kyo.Instant.fromJava(original.toInstant)
        val bytes    = pgTimestamptzBytes(instant)
        val row      = pgRow("ts" -> bytes)
        val result   = Abort.run(summon[SqlSchema[java.time.ZonedDateTime]].readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                // Decoded zone is always UTC.
                assert(decoded.getZone.equals(java.time.ZoneOffset.UTC))
                // Decoded instant matches the original instant (2022-08-01T00:00:00Z, Tokyo is UTC+9).
                assert(decoded.toInstant.equals(original.toInstant))
                // The decoded hour is shifted to UTC (was 09:00 Asia/Tokyo → 00:00 UTC).
                assert(decoded.getHour == 0)
            case other => fail(s"Expected Success but got $other")
        end match
    }

    // ── case class round-trip ─────────────────────────────────────────────────

    "ZonedDateTime case class round-trips" in {
        val zdt    = java.time.ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC)
        val record = SqlSchemaZonedDateTimeRecord(42L, zdt)
        val params = summon[SqlSchema[SqlSchemaZonedDateTimeRecord]].writePostgres(record)
        assert(params.size == 2)
        // First param: Long → OID_INT8
        assert(params(0).oid == PostgresEncoder.OID_INT8)
        // Second param: ZonedDateTime → OID_TIMESTAMPTZ
        assert(params(1).oid == PostgresEncoder.OID_TIMESTAMPTZ)
        succeed
    }

    "ZonedDateTime case class readPostgres round-trips" in {
        val zdt = java.time.ZonedDateTime.of(2025, 6, 21, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        val idBytes = summon[SqlSchema[Long]].writePostgres(99L).head.encoded match
            case Maybe.Present(b) => b
            case Maybe.Absent     => fail("SqlSchema[Long].writePostgres produced a NULL param")
        val tsBytes = pgTimestamptzBytes(kyo.Instant.fromJava(zdt.toInstant))
        val row     = pgRow("id" -> idBytes, "ts" -> tsBytes)
        val result  = Abort.run(summon[SqlSchema[SqlSchemaZonedDateTimeRecord]].readPostgres(row)).eval
        result match
            case Result.Success(rec) =>
                assert(rec.id == 99L)
                assert(rec.ts.getZone.equals(java.time.ZoneOffset.UTC))
                assert(rec.ts.toInstant.equals(zdt.toInstant))
            case other => fail(s"Expected Success but got $other")
        end match
    }

    // ── Two-column preserving variant (#505) ──────────────────────────────────
    //
    // SqlSchema.zonedDateTimePreserving stores (instant, zone_id) so the original IANA
    // zone survives the round-trip. Opt-in alternative to the default single-column UTC-only given.

    "zonedDateTimePreserving reports fieldCount 2 (instant + zone_id)" in {
        val s = SqlSchema.zonedDateTimePreserving
        assert(s.fieldCount == 2)
        assert(s.fieldNames == Chunk("instant", "zone_id"))
        succeed
    }

    "zonedDateTimePreserving writePostgres emits two BoundParams" in {
        val zdt    = java.time.ZonedDateTime.parse("2026-05-22T10:30:00Z[Europe/Paris]")
        val params = SqlSchema.zonedDateTimePreserving.writePostgres(zdt)
        assert(params.size == 2)
        assert(params(0).oid == PostgresEncoder.OID_TIMESTAMPTZ)
        assert(params(1).oid == PostgresEncoder.OID_TEXT)
        succeed
    }

    /** Builds an SqlRow from the wire bytes produced by `writePostgres` on the same schema — every byte flows through the public
      * [[SqlSchema.writePostgres]] + [[BoundParam.encoded]] surface; no test-side reach into internal encoders.
      */
    private def synthRowFromWrite(
        schema: SqlSchema[java.time.ZonedDateTime],
        v: java.time.ZonedDateTime,
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

    "zonedDateTimePreserving round-trip preserves Europe/Paris zone (write→read same schema)" in {
        val schema   = SqlSchema.zonedDateTimePreserving
        val original = java.time.ZonedDateTime.parse("2026-05-22T10:30:00+02:00[Europe/Paris]")
        val row      = synthRowFromWrite(schema, original, Seq("instant", "zone_id"))
        val result   = Abort.run(schema.readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getZone.getId == "Europe/Paris")
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "zonedDateTimePreserving round-trip preserves Asia/Tokyo zone (write→read same schema)" in {
        val schema   = SqlSchema.zonedDateTimePreserving
        val original = java.time.ZonedDateTime.parse("2026-05-22T18:30:00+09:00[Asia/Tokyo]")
        val row      = synthRowFromWrite(schema, original, Seq("instant", "zone_id"))
        val result   = Abort.run(schema.readPostgres(row)).eval
        result match
            case Result.Success(decoded) =>
                assert(decoded.toInstant.equals(original.toInstant))
                assert(decoded.getZone.getId == "Asia/Tokyo")
            case other => fail(s"Expected Success but got $other")
        end match
    }

    "zonedDateTimePreserving decode raises on unknown zone ID" in {
        val s = SqlSchema.zonedDateTimePreserving
        // Build a valid instant + invalid zone-id payload.
        val instantBytes = pgTimestamptzBytes(kyo.Instant.fromJava(java.time.Instant.parse("2026-05-22T10:30:00Z")))
        val zoneBytes    = Span.from("Not/A_Real/Zone".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val row          = pgRow("instant" -> instantBytes, "zone_id" -> zoneBytes)
        val result       = Abort.run[Throwable](s.readPostgres(row)).eval
        result match
            case Result.Failure(_) | Result.Panic(_) => succeed
            case other                               => fail(s"Expected failure on unknown zone, got $other")
        end match
    }

end SqlSchemaZonedDateTimeTest

// Top-level case class for the case-class round-trip tests.
case class SqlSchemaZonedDateTimeRecord(id: Long, ts: java.time.ZonedDateTime)

object SqlSchemaZonedDateTimeRecord:
    given SqlSchema[SqlSchemaZonedDateTimeRecord] = SqlSchema.of[SqlSchemaZonedDateTimeRecord](
        write = (rec, w) =>
            w.long(rec.id)
            // ZonedDateTime encodes via its Instant (zone ID is dropped on the wire — documented).
            w.instant(rec.ts.toInstant)
        ,
        read = r =>
            val id      = r.long()
            val instant = r.instant()
            val ts      = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)
            SqlSchemaZonedDateTimeRecord(id, ts)
    )
end SqlSchemaZonedDateTimeRecord
