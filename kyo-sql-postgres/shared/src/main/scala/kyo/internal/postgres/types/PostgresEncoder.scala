package kyo.internal.postgres.types

import java.nio.charset.StandardCharsets
import kyo.Instant
import kyo.Span
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.db.Idiom
import kyo.internal.postgres.PostgresBufferWriter
import scala.annotation.tailrec

/** Encodes a Scala value into PostgreSQL wire bytes for use in extended-protocol Bind messages.
  *
  * @tparam A
  *   the Scala type this encoder handles
  */
trait PostgresEncoder[A]:
    /** The PostgreSQL OID for this type. */
    def oid: Int

    /** The wire format (Text or Binary) used for encoding. */
    def format: Format

    /** Writes the value into `buf`. The buffer is pre-positioned; the encoder must not write a length prefix, that is added by the Bind
      * marshaller.
      */
    def write(value: A, buf: PostgresBufferWriter): Unit
end PostgresEncoder

object PostgresEncoder:

    /** The dialect this wire layer encodes for. Every rejection of a value another dialect owns names it, and it is the key
      * [[kyo.SqlCodec.Writer.Payload]] carries for a PostgreSQL-owned extension type.
      */
    val dialectId: Idiom.Id = Idiom.Id("postgres")

    // --- OID constants ---

    /** PostgreSQL's `InvalidOid`, which the protocol uses for "no type named here".
      *
      * On the decode path it is what a row assembled without server column metadata carries, so a decoder reading it knows only the value's
      * byte width and not which type produced it.
      */
    val OID_UNSPECIFIED = 0

    val OID_BOOL        = 16
    val OID_BYTEA       = 17
    val OID_INT8        = 20
    val OID_INT2        = 21
    val OID_INT4        = 23
    val OID_TEXT        = 25
    val OID_JSON        = 114  // json (bare UTF-8 text)
    val OID_FLOAT4      = 700
    val OID_FLOAT8      = 701
    val OID_NUMERIC     = 1700
    val OID_DATE        = 1082
    val OID_TIME        = 1083
    val OID_TIMETZ      = 1266
    val OID_TIMESTAMP   = 1114
    val OID_TIMESTAMPTZ = 1184
    val OID_INTERVAL    = 1186
    val OID_JSONB       = 3802 // jsonb (version byte 0x01 + UTF-8 text, binary format)
    val OID_UUID        = 2950
    // The `inet` OID, resolvable through PostgresTypes.custom[String]("inet") even though kyo-sql ships no
    // dedicated address type.
    val OID_INET = 869

    // Array OIDs
    val OID_INT4_ARRAY  = 1007 // _int4 (int4[])
    val OID_TEXT_ARRAY  = 1009 // _text (text[])
    val OID_JSONB_ARRAY = 3807 // _jsonb (jsonb[]), written by jsonbArrayBinary and read by PostgresDecoder.jsonbArray

    // Microseconds from Unix epoch (1970-01-01) to PostgreSQL epoch (2000-01-01).
    val PG_EPOCH_MICROS = 946_684_800_000_000L

    // --- Boolean ---

    val boolBinary: PostgresEncoder[Boolean] = new PostgresEncoder[Boolean]:
        def oid: Int       = OID_BOOL
        def format: Format = Format.Binary
        def write(value: Boolean, buf: PostgresBufferWriter): Unit =
            buf.writeByte(if value then 1.toByte else 0.toByte)

    // --- Short (Int2) ---

    val int2Binary: PostgresEncoder[Short] = new PostgresEncoder[Short]:
        def oid: Int       = OID_INT2
        def format: Format = Format.Binary
        def write(value: Short, buf: PostgresBufferWriter): Unit =
            buf.writeInt16(value)

    // --- Int (Int4) ---

    val int4Binary: PostgresEncoder[Int] = new PostgresEncoder[Int]:
        def oid: Int       = OID_INT4
        def format: Format = Format.Binary
        def write(value: Int, buf: PostgresBufferWriter): Unit =
            buf.writeInt32(value)

    /** The encoder a NULL parameter carries, declaring [[OID_UNSPECIFIED]] so the server infers the parameter's type from the position it is
      * used in.
      *
      * A NULL slot is signalled by a length of `-1` and no bytes, so [[write]] is unreachable: `BoundParam.encoded` answers `Absent` for a
      * NULL and the marshaller never asks an encoder to produce anything. What the encoder still decides is the OID in the `Parse` message,
      * and that one is load-bearing. Naming a concrete type there tells the server the parameter IS that type, so a NULL destined for a
      * `TEXT` column arrives declared as `INT4` and the insert is rejected on a type mismatch that the caller never wrote. Oid 0 is the
      * protocol's own "no type named here", which is what lets the column drive inference.
      */
    val nullUnspecified: PostgresEncoder[Nothing] = new PostgresEncoder[Nothing]:
        def oid: Int       = OID_UNSPECIFIED
        def format: Format = Format.Binary
        def write(value: Nothing, buf: PostgresBufferWriter): Unit =
            // Unreachable: a NULL parameter contributes no bytes, and `Nothing` has no inhabitant to pass here.
            ()

    // --- Long (Int8) ---

    val int8Binary: PostgresEncoder[Long] = new PostgresEncoder[Long]:
        def oid: Int       = OID_INT8
        def format: Format = Format.Binary
        def write(value: Long, buf: PostgresBufferWriter): Unit =
            buf.writeInt32(((value >> 32) & 0xffffffffL).toInt)
            buf.writeInt32((value & 0xffffffffL).toInt)

    // --- Float4 ---

    val float4Binary: PostgresEncoder[Float] = new PostgresEncoder[Float]:
        def oid: Int       = OID_FLOAT4
        def format: Format = Format.Binary
        def write(value: Float, buf: PostgresBufferWriter): Unit =
            buf.writeInt32(java.lang.Float.floatToIntBits(value))

    // --- Float8 ---

    val float8Binary: PostgresEncoder[Double] = new PostgresEncoder[Double]:
        def oid: Int       = OID_FLOAT8
        def format: Format = Format.Binary
        def write(value: Double, buf: PostgresBufferWriter): Unit =
            val bits = java.lang.Double.doubleToLongBits(value)
            buf.writeInt32(((bits >> 32) & 0xffffffffL).toInt)
            buf.writeInt32((bits & 0xffffffffL).toInt)
        end write

    // --- Numeric ---

    /** Encodes [[BigDecimal]] as PostgreSQL's text NUMERIC format.
      *
      * NB: do NOT use `value.underlying().toPlainString`, Scala Native's javalib has a bug where the integer digits are zeroed out.
      * `value.toString` (scala.math.BigDecimal) is correct on every platform.
      */
    val numericText: PostgresEncoder[BigDecimal] = new PostgresEncoder[BigDecimal]:
        def oid: Int       = OID_NUMERIC
        def format: Format = Format.Text
        def write(value: BigDecimal, buf: PostgresBufferWriter): Unit =
            buf.writeBytes(value.toString.getBytes(StandardCharsets.UTF_8))

    /** Encodes [[BigDecimal]] as PostgreSQL's variable-precision base-10000 binary NUMERIC format.
      *
      * Wire layout (PostgreSQL `numeric.c` `numericvar_to_binary`):
      * {{{
      *   Int16  ndigits    -- number of base-10000 digits
      *   Int16  weight     -- weight of first digit in base-10000 (may be negative)
      *   UInt16 sign       -- 0x0000 = positive, 0x4000 = negative
      *   UInt16 dscale     -- display scale (decimal digits after the decimal point)
      *   Int16  digits[]   -- each digit in [0..9999], most-significant first
      * }}}
      *
      * UInt16 values (sign, dscale) are written via `writeInt16(x.toShort)`, the bit pattern is identical; unsigned interpretation is the
      * caller's responsibility when reading.
      *
      * Note: dscale is truncated to Int16 range (max 32767). Values exceeding this are beyond PG's practical NUMERIC precision limit.
      */
    val numericBinary: PostgresEncoder[BigDecimal] = new PostgresEncoder[BigDecimal]:
        def oid: Int       = OID_NUMERIC
        def format: Format = Format.Binary

        /** Converts a non-negative BigInt into a list of base-10000 digits, most-significant first. */
        @tailrec private def toBase10000(n: BigInt, acc: List[Int] = List.empty): List[Int] =
            if n == 0 then acc
            else toBase10000(n / 10000, (n % 10000).toInt :: acc)

        def write(value: BigDecimal, buf: PostgresBufferWriter): Unit =
            // Special-case zero: header with all zeros, no digit payload.
            if value.signum == 0 then
                buf.writeInt16(0.toShort) // ndigits
                buf.writeInt16(0.toShort) // weight
                buf.writeInt16(0.toShort) // sign = positive
                buf.writeInt16(0.toShort) // dscale
            else
                val sign = if value.signum < 0 then 0x4000 else 0x0000
                val abs  = value.abs

                // Display scale: number of decimal digits after the decimal point.
                // Negative scale (e.g. 1E+100 has scale -100) is clamped to 0.
                val dscale = math.max(0, abs.scale)

                // Number of base-10000 digits needed to cover the fractional part.
                // ceil(dscale / 4) ensures the fractional digits align to the base-10000 boundary.
                val fracBase10000Digits = (dscale + 3) / 4

                // Scale the absolute value by 10^(4*F) so that all fractional digits land in the integer part.
                val scale10000 = BigDecimal(10).pow(4 * fracBase10000Digits)
                val scaledInt  = (abs * scale10000).toBigInt

                // Convert to base-10000, most-significant first.
                val rawDigits = toBase10000(scaledInt)

                // Pad on the left so the array is at least fracBase10000Digits long.
                // This ensures fractional-only values like 0.000001 have enough leading zeros.
                val paddedDigits =
                    if rawDigits.size < fracBase10000Digits then
                        List.fill(fracBase10000Digits - rawDigits.size)(0) ++ rawDigits
                    else rawDigits

                // Strip leading zero digits; count how many were removed (needed for weight calculation).
                val afterLeading = paddedDigits.dropWhile(_ == 0)
                val leadingZeros = paddedDigits.size - afterLeading.size

                // Strip trailing zero digits: the dscale field preserves display precision,
                // so trailing zeros in the digit array are redundant and PostgreSQL omits them.
                val allDigits = afterLeading.reverse.dropWhile(_ == 0).reverse

                val ndigits = allDigits.size

                // Weight = position of the most-significant (non-stripped) digit relative to the decimal point,
                // measured in base-10000 units.
                // Base weight = intCount - 1 where intCount = paddedDigits.size - fracBase10000Digits.
                // Subtract leadingZeros for the digits that were stripped from the front.
                val weight = (paddedDigits.size - fracBase10000Digits - 1) - leadingZeros

                buf.writeInt16(ndigits.toShort)
                buf.writeInt16(weight.toShort)
                buf.writeInt16(sign.toShort)   // UInt16 via toShort bit pattern
                buf.writeInt16(dscale.toShort) // UInt16 via toShort bit pattern
                for d <- allDigits do buf.writeInt16(d.toShort)
            end if
        end write
    end numericBinary

    // --- Text ---

    val textText: PostgresEncoder[String] = new PostgresEncoder[String]:
        def oid: Int       = OID_TEXT
        def format: Format = Format.Text
        def write(value: String, buf: PostgresBufferWriter): Unit =
            buf.writeBytes(value.getBytes(StandardCharsets.UTF_8))

    // --- JSONB (OID 3802), version byte 0x01 + UTF-8 text (binary format) ---
    // PostgreSQL jsonb binary wire format: 1-byte version prefix (0x01) followed by raw UTF-8 JSON text.

    val jsonbBinary: PostgresEncoder[String] = new PostgresEncoder[String]:
        def oid: Int       = OID_JSONB
        def format: Format = Format.Binary
        def write(value: String, buf: PostgresBufferWriter): Unit =
            buf.writeByte(0x01.toByte) // JSONB version byte
            buf.writeBytes(value.getBytes(StandardCharsets.UTF_8))

    // --- Bytea ---

    val byteaBinary: PostgresEncoder[Span[Byte]] = new PostgresEncoder[Span[Byte]]:
        def oid: Int       = OID_BYTEA
        def format: Format = Format.Binary
        def write(value: Span[Byte], buf: PostgresBufferWriter): Unit =
            buf.writeBytes(value)

    // --- Timestamptz, kyo.Instant ---
    // Uses kyo.Instant (preferred over java.time.Instant).
    // Wire: 8-byte int64 microseconds since PostgreSQL epoch (2000-01-01 00:00:00 UTC).

    val timestamptzBinary: PostgresEncoder[kyo.Instant] = new PostgresEncoder[kyo.Instant]:
        def oid: Int       = OID_TIMESTAMPTZ
        def format: Format = Format.Binary
        def write(value: kyo.Instant, buf: PostgresBufferWriter): Unit =
            val jInstant    = value.toJava
            val epochMicros = jInstant.getEpochSecond * 1_000_000L + jInstant.getNano / 1_000L
            val pgMicros    = epochMicros - PG_EPOCH_MICROS
            buf.writeInt32(((pgMicros >> 32) & 0xffffffffL).toInt)
            buf.writeInt32((pgMicros & 0xffffffffL).toInt)
        end write

    // --- Date, java.time.LocalDate ---
    // No Kyo equivalent for LocalDate (date without time zone); java.time.LocalDate is used.
    // Wire: 4-byte int32 days since PostgreSQL epoch (2000-01-01).

    val dateBinary: PostgresEncoder[java.time.LocalDate] = new PostgresEncoder[java.time.LocalDate]:
        def oid: Int       = OID_DATE
        def format: Format = Format.Binary
        def write(value: java.time.LocalDate, buf: PostgresBufferWriter): Unit =
            val pgEpoch = java.time.LocalDate.of(2000, 1, 1)
            val days    = value.toEpochDay - pgEpoch.toEpochDay
            buf.writeInt32(days.toInt)
        end write

    // --- Timestamp (no tz), java.time.LocalDateTime ---
    // No Kyo equivalent for LocalDateTime (datetime without time zone); java.time.LocalDateTime is used.
    // Wire: 8-byte int64 microseconds since PostgreSQL epoch (2000-01-01 00:00:00).

    val timestampBinary: PostgresEncoder[java.time.LocalDateTime] = new PostgresEncoder[java.time.LocalDateTime]:
        def oid: Int       = OID_TIMESTAMP
        def format: Format = Format.Binary
        def write(value: java.time.LocalDateTime, buf: PostgresBufferWriter): Unit =
            val pgEpoch  = java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 0)
            val duration = java.time.Duration.between(pgEpoch, value)
            val pgMicros = duration.getSeconds * 1_000_000L + duration.getNano / 1_000L
            buf.writeInt32(((pgMicros >> 32) & 0xffffffffL).toInt)
            buf.writeInt32((pgMicros & 0xffffffffL).toInt)
        end write

    // --- Time, java.time.LocalTime ---
    // No Kyo equivalent for LocalTime (time without date/zone); java.time.LocalTime is used.
    // Wire: 8-byte int64 microseconds since midnight.

    val timeBinary: PostgresEncoder[java.time.LocalTime] = new PostgresEncoder[java.time.LocalTime]:
        def oid: Int       = OID_TIME
        def format: Format = Format.Binary
        def write(value: java.time.LocalTime, buf: PostgresBufferWriter): Unit =
            val micros = value.toNanoOfDay / 1_000L
            buf.writeInt32(((micros >> 32) & 0xffffffffL).toInt)
            buf.writeInt32((micros & 0xffffffffL).toInt)
        end write

    // --- Timetz, java.time.OffsetTime ---
    // Wire: 12-byte big-endian struct: Int64 microseconds-of-day, Int32 offset_seconds (negated relative to UTC).
    // PG wire convention: the offset field stores the *negated* total seconds of the ZoneOffset so that
    // a UTC-05:00 value is stored as +18000 (positive = west of UTC). java.time.ZoneOffset.getTotalSeconds
    // returns positive for east-of-UTC, so we negate it to match the PG convention.

    val timetzBinary: PostgresEncoder[java.time.OffsetTime] = new PostgresEncoder[java.time.OffsetTime]:
        def oid: Int       = OID_TIMETZ
        def format: Format = Format.Binary
        def write(value: java.time.OffsetTime, buf: PostgresBufferWriter): Unit =
            val micros        = value.toLocalTime.toNanoOfDay / 1_000L
            val offsetSeconds = -value.getOffset.getTotalSeconds
            buf.writeInt32(((micros >> 32) & 0xffffffffL).toInt)
            buf.writeInt32((micros & 0xffffffffL).toInt)
            buf.writeInt32(offsetSeconds)
        end write

    // --- INTERVAL, java.time.Duration ---
    // Wire: 16-byte big-endian struct: Int64 microseconds, Int32 days (always 0), Int32 months (always 0).
    // java.time.Duration carries no calendar-day or month/year component; both are encoded as zero.
    // On encode overflow (seconds x 1_000_000L exceeds Int64), an ArithmeticException from
    // Math.multiplyExact is wrapped as SqlDecodeException.

    val intervalBinary: PostgresEncoder[java.time.Duration] = new PostgresEncoder[java.time.Duration]:
        def oid: Int       = OID_INTERVAL
        def format: Format = Format.Binary
        def write(value: java.time.Duration, buf: PostgresBufferWriter): Unit =
            // ArithmeticException propagates to the caller (PostgresParamWriter.duration) which
            // has a Frame in scope and wraps it as SqlDecodeException.
            val micros = java.lang.Math.multiplyExact(value.getSeconds, 1_000_000L) + value.getNano / 1000
            buf.writeInt32(((micros >> 32) & 0xffffffffL).toInt)
            buf.writeInt32((micros & 0xffffffffL).toInt)
            buf.writeInt32(0) // days = 0
            buf.writeInt32(0) // months = 0
        end write

    // --- INTERVAL, java.time.Period ---
    // Wire: 16-byte big-endian struct: Int64 microseconds (always 0), Int32 days, Int32 months.
    // java.time.Period carries only years/months/days, no time component; microseconds is always 0.
    // months = period.toTotalMonths (years * 12 + months field); days = period.getDays.

    val intervalPeriodBinary: PostgresEncoder[java.time.Period] = new PostgresEncoder[java.time.Period]:
        def oid: Int       = OID_INTERVAL
        def format: Format = Format.Binary
        def write(value: java.time.Period, buf: PostgresBufferWriter): Unit =
            val months = value.toTotalMonths
            val days   = value.getDays
            // microseconds = 0 (Period has no time component)
            buf.writeInt32(0) // high 32 bits of µs Int64
            buf.writeInt32(0) // low 32 bits of µs Int64
            buf.writeInt32(days.toInt)
            buf.writeInt32(months.toInt)
        end write

    // --- UUID ---
    // Wire: 16 bytes big-endian, mostSignificantBits (Int64) followed by leastSignificantBits (Int64).

    val uuidBinary: PostgresEncoder[java.util.UUID] = new PostgresEncoder[java.util.UUID]:
        def oid: Int       = OID_UUID
        def format: Format = Format.Binary
        def write(value: java.util.UUID, buf: PostgresBufferWriter): Unit =
            val msb = value.getMostSignificantBits
            val lsb = value.getLeastSignificantBits
            buf.writeInt32(((msb >> 32) & 0xffffffffL).toInt)
            buf.writeInt32((msb & 0xffffffffL).toInt)
            buf.writeInt32(((lsb >> 32) & 0xffffffffL).toInt)
            buf.writeInt32((lsb & 0xffffffffL).toInt)
        end write

    // --- PG array binary format ---
    //
    // Binary array wire layout (PostgreSQL arrayfuncs.c):
    //   Int32 ndim       -- number of dimensions (1 for 1-D arrays)
    //   Int32 hasnulls   -- 1 if any element is NULL, 0 otherwise
    //   Int32 elemOid    -- OID of the element type
    //   [for each dimension:]
    //     Int32 dim_size -- element count
    //     Int32 lbound   -- lower bound (1 for PG arrays)
    //   [for each element:]
    //     Int32 elemLen  -- byte length of element, or -1 for NULL
    //     <elemLen bytes> -- element data (omitted for NULL)

    /** Builds a binary-format PostgreSQL array encoder for element type `A`.
      *
      * @param elemEncoder
      *   encoder for a single element; [[PostgresEncoder.write]] is called once per element into a temporary buffer whose byte count is
      *   then written as the element-length prefix
      * @param elemOid
      *   PostgreSQL OID for the element type (e.g. [[OID_INT4]] = 23, [[OID_TEXT]] = 25)
      * @param arrayOid
      *   PostgreSQL OID for the array type (e.g. [[OID_INT4_ARRAY]] = 1007, [[OID_TEXT_ARRAY]] = 1009)
      */
    def arrayEncoder[A](elemEncoder: PostgresEncoder[A], elemOid: Int, arrayOid: Int): PostgresEncoder[Seq[A]] =
        new PostgresEncoder[Seq[A]]:
            def oid: Int       = arrayOid
            def format: Format = Format.Binary
            def write(value: Seq[A], buf: PostgresBufferWriter): Unit =
                buf.writeInt32(1) // ndim = 1
                buf.writeInt32(0) // hasnulls = 0 (null elements not supported; use Maybe wrappers)
                buf.writeInt32(elemOid)
                buf.writeInt32(value.size) // dim_size
                buf.writeInt32(1)          // lbound = 1
                value.foreach { x =>
                    val elemBuf = new PostgresBufferWriter()
                    elemEncoder.write(x, elemBuf)
                    val elemBytes = elemBuf.toSpan
                    buf.writeInt32(elemBytes.size)
                    buf.writeBytes(elemBytes)
                }
            end write

    /** Binary encoder for PostgreSQL `int4[]` (OID 1007).
      *
      * Encodes `Seq[Int]` using the standard PG binary array format with element OID 23 (int4).
      */
    val int4ArrayBinary: PostgresEncoder[Seq[Int]] = arrayEncoder(int4Binary, OID_INT4, OID_INT4_ARRAY)

    /** Binary encoder for PostgreSQL `text[]` (OID 1009).
      *
      * Encodes `Seq[String]` using the standard PG binary array format with element OID 25 (text). Note: element bytes are UTF-8 text
      * (OID_TEXT encoder), which is what PG expects for text[].
      */
    val textArrayBinary: PostgresEncoder[Seq[String]] = arrayEncoder(textText, OID_TEXT, OID_TEXT_ARRAY)

    /** Binary encoder for PostgreSQL `jsonb[]` (OID 3807).
      *
      * Encodes `Seq[String]` (each element is a JSON-text string) using the standard PG binary array format with element OID 3802 (jsonb).
      * Element bytes mirror [[jsonbBinary]]: 1-byte version prefix (0x01) followed by raw UTF-8 JSON text. Callers pass JSON-text strings;
      * shape validation is the caller's responsibility (use [[kyo.Json]]-derived serializers to produce valid element text).
      */
    val jsonbArrayBinary: PostgresEncoder[Seq[String]] = arrayEncoder(jsonbBinary, OID_JSONB, OID_JSONB_ARRAY)

end PostgresEncoder
