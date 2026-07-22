package kyo.internal.postgres.types

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Frame
import kyo.Instant
import kyo.Maybe
import kyo.Span
import kyo.SqlDecodeArrayNullElementException
import kyo.SqlDecodeException
import kyo.SqlDecodeInetException
import kyo.SqlDecodeIntervalException
import kyo.SqlDecodeNumericException
import kyo.SqlDecodeUuidException
import kyo.internal.postgres.PostgresArrayReader

/** Decodes raw PostgreSQL wire bytes into a Scala value.
  *
  * Decoders handle both Text and Binary formats; the `format` argument at decode time tells the decoder which encoding the server used.
  * Text format is always accepted as a fallback so that results from SimpleQueryExchange (which uses text format exclusively) can be
  * decoded using the same codec layer.
  *
  * Throw contract: `read` throws a [[SqlDecodeException]] leaf directly for:
  *   - the NaN / +Infinity / -Infinity NUMERIC cases (the NUMERIC wire protocol has no Scala representation for these);
  *   - the INTERVAL wire-format cases with non-zero `months` or `days` components (Duration has no calendar-arithmetic representation,
  *     callers needing `java.time.Period` semantics should use a different schema);
  *   - INTERVAL text-format parses that PG renders in `'1 year 2 mons 00:01:02'` verbose form (months token unsupported);
  *   - UUID binary buffers whose length is not exactly 16 bytes.
  *
  * All other decode failures (e.g. `NumberFormatException` from `.toInt`) propagate as unchecked exceptions; callers (specifically
  * `PostgresRowReader`) catch them and wrap them in a [[SqlDecodeException]] leaf.
  *
  * @tparam A
  *   the Scala type this decoder produces
  */
trait PostgresDecoder[A]:
    /** OIDs this decoder recognises. */
    def oids: Set[Int]

    /** Decodes `bytes` from the given `format` into an `A`. */
    def read(format: Format, bytes: Span[Byte])(using Frame): A
end PostgresDecoder

