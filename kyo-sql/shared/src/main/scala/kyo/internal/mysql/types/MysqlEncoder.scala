package kyo.internal.mysql.types

import java.nio.charset.StandardCharsets
import kyo.Instant
import kyo.Span
import kyo.internal.mysql.MysqlBufferWriter

/** Encodes a Scala value into MySQL binary-protocol wire bytes for use in [[ComStmtExecute]].
  *
  * MySQL binary protocol: parameter values are sent as raw bytes in the payload of [[ComStmtExecute]]. The (mysqlType, unsigned) pair is
  * sent in the type descriptor list when `newParamsBound=1`.
  *
  * Reference: MySQL Internals — Binary Protocol Value (§14.7.4)
  *
  * @tparam A
  *   the Scala type this encoder handles
  */
// Implemented as a trait (not an opaque type alias or final class) so that concrete encoders
// can be defined as anonymous given instances in the companion object without boilerplate.
// A trait allows `override def unsigned: Int = 1` for unsigned variants without a new class.
trait MysqlEncoder[A]:
    /** MySQL column-type byte (e.g. 0x08 = LONGLONG for Long). */
    def mysqlType: Int

    /** 0 = signed, 1 = unsigned. MySQL uses this to distinguish signed/unsigned variants of numeric types. */
    def unsigned: Int = 0

    /** Encodes `value` into `buf` using the MySQL binary format for this type. */
    def write(value: A, buf: MysqlBufferWriter): Unit

end MysqlEncoder

