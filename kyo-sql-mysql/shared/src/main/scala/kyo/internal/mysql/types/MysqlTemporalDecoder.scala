package kyo.internal.mysql.types

import kyo.*
import kyo.SqlDecodeDurationException
import kyo.SqlDecodeException
import kyo.SqlDecodeTemporalException
import kyo.internal.mysql.MysqlBufferReader

/** Parses the MySQL binary-protocol structs the temporal types travel in.
  *
  * The bytes come from `BinaryResultsetRow.values`, already sliced from the packet by `BinaryResultsetRowUnmarshaller`, so each function
  * here receives one column's struct body with its length prefix removed. `kyo.internal.mysql.MysqlRowReader` is the caller, for the binary
  * arm of each temporal read; the text arm parses the rendering in the reader itself, and the two meet at [[isZeroDate]].
  *
  * This is a set of functions rather than a decoder typeclass. A column decodes through [[kyo.internal.mysql.MysqlRowReader]] and
  * [[kyo.internal.mysql.MysqlNumericDecoder]]; the struct parsers live here once so both wire formats resolve a temporal value the same way
  * and cannot diverge on wire width, UNSIGNED handling, or the zero date.
  *
  * Reference: MySQL Internals, Binary Protocol Value (§14.7.4)
  */
object MysqlTemporalDecoder:

    // --- Datetime binary struct decoder ---
    //
    // MySQL datetime binary protocol length-prefixed struct:
    //   len=0  → the all-zero value: the zero date 0000-00-00 00:00:00
    //   len=4  → date only: year(2 LE) | month(1) | day(1)  → midnight on that date
    //   len=7  → date + time: year(2 LE) | month(1) | day(1) | hour(1) | min(1) | sec(1)
    //   len=11 → date + time + microseconds: above + micros(4 LE)
    //
    // The length byte is not in `bytes`. BinaryResultsetRowUnmarshaller.readColumnValue takes the generic
    // lenenc path for every variable-width type, and since 0, 4, 7 and 11 are all below 251 a lenenc-int
    // reads exactly one byte, so the value stored in the row is the struct body alone. Which variant it is
    // therefore comes from `bytes.size` rather than from a length field.
    //
    // Every path here refuses a zero year, month, or day rather than substituting 1, so a value MySQL
    // permits and java.time cannot hold surfaces as a decode failure instead of as a plausible date. See
    // [[isZeroDate]].

    private[kyo] def decodeDatetimeBytes(
        bytes: Span[Byte]
    )(using kyo.Frame): java.time.LocalDateTime < kyo.Abort[SqlDecodeException] =
        bytes.size match
            case 0 =>
                // A zero-length struct is the all-zero value, so it is the zero date and is refused for the
                // same reason an explicitly all-zero four-byte struct is. Note the asymmetry with TIME below,
                // where a zero-length struct is 00:00:00, a real value rather than a stand-in for absence.
                kyo.Abort.fail(SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, 0))
            case 4 =>
                // Date only (year/month/day), time = midnight
                val b = MysqlBufferReader(bytes)
                b.readUInt16LE().flatMap { year =>
                    b.readUInt8().flatMap { month =>
                        b.readUInt8().flatMap { day =>
                            localDateTimeOf(year, month, day, 0, 0, 0, 0, 4)
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
                                        localDateTimeOf(year, month, day, hour, minute, second, 0, 7)
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
                                            localDateTimeOf(year, month, day, hour, minute, second, micros.toInt * 1000, 11)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            case n =>
                kyo.Abort.fail(SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, n))
        end match
    end decodeDatetimeBytes

    /** True when a MySQL date carries a zero year, month, or day.
      *
      * MySQL permits `0000-00-00` and, unless `NO_ZERO_IN_DATE` is set, partial forms like `2024-00-15`. None of them names a day, and
      * `java.time` has no representation for any of them, so this is the one predicate every decode path here consults. Both wire formats
      * call it so the two cannot drift: the shared answer is what makes them agree, rather than each matching its own constants.
      *
      * It is not narrowed to the all-zero case on purpose. Substituting 1 per zero component would turn `2024-00-15` into `2024-01-15`, a
      * date that looks real, reads as data, and is not what the column holds.
      */
    private[kyo] def isZeroDate(year: Int, month: Int, day: Int): Boolean =
        year == 0 || month == 0 || day == 0

    private def localDateTimeOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        nanos: Int,
        structLength: Int
    )(using kyo.Frame): java.time.LocalDateTime < kyo.Abort[SqlDecodeException] =
        if isZeroDate(year, month, day) then
            kyo.Abort.fail(SqlDecodeTemporalException(year, month, day, hour, minute, second, structLength))
        else
            try java.time.LocalDateTime.of(year, month, day, hour, minute, second, nanos)
            catch
                case e: java.time.DateTimeException =>
                    kyo.Abort.fail(SqlDecodeTemporalException(year, month, day, hour, minute, second, structLength))
            end try
    end localDateTimeOf

    private[kyo] def decodeDateBytes(
        bytes: Span[Byte]
    )(using kyo.Frame): java.time.LocalDate < kyo.Abort[SqlDecodeException] =
        bytes.size match
            case 0 => kyo.Abort.fail(SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, 0)) // the zero date
            case 4 =>
                val b = MysqlBufferReader(bytes)
                b.readUInt16LE().flatMap { year =>
                    b.readUInt8().flatMap { month =>
                        b.readUInt8().flatMap { day =>
                            if isZeroDate(year, month, day) then
                                kyo.Abort.fail(SqlDecodeTemporalException(year, month, day, 0, 0, 0, 4))
                            else
                                try java.time.LocalDate.of(year, month, day)
                                catch
                                    case e: java.time.DateTimeException =>
                                        kyo.Abort.fail(SqlDecodeTemporalException(year, month, day, 0, 0, 0, 4))
                                end try
                            end if
                        }
                    }
                }
            case n => kyo.Abort.fail(SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, n))
        end match
    end decodeDateBytes

    // --- java.time.Duration from TIME bytes ---
    // MySQL TIME wire format body (length prefix stripped by BinaryResultsetRowUnmarshaller):
    //   len=0  → zero duration
    //   len=8  → is_negative(1) | days(4 LE) | hours(1) | minutes(1) | seconds(1)
    //   len=12 → same as 8 + micros(4 LE)

    private[kyo] def decodeDurationBytes(
        bytes: Span[Byte]
    )(using kyo.Frame): java.time.Duration < kyo.Abort[SqlDecodeException] =
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
                kyo.Abort.fail(SqlDecodeDurationException("<struct>", new Exception(s"unexpected struct length $n")))
        end match
    end decodeDurationBytes

    /** Decodes a MySQL binary TIME struct body as a time of day.
      *
      * MySQL `TIME` is a signed span from -838:59:59 to 838:59:59, which is wider than a day in both directions, and the struct carries the
      * two fields that say so: `is_negative` and `days`. Neither is representable in a `LocalTime`, and discarding them would turn
      * `-10:30:00` into `10:30:00` and `100:00:00` into `04:00:00`. Both are refused here, exactly as the text-format sibling
      * [[kyo.internal.mysql.MysqlRowReader.parseTextLocalTime]] refuses a leading `-` and an hour above 23, and the refusal reports the same
      * out-of-day hour count the text form would have rendered.
      *
      * [[decodeDurationBytes]] is the mapping that carries the full range, and it is what a `java.time.Duration` field reads through.
      */
    private[kyo] def decodeTimeBytes(
        bytes: Span[Byte]
    )(using kyo.Frame): java.time.LocalTime < kyo.Abort[SqlDecodeException] =
        bytes.size match
            case 0 => java.time.LocalTime.MIDNIGHT
            case 8 =>
                val b = MysqlBufferReader(bytes)
                b.readUInt8().flatMap { isNeg =>
                    b.readUInt32LE().flatMap { days =>
                        b.readUInt8().flatMap { hour =>
                            b.readUInt8().flatMap { minute =>
                                b.readUInt8().flatMap { second =>
                                    localTimeOf(isNeg, days, hour, minute, second, 0, 8)
                                }
                            }
                        }
                    }
                }
            case 12 =>
                val b = MysqlBufferReader(bytes)
                b.readUInt8().flatMap { isNeg =>
                    b.readUInt32LE().flatMap { days =>
                        b.readUInt8().flatMap { hour =>
                            b.readUInt8().flatMap { minute =>
                                b.readUInt8().flatMap { second =>
                                    b.readUInt32LE().flatMap { micros =>
                                        localTimeOf(isNeg, days, hour, minute, second, micros.toInt * 1000, 12)
                                    }
                                }
                            }
                        }
                    }
                }
            case n => kyo.Abort.fail(SqlDecodeDurationException("<struct>", new Exception(s"unexpected struct length $n")))
        end match
    end decodeTimeBytes

    private def localTimeOf(
        isNegative: Int,
        days: Long,
        hour: Int,
        minute: Int,
        second: Int,
        nanos: Int,
        structLength: Int
    )(using kyo.Frame): java.time.LocalTime < kyo.Abort[SqlDecodeException] =
        // The struct splits an out-of-day span across `days` and `hour`, so the hour count the value names is
        // days * 24 + hour, which is what the text protocol renders and what a rejection reports.
        val totalHours = days * 24L + hour
        if isNegative != 0 || totalHours > 23L then
            kyo.Abort.fail(SqlDecodeTemporalException(0, 0, 0, totalHours.toInt, minute, second, structLength))
        else
            try java.time.LocalTime.of(hour, minute, second, nanos)
            catch
                case e: java.time.DateTimeException =>
                    kyo.Abort.fail(SqlDecodeTemporalException(0, 0, 0, hour, minute, second, structLength))
            end try
        end if
    end localTimeOf

end MysqlTemporalDecoder