object PostgresDecoder:

    import PostgresEncoder.*

    // --- Helpers ---

    private def text(bytes: Span[Byte]): String =
        new String(bytes.toArray, StandardCharsets.UTF_8)

    private def readBigEndianLong(bytes: Span[Byte], offset: Int): Long =
        ((bytes(offset) & 0xffL) << 56) |
            ((bytes(offset + 1) & 0xffL) << 48) |
            ((bytes(offset + 2) & 0xffL) << 40) |
            ((bytes(offset + 3) & 0xffL) << 32) |
            ((bytes(offset + 4) & 0xffL) << 24) |
            ((bytes(offset + 5) & 0xffL) << 16) |
            ((bytes(offset + 6) & 0xffL) << 8) |
            (bytes(offset + 7) & 0xffL)

    private def readBigEndianInt(bytes: Span[Byte], offset: Int): Int =
        ((bytes(offset) & 0xff) << 24) |
            ((bytes(offset + 1) & 0xff) << 16) |
            ((bytes(offset + 2) & 0xff) << 8) |
            (bytes(offset + 3) & 0xff)

    private def readBigEndianShort(bytes: Span[Byte], offset: Int): Short =
        (((bytes(offset) & 0xff) << 8) | (bytes(offset + 1) & 0xff)).toShort

    // --- Boolean ---

    val bool: PostgresDecoder[Boolean] = new PostgresDecoder[Boolean]:
        def oids = Set(OID_BOOL)
        def read(format: Format, bytes: Span[Byte])(using Frame): Boolean = format match
            case Format.Binary => bytes(0) != 0.toByte
            case Format.Text =>
                val s = text(bytes)
                s == "t" || s == "true" || s == "1" || s == "TRUE" || s == "yes" || s == "on"

    // --- Short ---

    val int2: PostgresDecoder[Short] = new PostgresDecoder[Short]:
        def oids = Set(OID_INT2)
        def read(format: Format, bytes: Span[Byte])(using Frame): Short = format match
            case Format.Binary => readBigEndianShort(bytes, 0)
            case Format.Text   => text(bytes).toShort

    // --- Int ---

    val int4: PostgresDecoder[Int] = new PostgresDecoder[Int]:
        def oids = Set(OID_INT4)
        def read(format: Format, bytes: Span[Byte])(using Frame): Int = format match
            case Format.Binary => readBigEndianInt(bytes, 0)
            case Format.Text   => text(bytes).toInt

    // --- Long ---

    val int8: PostgresDecoder[Long] = new PostgresDecoder[Long]:
        def oids = Set(OID_INT8)
        def read(format: Format, bytes: Span[Byte])(using Frame): Long = format match
            case Format.Binary => readBigEndianLong(bytes, 0)
            case Format.Text   => text(bytes).toLong

    // --- Float4 ---

    val float4: PostgresDecoder[Float] = new PostgresDecoder[Float]:
        def oids = Set(OID_FLOAT4)
        def read(format: Format, bytes: Span[Byte])(using Frame): Float = format match
            case Format.Binary => java.lang.Float.intBitsToFloat(readBigEndianInt(bytes, 0))
            case Format.Text   => text(bytes).toFloat

    // --- Float8 ---

    val float8: PostgresDecoder[Double] = new PostgresDecoder[Double]:
        def oids = Set(OID_FLOAT8)
        def read(format: Format, bytes: Span[Byte])(using Frame): Double = format match
            case Format.Binary => java.lang.Double.longBitsToDouble(readBigEndianLong(bytes, 0))
            case Format.Text   => text(bytes).toDouble

    // --- Numeric ---

    /** Decodes PostgreSQL NUMERIC from both Text and Binary wire formats.
      *
      * Binary format layout (PostgreSQL `numeric.c`):
      * {{{
      *   Int16  ndigits    -- number of base-10000 digits
      *   Int16  weight     -- weight of first digit (may be negative)
      *   UInt16 sign       -- 0x0000 = positive, 0x4000 = negative,
      *                        0xC000 = NaN, 0xD000 = +Inf, 0xF000 = -Inf
      *   UInt16 dscale     -- display scale
      *   Int16  digits[]   -- each in [0..9999], most-significant first
      * }}}
      *
      * Value reconstruction: sum_i(digits[i] * 10000^(weight - i)), then apply dscale.
      */
    val numeric: PostgresDecoder[BigDecimal] = new PostgresDecoder[BigDecimal]:
        def oids = Set(OID_NUMERIC)
        def read(format: Format, bytes: Span[Byte])(using frame: Frame): BigDecimal = format match
            case Format.Text =>
                val s = text(bytes)
                try BigDecimal(s)
                catch
                    case e: NumberFormatException =>
                        throw SqlDecodeNumericException(s, SqlDecodeNumericException.Subtype.Parse)
                end try
            case Format.Binary =>
                // Read the 4-field fixed header directly (8 bytes total).
                // ndigits: UInt16 BE at offset 0
                // weight:  Int16 BE (signed) at offset 2
                // sign:    UInt16 BE at offset 4
                // dscale:  UInt16 BE at offset 6
                val ndigits = readBigEndianShort(bytes, 0).toInt & 0xffff
                val weight  = readBigEndianShort(bytes, 2).toInt // signed
                val sign    = readBigEndianShort(bytes, 4).toInt & 0xffff
                val dscale  = readBigEndianShort(bytes, 6).toInt & 0xffff

                sign match
                    case 0xc000 =>
                        throw SqlDecodeNumericException("NaN", SqlDecodeNumericException.Subtype.NaN)
                    case 0xd000 =>
                        throw SqlDecodeNumericException("+Infinity", SqlDecodeNumericException.Subtype.PosInf)
                    case 0xf000 =>
                        throw SqlDecodeNumericException("-Infinity", SqlDecodeNumericException.Subtype.NegInf)
                    case _ => ()
                end match

                if ndigits == 0 then BigDecimal(0).setScale(dscale)
                else
                    // Read all digits; each is an unsigned Int16 BE at offsets 8, 10, 12, ...
                    val digits = Array.tabulate(ndigits)(i => readBigEndianShort(bytes, 8 + i * 2).toInt & 0xffff)

                    // Reconstruct the integer formed by concatenating digits in base-10000.
                    val unscaled = digits.foldLeft(BigInt(0)) { (acc, d) => acc * 10000 + d }

                    // Digit[0] is at base-10000 position `weight`, digit[k] at `weight - k`.
                    // The last digit is at position `weight - ndigits + 1`.
                    // Multiplying `unscaled` by 10000^(weight - ndigits + 1) gives the actual value.
                    val base10000exp = weight - ndigits + 1

                    val unsigned: BigDecimal =
                        if base10000exp >= 0 then
                            BigDecimal(unscaled) * BigDecimal(10).pow(4 * base10000exp)
                        else
                            // Negative exponent: construct BigDecimal with scale = 4 * |base10000exp|.
                            BigDecimal(unscaled, -(4 * base10000exp))

                    // Apply display scale to recover trailing zeros stripped during encoding.
                    val scaled = unsigned.setScale(dscale)

                    if sign == 0x4000 then -scaled else scaled
                end if

    // --- Text / Varchar ---

    val textDecoder: PostgresDecoder[String] = new PostgresDecoder[String]:
        // Accepts text OID, varchar OID (1043), and bpchar OID (1042).
        def oids                                                         = Set(OID_TEXT, 1043, 1042)
        def read(format: Format, bytes: Span[Byte])(using Frame): String = text(bytes)

    // --- JSON / JSONB ---
    // Handles both json (OID 114) and jsonb (OID 3802).
    //
    // Binary format (JSONB, OID 3802): first byte is the JSONB version (0x01); remaining bytes are UTF-8 JSON text.
    // Text format (JSON, OID 114) and JSONB text fallback: raw UTF-8 JSON text with no prefix.
    //
    // This decoder is registered for both OIDs so the same instance handles either column type.
    // If the server sends the value in Binary format and the OID is JSONB, the version byte is stripped.
    // For all Text-format values the full byte span is decoded as UTF-8 (no prefix to strip).

    val jsonDecoder: PostgresDecoder[String] = new PostgresDecoder[String]:
        def oids = Set(OID_JSON, OID_JSONB)
        def read(format: Format, bytes: Span[Byte])(using Frame): String =
            format match
                case Format.Binary if bytes.size > 0 && bytes(0) == 0x01.toByte =>
                    // JSONB binary: strip the version byte, decode the rest as UTF-8.
                    text(bytes.slice(1, bytes.size))
                case _ =>
                    // JSON text (OID 114), JSONB text fallback, or unrecognised binary: decode as-is.
                    text(bytes)

    // --- Bytea ---

    val bytea: PostgresDecoder[Span[Byte]] = new PostgresDecoder[Span[Byte]]:
        def oids = Set(OID_BYTEA)
        def read(format: Format, bytes: Span[Byte])(using Frame): Span[Byte] = format match
            case Format.Binary => bytes
            case Format.Text   =>
                // Text format for bytea: \x followed by hex digits (default bytea_output=hex in PG 9+).
                val s = text(bytes)
                if s.startsWith("\\x") then
                    val hex = s.drop(2)
                    Span.from(hex.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray)
                else
                    // Legacy octal format: \nnn or literal bytes. Return raw bytes as-is.
                    bytes
                end if

    // --- Timestamptz, kyo.Instant ---
    // Uses kyo.Instant (preferred over java.time.Instant).

    val timestamptz: PostgresDecoder[kyo.Instant] = new PostgresDecoder[kyo.Instant]:
        def oids = Set(OID_TIMESTAMPTZ)
        def read(format: Format, bytes: Span[Byte])(using Frame): kyo.Instant = format match
            case Format.Binary =>
                val pgMicros    = readBigEndianLong(bytes, 0)
                val epochMicros = pgMicros + PostgresEncoder.PG_EPOCH_MICROS
                val secs        = epochMicros / 1_000_000L
                val nanos       = ((epochMicros % 1_000_000L) * 1_000L).toInt
                kyo.Instant.fromJava(java.time.Instant.ofEpochSecond(secs, nanos))
            case Format.Text =>
                val s = text(bytes)
                // PostgreSQL text format for timestamptz: "YYYY-MM-DD HH:MM:SS.ffffff+TZ"
                // Normalise to ISO-8601 by replacing the space separator with T.
                val iso = s.replace(" ", "T")
                // Handle PG offset format like "+00" → "+00:00"
                val fixedIso = if iso.matches(".*[+-]\\d{2}$") then iso + ":00" else iso
                kyo.Instant.fromJava(java.time.OffsetDateTime.parse(fixedIso).toInstant)

    // --- Date, java.time.LocalDate ---
    // No Kyo equivalent for LocalDate; java.time.LocalDate is used.

    val date: PostgresDecoder[java.time.LocalDate] = new PostgresDecoder[java.time.LocalDate]:
        def oids = Set(OID_DATE)
        def read(format: Format, bytes: Span[Byte])(using Frame): java.time.LocalDate = format match
            case Format.Binary =>
                val pgDays  = readBigEndianInt(bytes, 0)
                val pgEpoch = java.time.LocalDate.of(2000, 1, 1)
                pgEpoch.plusDays(pgDays.toLong)
            case Format.Text =>
                java.time.LocalDate.parse(text(bytes))

    // --- Timestamp (no tz), java.time.LocalDateTime ---
    // No Kyo equivalent for LocalDateTime; java.time.LocalDateTime is used.

    val timestamp: PostgresDecoder[java.time.LocalDateTime] = new PostgresDecoder[java.time.LocalDateTime]:
        def oids = Set(OID_TIMESTAMP)
        def read(format: Format, bytes: Span[Byte])(using Frame): java.time.LocalDateTime = format match
            case Format.Binary =>
                val pgMicros = readBigEndianLong(bytes, 0)
                val pgEpoch  = java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 0)
                val secs     = pgMicros / 1_000_000L
                val nanos    = ((pgMicros % 1_000_000L) * 1_000L).toInt
                pgEpoch.plusSeconds(secs).plusNanos(nanos)
            case Format.Text =>
                val s = text(bytes).replace(" ", "T")
                java.time.LocalDateTime.parse(s)

    // --- Time, java.time.LocalTime ---
    // No Kyo equivalent for LocalTime; java.time.LocalTime is used.

    val time: PostgresDecoder[java.time.LocalTime] = new PostgresDecoder[java.time.LocalTime]:
        def oids = Set(OID_TIME)
        def read(format: Format, bytes: Span[Byte])(using Frame): java.time.LocalTime = format match
            case Format.Binary =>
                val micros = readBigEndianLong(bytes, 0)
                java.time.LocalTime.ofNanoOfDay(micros * 1_000L)
            case Format.Text =>
                java.time.LocalTime.parse(text(bytes))

    // --- Timetz, java.time.OffsetTime ---
    // Wire: 12-byte big-endian struct: Int64 microseconds-of-day, Int32 offset_seconds (negated).
    // PG wire convention: the offset field is the *negated* total seconds of the ZoneOffset so that
    // a UTC-05:00 value is stored as +18000.  We negate the wire value to recover the Java offset.
    // Text format: ISO-8601 extended, e.g. "13:45:30.123456+05:30"; parsed via OffsetTime.parse.

    val timetz: PostgresDecoder[java.time.OffsetTime] = new PostgresDecoder[java.time.OffsetTime]:
        def oids = Set(OID_TIMETZ)
        def read(format: Format, bytes: Span[Byte])(using Frame): java.time.OffsetTime = format match
            case Format.Binary =>
                val micros        = readBigEndianLong(bytes, 0)
                val offsetNegated = readBigEndianInt(bytes, 8)
                val localTime     = java.time.LocalTime.ofNanoOfDay(micros * 1_000L)
                val offset        = java.time.ZoneOffset.ofTotalSeconds(-offsetNegated)
                java.time.OffsetTime.of(localTime, offset)
            case Format.Text =>
                java.time.OffsetTime.parse(text(bytes))

    // --- INTERVAL, java.time.Duration ---
    // Wire: 16-byte big-endian struct: Int64 microseconds, Int32 days, Int32 months.
    // Months != 0 or days != 0 raise a SqlDecodeIntervalException; java.time.Duration cannot represent
    // calendar-relative components without data loss (e.g. DST-sensitive calendar days).
    // Text format: try ISO-8601 parse (java.time.Duration.parse); PG verbose format with
    // months/years raises a SqlDecodeIntervalException directing the caller to cast to ISO-formatted text.

    val interval: PostgresDecoder[java.time.Duration] = new PostgresDecoder[java.time.Duration]:
        def oids = Set(OID_INTERVAL)
        def read(format: Format, bytes: Span[Byte])(using frame: Frame): java.time.Duration = format match
            case Format.Binary =>
                val micros = readBigEndianLong(bytes, 0)
                val days   = readBigEndianInt(bytes, 8)
                val months = readBigEndianInt(bytes, 12)
                if months != 0 then
                    throw SqlDecodeIntervalException("months", months.toString)
                end if
                if days != 0 then
                    throw SqlDecodeIntervalException("days", days.toString)
                end if
                java.time.Duration.ofSeconds(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L)
            case Format.Text =>
                val s = text(bytes)
                // Attempt ISO-8601 parse first (e.g. "PT1H", "PT-30S").
                // PG verbose format (e.g. "01:00:00", "1 year 2 mons ...") may also be received
                // in the simple-query path.
                try java.time.Duration.parse(s)
                catch
                    case _: java.time.format.DateTimeParseException =>
                        // PG verbose INTERVAL text (e.g. "01:30:00" or "1 year 2 mons 00:01:02").
                        // Attempt to parse hh:mm:ss as a fallback (no months/days, those would be
                        // data-losing for Duration). If the text does not match hh:mm:ss, raise a
                        // typed error suggesting the caller cast to ISO format.
                        val hhmmss = """^(-?)(\d+):(\d{2}):(\d{2})(?:\.(\d+))?$""".r
                        s match
                            case hhmmss(sign, hh, mm, ss, fracOrNull) =>
                                val totalSecs = hh.toLong * 3600L + mm.toLong * 60L + ss.toLong
                                val fracStr   = if fracOrNull == null then "" else fracOrNull
                                val nanos =
                                    if fracStr.isEmpty then 0L
                                    else
                                        // Pad or truncate to 9 digits for nanoseconds.
                                        val padded = (fracStr + "000000000").take(9)
                                        padded.toLong
                                val posDuration = java.time.Duration.ofSeconds(totalSecs, nanos)
                                if sign == "-" then posDuration.negated() else posDuration
                            case _ =>
                                throw SqlDecodeIntervalException("text", s)
                        end match
                end try

    // --- INTERVAL, java.time.Period ---
    // Wire: 16-byte big-endian struct: Int64 microseconds, Int32 days, Int32 months.
    // Period has no time component, microseconds must be zero; non-zero raises a SqlDecodeIntervalException.
    // Text format: attempt ISO-8601 period parse (e.g. "P1Y6M15D"); raises a SqlDecodeIntervalException
    // for values that cannot be parsed as a Period or that carry a time component.

    val intervalPeriod: PostgresDecoder[java.time.Period] = new PostgresDecoder[java.time.Period]:
        def oids = Set(OID_INTERVAL)
        def read(format: Format, bytes: Span[Byte])(using frame: Frame): java.time.Period = format match
            case Format.Binary =>
                val micros = readBigEndianLong(bytes, 0)
                val days   = readBigEndianInt(bytes, 8)
                val months = readBigEndianInt(bytes, 12)
                if micros != 0L then
                    throw SqlDecodeIntervalException("microseconds", micros.toString)
                end if
                java.time.Period.of(0, months, days).normalized()
            case Format.Text =>
                val s = text(bytes)
                try
                    val p = java.time.Period.parse(s)
                    p
                catch
                    case _: java.time.format.DateTimeParseException =>
                        throw SqlDecodeIntervalException("text", s)
                end try

    // --- InetAddress (PG inet, OID 869) ---
    // Binary wire: family(1) + prefix_bits(1) + is_cidr(1) + addr_len(1) + addr_bytes(N).
    // Text wire: standard dotted-decimal (IPv4) or colon-hex (IPv6) notation.
    // Unknown address family raises a SqlDecodeInetException.

    val inet: PostgresDecoder[java.net.InetAddress] = new PostgresDecoder[java.net.InetAddress]:
        def oids = Set(OID_INET)
        def read(format: Format, bytes: Span[Byte])(using frame: Frame): java.net.InetAddress =
            decodeInetOrCidr("inet", format, bytes)

    // --- CIDR (PG cidr, OID 650) ---
    // Same binary wire shape as inet (`family + prefix_bits + is_cidr + addr_len + addr_bytes`); the
    // `is_cidr` byte is 1 for cidr. PostgreSQL's text representation drops the trailing `/prefix` when
    // the prefix equals the host width (`/32` for IPv4, `/128` for IPv6), which makes `InetAddress.getByName`
    // accept it. Text representations with an explicit `/prefix` mask (e.g. `192.168.1.0/24`) cannot be
    // round-tripped as `java.net.InetAddress` because the class carries no prefix; such values raise
    // a `SqlDecodeInetException` for the user to handle.
    val cidr: PostgresDecoder[java.net.InetAddress] = new PostgresDecoder[java.net.InetAddress]:
        def oids = Set(OID_CIDR)
        def read(format: Format, bytes: Span[Byte])(using frame: Frame): java.net.InetAddress =
            format match
                case Format.Binary => decodeInetOrCidr("cidr", format, bytes)
                case Format.Text =>
                    val s        = text(bytes)
                    val slashIdx = s.indexOf('/')
                    if slashIdx < 0 then java.net.InetAddress.getByName(s)
                    else
                        val addrPart  = s.substring(0, slashIdx)
                        val mask      = s.substring(slashIdx + 1).toInt
                        val hostWidth = if addrPart.contains(':') then 128 else 32
                        if mask < hostWidth then
                            throw SqlDecodeInetException("cidr", -1, mask, hostWidth)
                        end if
                        java.net.InetAddress.getByName(addrPart)
                    end if

    // Shared binary-format decoder used by both `inet` and `cidr`, same wire layout
    // (`family + prefix_bits + is_cidr + addr_len + addr_bytes`); the `is_cidr` byte is not surfaced
    // to the caller because `java.net.InetAddress` cannot carry that distinction.
    private def decodeInetOrCidr(typeName: String, format: Format, bytes: Span[Byte])(using frame: Frame): java.net.InetAddress =
        format match
            case Format.Binary =>
                if bytes.size < 4 then
                    throw SqlDecodeInetException(typeName, -1, -1, bytes.size)
                end if
                val family  = bytes(0)
                val addrLen = bytes(3).toInt & 0xff
                if bytes.size < 4 + addrLen then
                    throw SqlDecodeInetException(typeName, family.toInt & 0xff, addrLen, bytes.size)
                end if
                val addrBytes = bytes.slice(4, 4 + addrLen).toArray
                if family == 2.toByte || family == 3.toByte then
                    java.net.InetAddress.getByAddress(addrBytes)
                else
                    throw SqlDecodeInetException(typeName, family.toInt & 0xff, addrLen, bytes.size)
                end if
            case Format.Text =>
                java.net.InetAddress.getByName(text(bytes))
    end decodeInetOrCidr

    // --- UUID ---
    // Binary: 16 bytes big-endian (mostSignificantBits Int64, leastSignificantBits Int64).
    // Text: canonical 36-character hyphenated form (e.g. "550e8400-e29b-41d4-a716-446655440000").

    val uuid: PostgresDecoder[java.util.UUID] = new PostgresDecoder[java.util.UUID]:
        def oids = Set(OID_UUID)
        def read(format: Format, bytes: Span[Byte])(using frame: Frame): java.util.UUID = format match
            case Format.Binary =>
                if bytes.size != 16 then
                    throw SqlDecodeUuidException(bytes.size)
                end if
                val msb = readBigEndianLong(bytes, 0)
                val lsb = readBigEndianLong(bytes, 8)
                new java.util.UUID(msb, lsb)
            case Format.Text =>
                java.util.UUID.fromString(text(bytes))

    // --- PG binary array decoders ---
    //
    // These decoders parse the PostgreSQL binary array wire format using [[PostgresArrayReader]]
    // and return a [[Chunk]] of decoded elements. Both Binary and Text format codes are accepted;
    // in Text format the bytes are treated as raw UTF-8 text and parsed accordingly.

    /** Decodes a PostgreSQL `int4[]` (OID 1007) column from binary wire format into a [[Chunk[Int]]]. */
    val int4Array: PostgresDecoder[Chunk[Int]] = new PostgresDecoder[Chunk[Int]]:
        def oids = Set(PostgresEncoder.OID_INT4_ARRAY)
        def read(format: Format, bytes: Span[Byte])(using frame: Frame): Chunk[Int] =
            val arr     = new PostgresArrayReader(bytes, frame)
            val count   = arr.openArray()
            val builder = Chunk.newBuilder[Int]
            var i       = 0
            while i < count do
                arr.nextElement() match
                    case Maybe.Present(elemBytes) =>
                        builder += PostgresDecoder.int4.read(Format.Binary, elemBytes)
                    case Maybe.Absent =>
                        throw SqlDecodeArrayNullElementException("Int", i)
                end match
                i += 1
            end while
            builder.result()
        end read

    /** Decodes a PostgreSQL `text[]` (OID 1009) column from binary wire format into a [[Chunk[String]]]. */
    val textArray: PostgresDecoder[Chunk[String]] = new PostgresDecoder[Chunk[String]]:
        def oids = Set(PostgresEncoder.OID_TEXT_ARRAY)
        def read(format: Format, bytes: Span[Byte])(using frame: Frame): Chunk[String] =
            val arr     = new PostgresArrayReader(bytes, frame)
            val count   = arr.openArray()
            val builder = Chunk.newBuilder[String]
            var i       = 0
            while i < count do
                arr.nextElement() match
                    case Maybe.Present(elemBytes) =>
                        builder += PostgresDecoder.textDecoder.read(Format.Binary, elemBytes)
                    case Maybe.Absent =>
                        throw SqlDecodeArrayNullElementException("String", i)
                end match
                i += 1
            end while
            builder.result()
        end read

    /** Decodes a PostgreSQL `jsonb[]` (OID 3807) column from binary wire format into a [[Chunk[String]]]. Each element is decoded by
      * [[jsonDecoder]], the 1-byte JSONB version prefix (0x01) is stripped and the remainder returned as a UTF-8 JSON-text string.
      */
    val jsonbArray: PostgresDecoder[Chunk[String]] = new PostgresDecoder[Chunk[String]]:
        def oids = Set(PostgresEncoder.OID_JSONB_ARRAY)
        def read(format: Format, bytes: Span[Byte])(using frame: Frame): Chunk[String] =
            val arr     = new PostgresArrayReader(bytes, frame)
            val count   = arr.openArray()
            val builder = Chunk.newBuilder[String]
            var i       = 0
            while i < count do
                arr.nextElement() match
                    case Maybe.Present(elemBytes) =>
                        builder += PostgresDecoder.jsonDecoder.read(Format.Binary, elemBytes)
                    case Maybe.Absent =>
                        throw SqlDecodeArrayNullElementException("String", i)
                end match
                i += 1
            end while
            builder.result()
        end read

    // --- Given aliases for implicit/macro summoning ---

    /** Given alias so macros can `Expr.summon[PostgresDecoder[Boolean]]`. */
    given PostgresDecoder[Boolean]                 = bool
    given PostgresDecoder[Short]                   = int2
    given PostgresDecoder[Int]                     = int4
    given PostgresDecoder[Long]                    = int8
    given PostgresDecoder[Float]                   = float4
    given PostgresDecoder[Double]                  = float8
    given PostgresDecoder[BigDecimal]              = numeric
    given PostgresDecoder[String]                  = textDecoder
    given PostgresDecoder[Span[Byte]]              = bytea
    given PostgresDecoder[kyo.Instant]             = timestamptz
    given PostgresDecoder[java.time.LocalDate]     = date
    given PostgresDecoder[java.time.LocalDateTime] = timestamp
    given PostgresDecoder[java.time.LocalTime]     = time
    given PostgresDecoder[java.time.OffsetTime]    = timetz
    given PostgresDecoder[java.time.Duration]      = interval
    given PostgresDecoder[java.time.Period]        = intervalPeriod
    given PostgresDecoder[java.util.UUID]          = uuid
    given PostgresDecoder[java.net.InetAddress]    = inet

end PostgresDecoder