object MysqlEncoder:

    // --- MySQL type byte constants ---

    val TYPE_DECIMAL    = 0x00
    val TYPE_TINY       = 0x01 // TINYINT / BOOL
    val TYPE_SHORT      = 0x02
    val TYPE_LONG       = 0x03
    val TYPE_FLOAT      = 0x04
    val TYPE_DOUBLE     = 0x05
    val TYPE_NULL       = 0x06
    val TYPE_TIMESTAMP  = 0x07
    val TYPE_LONGLONG   = 0x08
    val TYPE_INT24      = 0x09
    val TYPE_DATE       = 0x0a
    val TYPE_TIME       = 0x0b
    val TYPE_DATETIME   = 0x0c
    val TYPE_YEAR       = 0x0d
    val TYPE_VARCHAR    = 0x0f
    val TYPE_BIT        = 0x10
    val TYPE_JSON       = 0xf5
    val TYPE_NEWDECIMAL = 0xf6
    val TYPE_BLOB       = 0xfc
    val TYPE_VAR_STRING = 0xfd
    val TYPE_STRING     = 0xfe

    // --- Boolean → TINY (1 byte) ---

    val boolEncoder: MysqlEncoder[Boolean] = new MysqlEncoder[Boolean]:
        def mysqlType: Int = TYPE_TINY
        def write(value: Boolean, buf: MysqlBufferWriter): Unit =
            buf.writeUInt8(if value then 1 else 0)

    // --- Short → SHORT (2 bytes LE signed) ---
    // TYPE_SHORT = 0x02; wire format is 2 bytes little-endian (signed).
    // writeUInt16LE masks to the lower 16 bits, which preserves the two's-complement
    // bit pattern for negative shorts (e.g. Short.MinValue = -32768 → 0x8000 LE).

    val shortEncoder: MysqlEncoder[Short] = new MysqlEncoder[Short]:
        def mysqlType: Int = TYPE_SHORT
        def write(value: Short, buf: MysqlBufferWriter): Unit =
            buf.writeUInt16LE(value.toInt)

    // --- Int → LONG (4 bytes LE signed) ---

    val intEncoder: MysqlEncoder[Int] = new MysqlEncoder[Int]:
        def mysqlType: Int = TYPE_LONG
        def write(value: Int, buf: MysqlBufferWriter): Unit =
            buf.writeUInt32LE(value.toLong)

    // --- Long → LONGLONG (8 bytes LE signed) ---

    val longEncoder: MysqlEncoder[Long] = new MysqlEncoder[Long]:
        def mysqlType: Int = TYPE_LONGLONG
        def write(value: Long, buf: MysqlBufferWriter): Unit =
            buf.writeUInt64LE(value)

    // --- Float → FLOAT (4 bytes IEEE 754 LE) ---

    val floatEncoder: MysqlEncoder[Float] = new MysqlEncoder[Float]:
        def mysqlType: Int = TYPE_FLOAT
        def write(value: Float, buf: MysqlBufferWriter): Unit =
            buf.writeUInt32LE(java.lang.Float.floatToIntBits(value).toLong)

    // --- Double → DOUBLE (8 bytes IEEE 754 LE) ---

    val doubleEncoder: MysqlEncoder[Double] = new MysqlEncoder[Double]:
        def mysqlType: Int = TYPE_DOUBLE
        def write(value: Double, buf: MysqlBufferWriter): Unit =
            buf.writeUInt64LE(java.lang.Double.doubleToLongBits(value))

    // --- BigDecimal → NEWDECIMAL (lenenc-string, text representation) ---
    // MySQL binary protocol sends NEWDECIMAL as a length-encoded string.

    val bigDecimalEncoder: MysqlEncoder[BigDecimal] = new MysqlEncoder[BigDecimal]:
        def mysqlType: Int = TYPE_NEWDECIMAL
        def write(value: BigDecimal, buf: MysqlBufferWriter): Unit =
            // Use Scala BigDecimal.toString, NOT value.underlying().toPlainString — Scala Native's
            // javalib zeros the integer digits in toPlainString. See PostgresEncoder.numericText.
            val bytes = value.toString.getBytes(StandardCharsets.UTF_8)
            buf.writeLenencInt(bytes.length.toLong)
            buf.writeBytes(Span.from(bytes))
        end write

    // --- String → VAR_STRING (lenenc-string, UTF-8) ---

    val stringEncoder: MysqlEncoder[String] = new MysqlEncoder[String]:
        def mysqlType: Int = TYPE_VAR_STRING
        def write(value: String, buf: MysqlBufferWriter): Unit =
            buf.writeLenencString(value)

    // --- JSON → TYPE_JSON (lenenc-string, UTF-8 JSON text) ---
    // MySQL JSON columns in the binary protocol are sent as length-encoded strings (TYPE_JSON = 0xf5).
    // The wire format is identical to VAR_STRING but the type byte signals a JSON column to the server.

    val jsonEncoder: MysqlEncoder[String] = new MysqlEncoder[String]:
        def mysqlType: Int = TYPE_JSON
        def write(value: String, buf: MysqlBufferWriter): Unit =
            buf.writeLenencString(value)

    // --- Span[Byte] → BLOB (lenenc-bytes) ---

    val bytesEncoder: MysqlEncoder[Span[Byte]] = new MysqlEncoder[Span[Byte]]:
        def mysqlType: Int = TYPE_BLOB
        def write(value: Span[Byte], buf: MysqlBufferWriter): Unit =
            buf.writeLenencInt(value.size.toLong)
            buf.writeBytes(value)

    // --- kyo.Instant → TIMESTAMP (7-byte datetime struct, UTC) ---
    // MySQL TIMESTAMP stores in UTC; we send as a 7-byte struct (no microseconds).
    // Format: len(1) | year(2 LE) | month(1) | day(1) | hour(1) | min(1) | sec(1)
    // For sub-second precision add 4 more bytes for microseconds (11 bytes total).

    val instantEncoder: MysqlEncoder[kyo.Instant] = new MysqlEncoder[kyo.Instant]:
        def mysqlType: Int = TYPE_TIMESTAMP
        def write(value: kyo.Instant, buf: MysqlBufferWriter): Unit =
            val jInstant = value.toJava
            val ldt      = java.time.LocalDateTime.ofInstant(jInstant, java.time.ZoneOffset.UTC)
            val micros   = jInstant.getNano / 1000
            if micros != 0 then
                buf.writeUInt8(11) // 11-byte struct with microseconds
                buf.writeUInt16LE(ldt.getYear)
                buf.writeUInt8(ldt.getMonthValue)
                buf.writeUInt8(ldt.getDayOfMonth)
                buf.writeUInt8(ldt.getHour)
                buf.writeUInt8(ldt.getMinute)
                buf.writeUInt8(ldt.getSecond)
                buf.writeUInt32LE(micros.toLong)
            else
                buf.writeUInt8(7) // 7-byte struct without microseconds
                buf.writeUInt16LE(ldt.getYear)
                buf.writeUInt8(ldt.getMonthValue)
                buf.writeUInt8(ldt.getDayOfMonth)
                buf.writeUInt8(ldt.getHour)
                buf.writeUInt8(ldt.getMinute)
                buf.writeUInt8(ldt.getSecond)
            end if
        end write

    // --- java.time.LocalDateTime → DATETIME (7-byte or 11-byte struct, naive) ---

    val localDateTimeEncoder: MysqlEncoder[java.time.LocalDateTime] = new MysqlEncoder[java.time.LocalDateTime]:
        def mysqlType: Int = TYPE_DATETIME
        def write(value: java.time.LocalDateTime, buf: MysqlBufferWriter): Unit =
            val micros = value.getNano / 1000
            if micros != 0 then
                buf.writeUInt8(11)
                buf.writeUInt16LE(value.getYear)
                buf.writeUInt8(value.getMonthValue)
                buf.writeUInt8(value.getDayOfMonth)
                buf.writeUInt8(value.getHour)
                buf.writeUInt8(value.getMinute)
                buf.writeUInt8(value.getSecond)
                buf.writeUInt32LE(micros.toLong)
            else
                buf.writeUInt8(7)
                buf.writeUInt16LE(value.getYear)
                buf.writeUInt8(value.getMonthValue)
                buf.writeUInt8(value.getDayOfMonth)
                buf.writeUInt8(value.getHour)
                buf.writeUInt8(value.getMinute)
                buf.writeUInt8(value.getSecond)
            end if
        end write

    // --- java.time.LocalDate → DATE (4-byte struct: year, month, day) ---

    val localDateEncoder: MysqlEncoder[java.time.LocalDate] = new MysqlEncoder[java.time.LocalDate]:
        def mysqlType: Int = TYPE_DATE
        def write(value: java.time.LocalDate, buf: MysqlBufferWriter): Unit =
            buf.writeUInt8(4)
            buf.writeUInt16LE(value.getYear)
            buf.writeUInt8(value.getMonthValue)
            buf.writeUInt8(value.getDayOfMonth)
        end write

    // --- java.time.LocalTime → TIME (8-byte or 12-byte struct) ---
    // Wire: len(1) | is_negative(1) | days(4 LE) | hour(1) | min(1) | sec(1) [| micros(4 LE)]

    val localTimeEncoder: MysqlEncoder[java.time.LocalTime] = new MysqlEncoder[java.time.LocalTime]:
        def mysqlType: Int = TYPE_TIME
        def write(value: java.time.LocalTime, buf: MysqlBufferWriter): Unit =
            val micros = value.getNano / 1000
            if micros != 0 then
                buf.writeUInt8(12)
                buf.writeUInt8(0)     // is_negative = 0
                buf.writeUInt32LE(0L) // days = 0
                buf.writeUInt8(value.getHour)
                buf.writeUInt8(value.getMinute)
                buf.writeUInt8(value.getSecond)
                buf.writeUInt32LE(micros.toLong)
            else
                buf.writeUInt8(8)
                buf.writeUInt8(0)     // is_negative = 0
                buf.writeUInt32LE(0L) // days = 0
                buf.writeUInt8(value.getHour)
                buf.writeUInt8(value.getMinute)
                buf.writeUInt8(value.getSecond)
            end if
        end write

    // --- java.time.Duration → TIME (1, 8, or 12-byte length-prefixed struct) ---
    // MySQL TIME wire format: len(1) | is_negative(1) | days(4 LE) | hour(1) | min(1) | sec(1) [| micros(4 LE)]
    // Duration.ZERO encodes as a single 0x00 byte (length = 0).
    // Non-fractional (no sub-second component): 8 bytes body → total 9 bytes with length prefix.
    // Fractional (nanoseconds > 0): 12 bytes body → total 13 bytes with length prefix.
    // Range: MySQL TIME supports -838:59:59 to 838:59:59 (days up to 34).
    // Days exceeding Int.MaxValue raise SqlException.Decode.

    val durationEncoder: MysqlEncoder[java.time.Duration] = new MysqlEncoder[java.time.Duration]:
        def mysqlType: Int = TYPE_TIME
        def write(value: java.time.Duration, buf: MysqlBufferWriter): Unit =
            val isNegative = value.isNegative
            val abs        = if isNegative then value.negated() else value
            // toDays can overflow Int if the duration is extremely large; throw ArithmeticException
            // so MysqlParamWriter.duration() can catch and wrap it as SqlException.Decode.
            val totalDays = abs.toDays
            if totalDays > Int.MaxValue.toLong then
                throw new ArithmeticException(
                    s"Duration.toDays=$totalDays exceeds MySQL TIME day-count range (Int.MAX_VALUE)"
                )
            end if
            val days    = totalDays.toInt
            val hours   = abs.toHoursPart
            val minutes = abs.toMinutesPart
            val seconds = abs.toSecondsPart
            val micros  = abs.toNanosPart / 1000
            if days == 0 && hours == 0 && minutes == 0 && seconds == 0 && micros == 0 then
                buf.writeUInt8(0) // length = 0 (zero duration)
            else if micros == 0 then
                buf.writeUInt8(8) // length = 8 (no fractional)
                buf.writeUInt8(if isNegative then 1 else 0)
                buf.writeUInt32LE(days.toLong)
                buf.writeUInt8(hours)
                buf.writeUInt8(minutes)
                buf.writeUInt8(seconds)
            else
                buf.writeUInt8(12) // length = 12 (fractional)
                buf.writeUInt8(if isNegative then 1 else 0)
                buf.writeUInt32LE(days.toLong)
                buf.writeUInt8(hours)
                buf.writeUInt8(minutes)
                buf.writeUInt8(seconds)
                buf.writeUInt32LE(micros.toLong)
            end if
        end write

    // --- Given instances for use by the fragment macro ---
    // These are used when a fragment interpolator contains a non-Default type in a MySQL client context.

    given MysqlEncoder[Float]  = floatEncoder
    given MysqlEncoder[Double] = doubleEncoder

    given MysqlEncoder[java.time.LocalDate]     = localDateEncoder
    given MysqlEncoder[java.time.LocalDateTime] = localDateTimeEncoder
    given MysqlEncoder[java.time.LocalTime]     = localTimeEncoder
    given MysqlEncoder[kyo.Instant]             = instantEncoder
    given MysqlEncoder[java.time.Duration]      = durationEncoder

end MysqlEncoder
