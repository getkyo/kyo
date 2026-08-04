package kyo

import kyo.Sql.*
import kyo.internal.SqlTestBackend

/** Cross-backend type-codec round-trip battery: one named group per [[SqlSchema]]-supported Scala type, each running a single-column table
  * round trip once per available backend through [[SqlBackendTest.forEachBackend]], whose body receives the backend descriptor. Every group
  * follows the same shape: derive a one-field case class local to the group, named so its lowercased simple name matches the table the
  * group creates (kyo derives a type's SQL table name as its simple name lowercased, so a distinct per-group name keeps each `CREATE TABLE`
  * and the group's typed insert and read in agreement), `CREATE TABLE` with a column type named by the backend descriptor, insert one row
  * through the typed [[Sql.insert]], read it back through the typed [[Sql.from]], and assert the decoded value equals what was inserted.
  *
  * This is where every [[SqlSchema]]-supported type meets a live database on every available backend; each engine module's own `types`
  * suites cover the wire bytes for one engine only. Every type here is derived through the SAME shape (a one-field case class over `T`,
  * whose row instance the ambient derivation assembles from `T`'s own [[SqlSchema.Column]]), so a reader can trust each group exercises
  * `T`'s declared wire codec and not a fallback: the derivation has nothing else to reach for, since a field with no column is a compile
  * error rather than a substitution.
  *
  * ==Column types come from the descriptor==
  * Every table's column type is named through [[SqlTestBackend.columnType]], keyed by [[SqlTestBackend.ColumnType]], so the DDL carries no
  * engine literal and each backend spells the portable kind its own way. The string-backed types (`String`, `java.net.URI`,
  * `java.util.Locale`, `java.util.Currency`) reuse [[SqlTestBackend.textColumnType]] instead.
  */
