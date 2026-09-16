package kyo.internal.sqlite

import java.nio.charset.StandardCharsets
import kyo.*

/** Parsers for the text SQLite hands back, each the exact inverse of the matching [[kyo.internal.SqlValueRender]] method.
  *
  * Inverse rather than a second opinion about the format. SQLite has no type of its own for any of these: a TEXT-affinity column stores the
  * characters the driver wrote and returns them byte for byte, so the only format in play is the one the renderer chose. Reading anything
  * else here would mean the driver disagreeing with itself.
  *
  * Shared by the row reader, which decodes into `java.time` values for a typed read, and by the codec, which decodes into `SqlValue` for a
  * neutral one. Both parse the same characters, so they parse them in one place.
  */
private[sqlite] object SqliteText:

    private val BcSuffix        = " BC"
    private val MicrosPerSecond = 1_000_000L
    private val MicrosPerMinute = 60L * MicrosPerSecond
    private val MicrosPerHour   = 60L * MicrosPerMinute

    def utf8(bytes: Span[Byte]): String =
        new String(bytes.toArray, StandardCharsets.UTF_8)

    /** SQLite stores a boolean as an integer, a `BOOLEAN` declaration taking NUMERIC affinity, so `0` and `1` are what come back. The word
      * forms are accepted too, for a column some other writer filled.
      */
    def boolean(text: String): Boolean =
        text match
            case "1" | "true" | "TRUE" | "t"   => true
            case "0" | "false" | "FALSE" | "f" => false
            case other                         => throw new NumberFormatException(s"not a boolean: '$other'")

    /** Splits a trailing era off a value. */
    def splitEra(text: String): (String, Boolean) =
        if text.endsWith(BcSuffix) then (text.dropRight(BcSuffix.length), true) else (text, false)

    /** Splits a trailing `+HH:MM[:SS]` off a value, or answers no offset.
      *
      * The tail after the sign must be EXACTLY `HH:MM` or `HH:MM:SS`, so the test is on its exact length rather than a minimum. A minimum
      * is not enough: `2026-08-25 10:00:00` ends eleven characters after its last hyphen, which reads as long enough and would split the
      * value in the middle of the date. Nothing about the answer would look wrong.
      *
      * The caller strips any era FIRST: with ` BC` still attached the sign search lands on the date's hyphen instead.
      */
    def splitOffset(text: String): (String, Int) =
        val idx  = text.lastIndexWhere(c => c == '+' || c == '-')
        val tail = text.length - idx
        if idx < 0 || (tail != 6 && tail != 9) then (text, 0)
        else
            val sign   = if text.charAt(idx) == '-' then -1 else 1
            val fields = text.substring(idx + 1).split(':')
            val total =
                fields.length match
                    case 2 => fields(0).toInt * 3600 + fields(1).toInt * 60
                    case 3 => fields(0).toInt * 3600 + fields(1).toInt * 60 + fields(2).toInt
                    case _ => throw new NumberFormatException(s"not an offset: '${text.substring(idx)}'")
            (text.substring(0, idx), sign * total)
        end if
    end splitOffset

    /** `HH:MM:SS[.ffffff]`, answering hours as a Long because a span's hours are not bounded by a day. */
    def timeFields(body: String): (Long, Int, Int, Int) =
        val (base, micros) = body.indexOf('.') match
            case -1  => (body, 0)
            case dot =>
                // The rendering drops trailing zeros, so the fraction is padded back to six digits rather than read as
                // an integer: `.5` is 500000 microseconds, not 5.
                val frac = body.substring(dot + 1)
                (body.substring(0, dot), frac.padTo(6, '0').take(6).toInt)
        base.split(':') match
            case Array(h, m, s) => (h.toLong, m.toInt, s.toInt, micros)
            case _              => throw new NumberFormatException(s"not a time: '$body'")
        end match
    end timeFields

    /** `YYYY-MM-DD[ BC]`, the year counted within its era. */
    def dateFields(text: String): (Int, Int, Int, Boolean) =
        val (body, bc) = splitEra(text)
        body.split('-') match
            case Array(y, m, d) => (y.toInt, m.toInt, d.toInt, bc)
            case _              => throw new NumberFormatException(s"not a date: '$text'")
    end dateFields

    /** `YYYY-MM-DD HH:MM:SS[.ffffff][ BC]`, a space rather than a `T` and the era trailing the whole value. */
    def dateTimeFields(text: String): (Int, Int, Int, Boolean, Int, Int, Int, Int) =
        val (body, bc) = splitEra(text)
        body.split(' ') match
            case Array(datePart, timePart) =>
                val (y, mo, d, _)      = dateFields(datePart)
                val (h, mi, s, micros) = timeFields(timePart)
                (y, mo, d, bc, h.toInt, mi, s, micros)
            case _ => throw new NumberFormatException(s"not a date-time: '$text'")
        end match
    end dateTimeFields

    /** An instant written at UTC, with the era trailing the OFFSET rather than the date and time. */
    def timestampFields(text: String): (Long, Int) =
        val (withOffset, bc)      = splitEra(text)
        val (body, offsetSeconds) = splitOffset(withOffset)
        body.split(' ') match
            case Array(datePart, timePart) =>
                val (y, mo, d, _)      = dateFields(datePart)
                val (h, mi, s, micros) = timeFields(timePart)
                val year               = if bc then 1 - y else y
                val local              = java.time.LocalDateTime.of(year, mo, d, h.toInt, mi, s)
                (local.toEpochSecond(java.time.ZoneOffset.ofTotalSeconds(offsetSeconds)), micros)
            case _ => throw new NumberFormatException(s"not a timestamp: '$text'")
        end match
    end timestampFields

    /** ISO-8601 `P[nY][nM][nD][T[nH][nM][n[.f]S]]`, the one spelling the renderer writes. */
    def intervalFields(text: String): (Long, Long, Long) =
        if !text.startsWith("P") then throw new NumberFormatException(s"not an interval: '$text'")
        val body = text.substring(1)
        val (datePart, timePart) = body.indexOf('T') match
            case -1  => (body, "")
            case idx => (body.substring(0, idx), body.substring(idx + 1))
        var months = 0L
        var days   = 0L
        var micros = 0L
        def each(part: String)(consume: (String, Char) => Unit): Unit =
            val number = new StringBuilder
            part.foreach { c =>
                if c.isDigit || c == '-' || c == '.' then discard(number.append(c))
                else
                    consume(number.toString, c)
                    number.setLength(0)
            }
        end each
        each(datePart) { (value, unit) =>
            unit match
                case 'Y' => months += value.toLong * 12
                case 'M' => months += value.toLong
                case 'D' => days += value.toLong
                case _   => throw new NumberFormatException(s"not an interval: '$text'")
        }
        each(timePart) { (value, unit) =>
            unit match
                case 'H' => micros += value.toLong * MicrosPerHour
                case 'M' => micros += value.toLong * MicrosPerMinute
                case 'S' =>
                    // The sign lives on the seconds when the whole part is zero and the fraction is not, which is how
                    // the renderer spells a negative sub-second interval.
                    val negative  = value.startsWith("-")
                    val magnitude = BigDecimal(if negative then value.substring(1) else value)
                    val asMicros  = (magnitude * BigDecimal(MicrosPerSecond)).toLong
                    micros += (if negative then -asMicros else asMicros)
                case _ => throw new NumberFormatException(s"not an interval: '$text'")
        }
        (months, days, micros)
    end intervalFields

end SqliteText
