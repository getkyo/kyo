package kyo.internal.mysql.types

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlException
import kyo.internal.mysql.MysqlBufferReader

/** Decodes a MySQL binary-protocol column value from raw [[Span[Byte]]] into a Scala type.
  *
  * The raw bytes come from [[BinaryResultsetRow.values]] — already sliced from the packet by [[BinaryResultsetRowUnmarshaller]] — and
  * represent the binary-encoded value for a single non-null column.
  *
  * Reference: MySQL Internals — Binary Protocol Value (§14.7.4)
  *
  * @tparam A
  *   the Scala type this decoder produces
  */
trait MysqlDecoder[A]:
    def decode(bytes: Span[Byte])(using kyo.Frame): A < kyo.Abort[SqlException.Decode]
end MysqlDecoder

object MysqlDecoder:

    // --- Boolean ---

    val boolDecoder: MysqlDecoder[Boolean] = new MysqlDecoder[Boolean]:
        def decode(bytes: Span[Byte])(using kyo.Frame): Boolean < kyo.Abort[SqlException.Decode] =
            if bytes.size < 1 then kyo.Abort.fail(SqlException.Decode("TINY(bool): expected 1 byte", kyo.Maybe.Absent, summon[kyo.Frame]))
            else (bytes(0) & 0xff) != 0

    // --- Short (SHORT) ---
    // TYPE_SHORT = 0x02; wire format is 2 bytes little-endian (signed).
    // readUInt16LE returns an unsigned Int (0..65535); we reinterpret as a signed Short.
    // Values 0..32767 stay unchanged; 32768..65535 become negative (-32768..-1) via toShort.

    val shortDecoder: MysqlDecoder[Short] = new MysqlDecoder[Short]:
        def decode(bytes: Span[Byte])(using kyo.Frame): Short < kyo.Abort[SqlException.Decode] =
            if bytes.size < 2 then kyo.Abort.fail(SqlException.Decode("SHORT: expected 2 bytes", kyo.Maybe.Absent, summon[kyo.Frame]))
            else
                val b = MysqlBufferReader(bytes)
                b.readUInt16LE().map(_.toShort)

    given MysqlDecoder[Short] = shortDecoder

    // --- Int (LONG) ---

    val intDecoder: MysqlDecoder[Int] = new MysqlDecoder[Int]:
        def decode(bytes: Span[Byte])(using kyo.Frame): Int < kyo.Abort[SqlException.Decode] =
            if bytes.size < 4 then kyo.Abort.fail(SqlException.Decode("LONG: expected 4 bytes", kyo.Maybe.Absent, summon[kyo.Frame]))
            else
                val b = MysqlBufferReader(bytes)
                b.readUInt32LE().map(_.toInt)

    // --- Long (LONGLONG) ---

    val longDecoder: MysqlDecoder[Long] = new MysqlDecoder[Long]:
        def decode(bytes: Span[Byte])(using kyo.Frame): Long < kyo.Abort[SqlException.Decode] =
            if bytes.size < 8 then kyo.Abort.fail(SqlException.Decode("LONGLONG: expected 8 bytes", kyo.Maybe.Absent, summon[kyo.Frame]))
            else
                val b = MysqlBufferReader(bytes)
                b.readUInt64LE()

    // --- Float (FLOAT) ---

    val floatDecoder: MysqlDecoder[Float] = new MysqlDecoder[Float]:
        def decode(bytes: Span[Byte])(using kyo.Frame): Float < kyo.Abort[SqlException.Decode] =
            if bytes.size < 4 then kyo.Abort.fail(SqlException.Decode("FLOAT: expected 4 bytes", kyo.Maybe.Absent, summon[kyo.Frame]))
            else
                val b = MysqlBufferReader(bytes)
                b.readUInt32LE().map(bits => java.lang.Float.intBitsToFloat(bits.toInt))

    // --- Double (DOUBLE) ---

    val doubleDecoder: MysqlDecoder[Double] = new MysqlDecoder[Double]:
        def decode(bytes: Span[Byte])(using kyo.Frame): Double < kyo.Abort[SqlException.Decode] =
            if bytes.size < 8 then kyo.Abort.fail(SqlException.Decode("DOUBLE: expected 8 bytes", kyo.Maybe.Absent, summon[kyo.Frame]))
            else
                val b = MysqlBufferReader(bytes)
                b.readUInt64LE().map(bits => java.lang.Double.longBitsToDouble(bits))

    // --- BigDecimal (NEWDECIMAL — lenenc-string already decoded) ---
    // When this decoder is called the raw bytes are already the UTF-8 text of the decimal.

    val bigDecimalDecoder: MysqlDecoder[BigDecimal] = new MysqlDecoder[BigDecimal]:
        def decode(bytes: Span[Byte])(using kyo.Frame): BigDecimal < kyo.Abort[SqlException.Decode] =
            val s = new String(bytes.toArray, StandardCharsets.UTF_8)
            try BigDecimal(s)
            catch
                case _: NumberFormatException =>
                    kyo.Abort.fail(SqlException.Decode(s"NEWDECIMAL: cannot parse '$s' as BigDecimal", kyo.Maybe.Absent, summon[kyo.Frame]))
            end try
        end decode

    // --- String (VAR_STRING / STRING) ---

    val stringDecoder: MysqlDecoder[String] = new MysqlDecoder[String]:
        def decode(bytes: Span[Byte])(using kyo.Frame): String < kyo.Abort[SqlException.Decode] =
            new String(bytes.toArray, StandardCharsets.UTF_8)

    // --- JSON (TYPE_JSON = 0xf5) — UTF-8 text payload ---
    // MySQL sends JSON column values as length-encoded string payloads (raw UTF-8 JSON text).
    // The length prefix is stripped by BinaryResultsetRowUnmarshaller before reaching this decoder.
    //
    // Production-read note (Phase 17 audit W-2): `MysqlRowReader.string()` decodes TYPE_JSON columns
    // through its generic `readUtf8String(nextBytes())` path; the byte output is byte-identical to
    // this singleton (both `new String(bytes.toArray, UTF_8)` from the same lenenc-stripped span).
    // The singleton is therefore test-asserted only — it pins the contract that JSON columns surface
    // as raw UTF-8 strings without further parsing, so any future production refactor that switches
    // away from `string()` (e.g. routes JSON through a typed-codec map) starts from this declared
    // shape rather than inferring it from `string()`'s implementation.
    val jsonDecoder: MysqlDecoder[String] = new MysqlDecoder[String]:
        def decode(bytes: Span[Byte])(using kyo.Frame): String < kyo.Abort[SqlException.Decode] =
            new String(bytes.toArray, StandardCharsets.UTF_8)

    // --- Span[Byte] (BLOB) ---

    val bytesDecoder: MysqlDecoder[Span[Byte]] = new MysqlDecoder[Span[Byte]]:
        def decode(bytes: Span[Byte])(using kyo.Frame): Span[Byte] < kyo.Abort[SqlException.Decode] =
            bytes

    // --- kyo.Instant from TIMESTAMP bytes ---
    // Uses the raw lenenc-string bytes from the column value (already lenenc-decoded by BinaryResultsetRowUnmarshaller).

    val instantDecoder: MysqlDecoder[kyo.Instant] = new MysqlDecoder[kyo.Instant]:
        def decode(bytes: Span[Byte])(using kyo.Frame): kyo.Instant < kyo.Abort[SqlException.Decode] =
            decodeDatetimeBytes(bytes).map { ldt =>
                kyo.Instant.fromJava(ldt.toInstant(java.time.ZoneOffset.UTC))
            }

    // --- java.time.LocalDateTime from DATETIME bytes ---

    val localDateTimeDecoder: MysqlDecoder[java.time.LocalDateTime] = new MysqlDecoder[java.time.LocalDateTime]:
        def decode(bytes: Span[Byte])(using kyo.Frame): java.time.LocalDateTime < kyo.Abort[SqlException.Decode] =
            decodeDatetimeBytes(bytes)

    // --- java.time.LocalDate from DATE bytes ---

    val localDateDecoder: MysqlDecoder[java.time.LocalDate] = new MysqlDecoder[java.time.LocalDate]:
        def decode(bytes: Span[Byte])(using kyo.Frame): java.time.LocalDate < kyo.Abort[SqlException.Decode] =
            decodeDateBytes(bytes)

    // --- java.time.LocalTime from TIME bytes ---

    val localTimeDecoder: MysqlDecoder[java.time.LocalTime] = new MysqlDecoder[java.time.LocalTime]:
        def decode(bytes: Span[Byte])(using kyo.Frame): java.time.LocalTime < kyo.Abort[SqlException.Decode] =
            decodeTimeBytes(bytes)

    // --- Datetime binary struct decoder ---
    //
    // MySQL datetime binary protocol length-prefixed struct:
    //   len=0  → zero date / zero time (0000-00-00 00:00:00)
    //   len=4  → date only: year(2 LE) | month(1) | day(1)  → midnight on that date
    //   len=7  → date + time: year(2 LE) | month(1) | day(1) | hour(1) | min(1) | sec(1)
    //   len=11 → date + time + microseconds: above + micros(4 LE)
    //
    // IMPORTANT: When this decoder is called by ExtendedQueryExchange, the bytes have already had
    // the length prefix stripped by BinaryResultsetRowUnmarshaller.readColumnValue — so `bytes`
    // contains only the struct body (not the length byte).
    // However, the length byte IS still present when we read from the variable-length path in
    // readColumnValue (it calls readLenencInt then readBytes(len), giving us just the body).
    // For DATE/TIME/DATETIME/TIMESTAMP, the length byte encodes the variant — we need it.
    // BinaryResultsetRowUnmarshaller's readColumnValue for these types reads the lenenc-string
    // path (first byte = lenenc-int length, rest = content). So bytes here = raw struct body.
    // The "length byte" (0, 4, 7, 11) was consumed by readLenencInt; what we get is the rest.
    //
    // Actually looking at MySQL protocol more carefully: DATE/TIME/DATETIME in binary protocol
    // are sent as length-prefixed binary structs, NOT as lenenc-strings. The length byte (0/4/7/11)
    // is read directly (1 byte), then the struct bytes follow. BinaryResultsetRowUnmarshaller
    // uses the generic lenenc path for all variable types, which means it reads the length byte
    // as a lenenc-int then reads that many bytes. Since 0/4/7/11 are all < 251, lenenc-int reads
    // 1 byte. So bytes = struct body (without the length prefix). We DON'T have the length here,
    // but we can infer from bytes.size.

    private[types] def decodeDatetimeBytes(
        bytes: Span[Byte]
    )(using kyo.Frame): java.time.LocalDateTime < kyo.Abort[SqlException.Decode] =
        bytes.size match
            case 0 =>
                // Zero date: 0000-00-00 00:00:00 → represent as epoch
                java.time.LocalDateTime.of(0, 1, 1, 0, 0, 0)
            case 4 =>
                // Date only (year/month/day), time = midnight
                val b = MysqlBufferReader(bytes)
                b.readUInt16LE().flatMap { year =>
                    b.readUInt8().flatMap { month =>
                        b.readUInt8().flatMap { day =>
                            safeLocalDateTime(year, month, day, 0, 0, 0, 0)
                        }
                    }
                }
            case 7 =>
                // Date + time, no microseconds
                val b = MysqlBufferReader(bytes)
                b.readUInt16LE().flatMap { year =>
                    b.readUInt8().flatMap { month =>
                        b.readUInt8().flatMap { day =>
                            b.readUInt8().flatMap { hour =>
                                b.readUInt8().flatMap { minute =>
                                    b.readUInt8().flatMap { second =>
                                        safeLocalDateTime(year, month, day, hour, minute, second, 0)
                                    }
                                }
                            }
                        }
                    }
                }
            case 11 =>
                // Date + time + microseconds
                val b = MysqlBufferReader(bytes)
                b.readUInt16LE().flatMap { year =>
                    b.readUInt8().flatMap { month =>
                        b.readUInt8().flatMap { day =>
                            b.readUInt8().flatMap { hour =>
                                b.readUInt8().flatMap { minute =>
                                    b.readUInt8().flatMap { second =>
                                        b.readUInt32LE().flatMap { micros =>
                                            safeLocalDateTime(year, month, day, hour, minute, second, micros.toInt * 1000)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            case n =>
                kyo.Abort.fail(SqlException.Decode(
                    s"DATETIME: unexpected struct length $n (expected 0, 4, 7, or 11)",
                    kyo.Maybe.Absent,
                    summon[kyo.Frame]
                ))
        end match
    end decodeDatetimeBytes

    private def safeLocalDateTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        nanos: Int
    )(using kyo.Frame): java.time.LocalDateTime < kyo.Abort[SqlException.Decode] =
        // Handle MySQL zero-date (0000-00-00): java.time doesn't support year 0; use year 1.
        val safeYear = if year == 0 then 1 else year
        val safeMon  = if month == 0 then 1 else month
        val safeDay  = if day == 0 then 1 else day
        try java.time.LocalDateTime.of(safeYear, safeMon, safeDay, hour, minute, second, nanos)
        catch
            case e: java.time.DateTimeException =>
                kyo.Abort.fail(SqlException.Decode(
                    s"DATETIME: invalid date $year-$month-$day $hour:$minute:$second: ${e.getMessage}",
                    kyo.Maybe.Absent,
                    summon[kyo.Frame]
                ))
        end try
    end safeLocalDateTime

    private[types] def decodeDateBytes(
        bytes: Span[Byte]
    )(using kyo.Frame): java.time.LocalDate < kyo.Abort[SqlException.Decode] =
        bytes.size match
            case 0 => java.time.LocalDate.of(1, 1, 1) // zero date
            case 4 =>
                val b = MysqlBufferReader(bytes)
                b.readUInt16LE().flatMap { year =>
                    b.readUInt8().flatMap { month =>
                        b.readUInt8().flatMap { day =>
                            val safeYear = if year == 0 then 1 else year
                            val safeMon  = if month == 0 then 1 else month
                            val safeDay  = if day == 0 then 1 else day
                            try java.time.LocalDate.of(safeYear, safeMon, safeDay)
                            catch
                                case e: java.time.DateTimeException =>
                                    kyo.Abort.fail(SqlException.Decode(
                                        s"DATE: invalid $year-$month-$day: ${e.getMessage}",
                                        kyo.Maybe.Absent,
                                        summon[kyo.Frame]
                                    ))
                            end try
                        }
                    }
                }
            case n => kyo.Abort.fail(SqlException.Decode(s"DATE: unexpected struct length $n", kyo.Maybe.Absent, summon[kyo.Frame]))
        end match
    end decodeDateBytes

    // --- java.time.Duration from TIME bytes ---
    // MySQL TIME wire format body (length prefix stripped by BinaryResultsetRowUnmarshaller):
    //   len=0  → zero duration
    //   len=8  → is_negative(1) | days(4 LE) | hours(1) | minutes(1) | seconds(1)
    //   len=12 → same as 8 + micros(4 LE)

    val durationDecoder: MysqlDecoder[java.time.Duration] = new MysqlDecoder[java.time.Duration]:
        def decode(bytes: Span[Byte])(using kyo.Frame): java.time.Duration < kyo.Abort[SqlException.Decode] =
            decodeDurationBytes(bytes)

    private[types] def decodeDurationBytes(
        bytes: Span[Byte]
    )(using kyo.Frame): java.time.Duration < kyo.Abort[SqlException.Decode] =
        bytes.size match
            case 0 => java.time.Duration.ZERO
            case 8 =>
                val b = MysqlBufferReader(bytes)
                b.readUInt8().flatMap { isNeg =>
                    b.readUInt32LE().flatMap { days =>
                        b.readUInt8().flatMap { hours =>
                            b.readUInt8().flatMap { minutes =>
                                b.readUInt8().flatMap { seconds =>
                                    val totalSecs = days * 86400L + hours * 3600L + minutes * 60L + seconds
                                    val d         = java.time.Duration.ofSeconds(totalSecs)
                                    if isNeg != 0 then d.negated() else d
                                }
                            }
                        }
                    }
                }
            case 12 =>
                val b = MysqlBufferReader(bytes)
                b.readUInt8().flatMap { isNeg =>
                    b.readUInt32LE().flatMap { days =>
                        b.readUInt8().flatMap { hours =>
                            b.readUInt8().flatMap { minutes =>
                                b.readUInt8().flatMap { seconds =>
                                    b.readUInt32LE().flatMap { micros =>
                                        val totalSecs = days * 86400L + hours * 3600L + minutes * 60L + seconds
                                        val nanos     = micros * 1000L
                                        val d         = java.time.Duration.ofSeconds(totalSecs, nanos)
                                        if isNeg != 0 then d.negated() else d
                                    }
                                }
                            }
                        }
                    }
                }
            case n =>
                kyo.Abort.fail(SqlException.Decode(
                    s"TIME(duration): unexpected struct length $n (expected 0, 8, or 12)",
                    kyo.Maybe.Absent,
                    summon[kyo.Frame]
                ))
        end match
    end decodeDurationBytes

    private[types] def decodeTimeBytes(
        bytes: Span[Byte]
    )(using kyo.Frame): java.time.LocalTime < kyo.Abort[SqlException.Decode] =
        bytes.size match
            case 0 => java.time.LocalTime.MIDNIGHT
            case 8 =>
                val b = MysqlBufferReader(bytes)
                b.readUInt8().andThen(        // is_negative: ignored for LocalTime (always 0 for SELECT results)
                    b.readUInt32LE().andThen( // days: ignored for LocalTime (always 0 for SELECT results)
                        b.readUInt8().flatMap { hour =>
                            b.readUInt8().flatMap { minute =>
                                b.readUInt8().flatMap { second =>
                                    try java.time.LocalTime.of(hour, minute, second)
                                    catch
                                        case e: java.time.DateTimeException =>
                                            kyo.Abort.fail(SqlException.Decode(
                                                s"TIME: invalid $hour:$minute:$second: ${e.getMessage}",
                                                kyo.Maybe.Absent,
                                                summon[kyo.Frame]
                                            ))
                                    end try
                                }
                            }
                        }
                    )
                )
            case 12 =>
                val b = MysqlBufferReader(bytes)
                b.readUInt8().andThen(        // is_negative: ignored for LocalTime (always 0 for SELECT results)
                    b.readUInt32LE().andThen( // days: ignored for LocalTime (always 0 for SELECT results)
                        b.readUInt8().flatMap { hour =>
                            b.readUInt8().flatMap { minute =>
                                b.readUInt8().flatMap { second =>
                                    b.readUInt32LE().flatMap { micros =>
                                        try java.time.LocalTime.of(hour, minute, second, micros.toInt * 1000)
                                        catch
                                            case e: java.time.DateTimeException =>
                                                kyo.Abort.fail(SqlException.Decode(
                                                    s"TIME: invalid $hour:$minute:$second.$micros: ${e.getMessage}",
                                                    kyo.Maybe.Absent,
                                                    summon[kyo.Frame]
                                                ))
                                        end try
                                    }
                                }
                            }
                        }
                    )
                )
            case n => kyo.Abort.fail(SqlException.Decode(s"TIME: unexpected struct length $n", kyo.Maybe.Absent, summon[kyo.Frame]))
        end match
    end decodeTimeBytes

    given MysqlDecoder[java.time.Duration] = durationDecoder

end MysqlDecoder