class SqlCodecConformanceTest extends SqlBackendTest:

    // ── Int ──────────────────────────────────────────────────────────────────

    "Int round-trip" - {
        case class IntRow(v: Int) derives CanEqual

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE introw (v ${backend.columnType(SqlTestBackend.ColumnType.Int)})")
                _    <- Sql.insert[IntRow].values(IntRow(42)).run
                rows <- Sql.from[IntRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == IntRow(42), s"expected IntRow(42), got ${rows.head}")
        }
    }

    // ── Long ─────────────────────────────────────────────────────────────────

    "Long round-trip" - {
        case class LongRow(v: Long) derives CanEqual

        val value = Long.MaxValue

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE longrow (v ${backend.columnType(SqlTestBackend.ColumnType.BigInt)})")
                _    <- Sql.insert[LongRow].values(LongRow(value)).run
                rows <- Sql.from[LongRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == LongRow(value), s"expected LongRow($value), got ${rows.head}")
        }
    }

    // ── Short ────────────────────────────────────────────────────────────────

    "Short round-trip" - {
        case class ShortRow(v: Short) derives CanEqual

        val value: Short = 12345

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE shortrow (v ${backend.columnType(SqlTestBackend.ColumnType.SmallInt)})")
                _    <- Sql.insert[ShortRow].values(ShortRow(value)).run
                rows <- Sql.from[ShortRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == ShortRow(value), s"expected ShortRow($value), got ${rows.head}")
        }
    }

    // ── Byte ─────────────────────────────────────────────────────────────────

    "Byte round-trip" - {
        case class ByteRow(v: Byte) derives CanEqual

        val value: Byte = 100

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE byterow (v ${backend.columnType(SqlTestBackend.ColumnType.SmallInt)})")
                _    <- Sql.insert[ByteRow].values(ByteRow(value)).run
                rows <- Sql.from[ByteRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == ByteRow(value), s"expected ByteRow($value), got ${rows.head}")
        }
    }

    // ── Boolean ──────────────────────────────────────────────────────────────

    "Boolean round-trip" - {
        case class BooleanRow(v: Boolean) derives CanEqual

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE booleanrow (v ${backend.columnType(SqlTestBackend.ColumnType.Boolean)})")
                _    <- Sql.insert[BooleanRow].values(BooleanRow(true)).run
                rows <- Sql.from[BooleanRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == BooleanRow(true), s"expected BooleanRow(true), got ${rows.head}")
        }
    }

    // ── String ───────────────────────────────────────────────────────────────

    "String round-trip" - {
        case class StringRow(v: String) derives CanEqual

        val value = "kyo-sql conformance"

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE stringrow (v ${backend.textColumnType})")
                _    <- Sql.insert[StringRow].values(StringRow(value)).run
                rows <- Sql.from[StringRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == StringRow(value), s"expected StringRow($value), got ${rows.head}")
        }
    }

    // ── Float ────────────────────────────────────────────────────────────────

    "Float round-trip" - {
        case class FloatRow(v: Float) derives CanEqual

        val value = 3.14f

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE floatrow (v ${backend.columnType(SqlTestBackend.ColumnType.Float32)})")
                _    <- Sql.insert[FloatRow].values(FloatRow(value)).run
                rows <- Sql.from[FloatRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == FloatRow(value), s"expected FloatRow($value), got ${rows.head}")
        }
    }

    // ── Double ───────────────────────────────────────────────────────────────

    "Double round-trip" - {
        case class DoubleRow(v: Double) derives CanEqual

        val value = 3.14159265358979

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE doublerow (v ${backend.columnType(SqlTestBackend.ColumnType.Float64)})")
                _    <- Sql.insert[DoubleRow].values(DoubleRow(value)).run
                rows <- Sql.from[DoubleRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == DoubleRow(value), s"expected DoubleRow($value), got ${rows.head}")
        }
    }

    // ── BigDecimal ───────────────────────────────────────────────────────────

    "BigDecimal round-trip" - {
        case class BigDecimalRow(v: BigDecimal) derives CanEqual

        val value = BigDecimal("98765432109876.543210")

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE bigdecimalrow (v ${backend.columnType(SqlTestBackend.ColumnType.Numeric)})")
                _    <- Sql.insert[BigDecimalRow].values(BigDecimalRow(value)).run
                rows <- Sql.from[BigDecimalRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == BigDecimalRow(value), s"expected BigDecimalRow($value), got ${rows.head}")
        }
    }

    // ── BigInt ───────────────────────────────────────────────────────────────
    //
    // The type is named to match its created table so the type-derived table name and the DDL agree (each block is schema-isolated).

    "BigInt round-trip" - {
        case class BigIntRow(v: BigInt) derives CanEqual

        val value = BigInt("123456789012345678901234")

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE bigintrow (v ${backend.columnType(SqlTestBackend.ColumnType.Numeric)})")
                _    <- Sql.insert[BigIntRow].values(BigIntRow(value)).run
                rows <- Sql.from[BigIntRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == BigIntRow(value), s"expected BigIntRow($value), got ${rows.head}")
        }
    }

    // ── Span[Byte] (bytes / blob) ────────────────────────────────────────────
    //
    // `Span[Byte]`'s equality is content-blind (it is an opaque `Array[Byte]`, and `Array`'s own `equals` is reference identity), so
    // `BytesRow == BytesRow` cannot be used here; the assertion compares `.toArray.toSeq` instead, the same pattern an existing binary-column
    // round-trip leaf elsewhere in the engine-specific test trees already uses.

    "Span[Byte] round-trip" - {
        case class BytesRow(v: Span[Byte])

        val value = Span.from(Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05, 0x00, 0x7f, 0xff.toByte))

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE bytesrow (v ${backend.columnType(SqlTestBackend.ColumnType.Bytes)})")
                _    <- Sql.insert[BytesRow].values(BytesRow(value)).run
                rows <- Sql.from[BytesRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(
                    rows.head.v.toArray.toSeq == value.toArray.toSeq,
                    s"expected ${value.toArray.toSeq}, got ${rows.head.v.toArray.toSeq}"
                )
        }
    }

    // ── kyo.Instant ──────────────────────────────────────────────────────────

    "kyo.Instant round-trip" - {
        case class InstantRow(v: kyo.Instant) derives CanEqual

        val value = kyo.Instant.fromJava(java.time.Instant.parse("2024-03-15T10:30:00Z"))

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE instantrow (v ${backend.columnType(SqlTestBackend.ColumnType.Timestamp)})")
                _    <- Sql.insert[InstantRow].values(InstantRow(value)).run
                rows <- Sql.from[InstantRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == InstantRow(value), s"expected InstantRow($value), got ${rows.head}")
        }
    }

    // ── java.time.Duration ───────────────────────────────────────────────────

    "java.time.Duration round-trip" - {
        case class DurationRow(v: java.time.Duration) derives CanEqual

        val value = java.time.Duration.ofHours(3).plusMinutes(15).plusSeconds(30)

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE durationrow (v ${backend.columnType(SqlTestBackend.ColumnType.Duration)})")
                _    <- Sql.insert[DurationRow].values(DurationRow(value)).run
                rows <- Sql.from[DurationRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == DurationRow(value), s"expected DurationRow($value), got ${rows.head}")
        }
    }

    // ── java.time.LocalDate ──────────────────────────────────────────────────

    "java.time.LocalDate round-trip" - {
        case class LocalDateRow(v: java.time.LocalDate) derives CanEqual

        val value = java.time.LocalDate.of(2024, 3, 15)

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE localdaterow (v ${backend.columnType(SqlTestBackend.ColumnType.Date)})")
                _    <- Sql.insert[LocalDateRow].values(LocalDateRow(value)).run
                rows <- Sql.from[LocalDateRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == LocalDateRow(value), s"expected LocalDateRow($value), got ${rows.head}")
        }
    }

    // ── java.time.LocalDateTime ──────────────────────────────────────────────

    "java.time.LocalDateTime round-trip" - {
        case class LocalDateTimeRow(v: java.time.LocalDateTime) derives CanEqual

        val value = java.time.LocalDateTime.of(2024, 3, 15, 10, 30, 0)

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE localdatetimerow (v ${backend.columnType(SqlTestBackend.ColumnType.DateTime)})")
                _    <- Sql.insert[LocalDateTimeRow].values(LocalDateTimeRow(value)).run
                rows <- Sql.from[LocalDateTimeRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == LocalDateTimeRow(value), s"expected LocalDateTimeRow($value), got ${rows.head}")
        }
    }

    // ── java.time.LocalTime ──────────────────────────────────────────────────

    "java.time.LocalTime round-trip" - {
        case class LocalTimeRow(v: java.time.LocalTime) derives CanEqual

        val value = java.time.LocalTime.of(14, 30, 15)

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE localtimerow (v ${backend.columnType(SqlTestBackend.ColumnType.Time)})")
                _    <- Sql.insert[LocalTimeRow].values(LocalTimeRow(value)).run
                rows <- Sql.from[LocalTimeRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == LocalTimeRow(value), s"expected LocalTimeRow($value), got ${rows.head}")
        }
    }

    // ── java.time.OffsetTime ─────────────────────────────────────────────────
    //
    // Unlike `OffsetDateTime`/`ZonedDateTime` below, the shared given for `OffsetTime` preserves the original offset on every backend:
    // an engine with a native time-with-offset wire codec carries it directly, and one that falls back to ISO-8601 text carries it too
    // (the offset is part of the text), so full value identity holds regardless of which offset the test picks.

    "java.time.OffsetTime round-trip" - {
        case class OffsetTimeRow(v: java.time.OffsetTime) derives CanEqual

        val value = java.time.OffsetTime.of(14, 30, 15, 0, java.time.ZoneOffset.ofHours(-5))

        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE offsettimerow (v ${backend.columnType(SqlTestBackend.ColumnType.TimeWithOffset)})"
                )
                _    <- Sql.insert[OffsetTimeRow].values(OffsetTimeRow(value)).run
                rows <- Sql.from[OffsetTimeRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == OffsetTimeRow(value), s"expected OffsetTimeRow($value), got ${rows.head}")
        }
    }

    // ── java.time.Period ─────────────────────────────────────────────────────
    //
    // The test value is already normalised (`Period.of(1, 2, 3)`, months < 12), matching the invariant both backends' codecs rely on: a
    // native calendar-interval wire form carries a single month count, and a text fallback re-normalises on both write and read.

    "java.time.Period round-trip" - {
        case class PeriodRow(v: java.time.Period) derives CanEqual

        val value = java.time.Period.of(1, 2, 3)

        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE periodrow (v ${backend.columnType(SqlTestBackend.ColumnType.CalendarInterval)})"
                )
                _    <- Sql.insert[PeriodRow].values(PeriodRow(value)).run
                rows <- Sql.from[PeriodRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == PeriodRow(value), s"expected PeriodRow($value), got ${rows.head}")
        }
    }

    // ── java.time.OffsetDateTime ─────────────────────────────────────────────
    //
    // The shared given normalises to UTC on decode regardless of the original offset (`timestamptz`/`DATETIME` both store an instant, not
    // an offset), so the test value uses `ZoneOffset.UTC` to keep the round trip a true value-identity check rather than one that is known
    // to lose information by design.

    "java.time.OffsetDateTime round-trip" - {
        case class OffsetDateTimeRow(v: java.time.OffsetDateTime) derives CanEqual

        val value = java.time.OffsetDateTime.of(2024, 3, 15, 10, 30, 0, 0, java.time.ZoneOffset.UTC)

        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE offsetdatetimerow (v ${backend.columnType(SqlTestBackend.ColumnType.Timestamp)})"
                )
                _    <- Sql.insert[OffsetDateTimeRow].values(OffsetDateTimeRow(value)).run
                rows <- Sql.from[OffsetDateTimeRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == OffsetDateTimeRow(value), s"expected OffsetDateTimeRow($value), got ${rows.head}")
        }
    }

    // ── java.time.ZonedDateTime ──────────────────────────────────────────────
    //
    // Same reasoning as `OffsetDateTime` above: the shared given decodes with `ZoneOffset.UTC` unconditionally, so the test value is
    // constructed with `ZoneOffset.UTC` (not `ZoneId.of("UTC")`, a distinct, non-equal zone) to keep `==` a true identity check.

    "java.time.ZonedDateTime round-trip" - {
        case class ZonedDateTimeRow(v: java.time.ZonedDateTime) derives CanEqual

        val value = java.time.ZonedDateTime.of(2024, 3, 15, 10, 30, 0, 0, java.time.ZoneOffset.UTC)

        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE zoneddatetimerow (v ${backend.columnType(SqlTestBackend.ColumnType.Timestamp)})"
                )
                _    <- Sql.insert[ZonedDateTimeRow].values(ZonedDateTimeRow(value)).run
                rows <- Sql.from[ZonedDateTimeRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == ZonedDateTimeRow(value), s"expected ZonedDateTimeRow($value), got ${rows.head}")
        }
    }

    // ── java.net.URI ─────────────────────────────────────────────────────────

    "java.net.URI round-trip" - {
        case class UriRow(v: java.net.URI) derives CanEqual

        val value = java.net.URI.create("https://example.com/path?query=value")

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE urirow (v ${backend.textColumnType})")
                _    <- Sql.insert[UriRow].values(UriRow(value)).run
                rows <- Sql.from[UriRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == UriRow(value), s"expected UriRow($value), got ${rows.head}")
        }
    }

    // ── java.util.Locale ─────────────────────────────────────────────────────

    "java.util.Locale round-trip" - {
        case class LocaleRow(v: java.util.Locale) derives CanEqual

        val value = java.util.Locale.forLanguageTag("pt-BR")

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE localerow (v ${backend.textColumnType})")
                _    <- Sql.insert[LocaleRow].values(LocaleRow(value)).run
                rows <- Sql.from[LocaleRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == LocaleRow(value), s"expected LocaleRow($value), got ${rows.head}")
        }
    }

    // ── java.util.Currency ───────────────────────────────────────────────────

    "java.util.Currency round-trip" - {
        case class CurrencyRow(v: java.util.Currency) derives CanEqual

        val value = java.util.Currency.getInstance("USD")

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE currencyrow (v ${backend.textColumnType})")
                _    <- Sql.insert[CurrencyRow].values(CurrencyRow(value)).run
                rows <- Sql.from[CurrencyRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == CurrencyRow(value), s"expected CurrencyRow($value), got ${rows.head}")
        }
    }

    // ── java.util.UUID ───────────────────────────────────────────────────────

    "java.util.UUID round-trip" - {
        case class UuidRow(v: java.util.UUID) derives CanEqual

        val value = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE uuidrow (v ${backend.columnType(SqlTestBackend.ColumnType.Uuid)})")
                _    <- Sql.insert[UuidRow].values(UuidRow(value)).run
                rows <- Sql.from[UuidRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == UuidRow(value), s"expected UuidRow($value), got ${rows.head}")
        }
    }

    // ── Chunk[Int] (array) ───────────────────────────────────────────────────

    "Chunk[Int] round-trip" - {
        case class ChunkIntRow(v: Chunk[Int]) derives CanEqual

        val value = Chunk(1, 2, 3, 42, -7)

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE chunkintrow (v ${backend.columnType(SqlTestBackend.ColumnType.IntArray)})")
                _    <- Sql.insert[ChunkIntRow].values(ChunkIntRow(value)).run
                rows <- Sql.from[ChunkIntRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == ChunkIntRow(value), s"expected ChunkIntRow($value), got ${rows.head}")
        }
    }

    // ── Chunk[String] (array) ────────────────────────────────────────────────

    "Chunk[String] round-trip" - {
        case class ChunkStringRow(v: Chunk[String]) derives CanEqual

        val value = Chunk("alpha", "beta", "gamma")

        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE chunkstringrow (v ${backend.columnType(SqlTestBackend.ColumnType.TextArray)})"
                )
                _    <- Sql.insert[ChunkStringRow].values(ChunkStringRow(value)).run
                rows <- Sql.from[ChunkStringRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == ChunkStringRow(value), s"expected ChunkStringRow($value), got ${rows.head}")
        }
    }

    // ── JsonText (JSON) ──────────────────────────────────────────────────────
    //
    // The document text is produced by an ordinary JSON encoder (kyo-schema here, as a library), and `JsonText` is what puts it on the
    // native JSON wire rather than in a text column. The round-trip asserts the decoded DOCUMENT rather than the bytes, because a server
    // is free to reformat what it stores: PostgreSQL `jsonb` drops insignificant whitespace and reorders object keys.

    "JsonText round-trip" - {
        case class JsonTextRow(v: JsonText) derives CanEqual

        val document = Structure.Value.Record(Chunk("name" -> Structure.Value.Str("kyo"), "count" -> Structure.Value.Integer(42L)))
        val value    = JsonText(Json.encode(document))

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE jsontextrow (v ${backend.columnType(SqlTestBackend.ColumnType.Json)})")
                _    <- Sql.insert[JsonTextRow].values(JsonTextRow(value)).run
                rows <- Sql.from[JsonTextRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                val decoded = Json.decode[Structure.Value](rows.head.v.text).getOrThrow
                assert(decoded == document, s"expected the document back, got ${rows.head.v.text}")
        }
    }

    // ── Chunk[JsonText] (JSON array) ─────────────────────────────────────────

    "Chunk[JsonText] round-trip" - {
        case class ChunkJsonTextRow(v: Chunk[JsonText]) derives CanEqual

        val documents = Chunk[Structure.Value](Structure.Value.Str("a"), Structure.Value.Integer(1L))
        val value     = documents.map(d => JsonText(Json.encode(d)))

        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE chunkjsontextrow (v ${backend.columnType(SqlTestBackend.ColumnType.JsonArray)})"
                )
                _    <- Sql.insert[ChunkJsonTextRow].values(ChunkJsonTextRow(value)).run
                rows <- Sql.from[ChunkJsonTextRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                val decoded = rows.head.v.map(t => Json.decode[Structure.Value](t.text).getOrThrow)
                assert(decoded == documents, s"expected both documents back, got ${rows.head.v}")
        }
    }

    // ── Maybe[Int] (nullable) ────────────────────────────────────────────────
    //
    // A nullable column is `Maybe` of a column, so the field derives like any other. Two rows are inserted, `Present` and `Absent`, and
    // checked independently since the read-back order across two single-row inserts is not guaranteed.

    "Maybe[Int] round-trip" - {
        case class MaybeIntRow(v: Maybe[Int]) derives CanEqual

        forEachBackend() { (backend, client, _) =>
            for
                _    <- client.executeRaw(s"CREATE TABLE maybeintrow (v ${backend.columnType(SqlTestBackend.ColumnType.Int)})")
                _    <- Sql.insert[MaybeIntRow].values(MaybeIntRow(Maybe(5))).run
                _    <- Sql.insert[MaybeIntRow].values(MaybeIntRow(Maybe.Absent)).run
                rows <- Sql.from[MaybeIntRow]("r").run
            yield
                assert(rows.size == 2, s"expected 2 rows, got ${rows.size}")
                assert(rows.exists(_.v == Maybe(5)), s"expected a row with Present(5), got $rows")
                assert(rows.exists(_.v == Maybe.Absent), s"expected a row with Absent, got $rows")
        }
    }

    // ── SqlNaming column casing ──────────────────────────────────────────────
    // An in-scope SqlNaming.SnakeCase casts the column identifiers on both the INSERT and the SELECT, so a row with
    // camelCase Scala fields round-trips through a table whose columns are snake_case. The decode matches by position
    // (the cased server columns do not equal the verbatim field names), which is correct for a full row in field order.
    "SqlNaming.SnakeCase casts column names and round-trips" - {
        case class CamelRow(userId: Long, firstName: String) derives CanEqual
        given SqlNaming = SqlNaming.SnakeCase

        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE camel_row (user_id ${backend.columnType(SqlTestBackend.ColumnType.BigInt)}, first_name ${backend.textColumnType})"
                )
                _    <- Sql.insert[CamelRow].values(CamelRow(7L, "amy")).run
                rows <- Sql.from[CamelRow]("r").run
            yield
                assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                assert(rows.head == CamelRow(7L, "amy"), s"expected CamelRow(7,amy), got ${rows.head}")
        }
    }

end SqlCodecConformanceTest
