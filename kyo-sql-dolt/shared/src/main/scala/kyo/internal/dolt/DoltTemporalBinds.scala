package kyo.internal.dolt

import kyo.*

/** Rewrites a temporal bind parameter as text, because this engine corrupts the binary form.
  *
  * Measured against a 2.3.4 server. The MySQL binary protocol carries a datetime's sub-second part as a 4-byte count of MICROSECONDS, and
  * this driver encodes exactly that. Dolt reads that integer as the fractional DIGITS instead, so it stores `"." + micros` rather than
  * `micros` microseconds:
  *
  * {{{
  * bound .5       (500000 micros) -> stored .5        correct by coincidence
  * bound .001     (  1000 micros) -> stored .1        WRONG
  * bound .000001  (     1 micros) -> stored .1        WRONG
  * }}}
  *
  * A value is right exactly when its microsecond count has no leading zero at six digits, so everything under 100000 microseconds is
  * silently corrupted: nothing fails, and the row simply holds a different instant than the one written. The same value written as a TEXT
  * literal stores correctly, measured, so every temporal bind is sent as a string. The read path needs nothing, the corruption being
  * entirely in what the server stores.
  */
private[kyo] object DoltTemporalBinds:

    /** A parameter carrying the same value as text, or the parameter unchanged when it is not a temporal one. */
    def rewrite(params: Chunk[Sql.BoundValue[?]]): Chunk[Sql.BoundValue[?]] =
        params.map { bound =>
            asText(bound.value) match
                case Present(text) => Sql.BoundValue(text, stringColumn, "VARCHAR")
                case Absent        => bound
        }

    private val stringColumn: SqlSchema.Column[String] = summon[SqlSchema.Column[String]]

    /** The literal this engine parses back to the same value, for every temporal type that carries a sub-second part. The types with none
      * are deliberately absent: a `LocalDate` has nothing to corrupt.
      */
    private def asText(value: Any): Maybe[String] =
        value match
            case v: java.time.LocalDateTime  => Present(dateTime(v))
            case v: java.time.LocalTime      => Present(time(v))
            case v: java.time.OffsetDateTime => Present(dateTime(v.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime))
            case v: java.time.ZonedDateTime =>
                Present(dateTime(v.withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime))
            // kyo.Instant is opaque over java.time.Instant, so this arm covers both.
            case v: java.time.Instant =>
                Present(dateTime(java.time.LocalDateTime.ofInstant(v, java.time.ZoneOffset.UTC)))
            case v: java.time.Duration => Present(duration(v))
            // OffsetTime is deliberately absent: it has no temporal column on this engine and is stored as text whose
            // rendering CARRIES the offset, which rewriting here drops, and the row then fails to decode.
            case _ => Absent

    private def pad(value: Int, width: Int): String =
        val s = value.toString
        if s.length >= width then s else "0" * (width - s.length) + s

    /** The fractional part, `.` and six digits, or empty when the value lands on a whole second. Six digits always, never trimmed: the
      * trimming is what the server gets wrong, so the text carries every leading zero explicitly.
      */
    private def fraction(nanos: Int): String =
        val micros = nanos / 1000
        if micros == 0 then "" else "." + pad(micros, 6)

    private def dateTime(v: java.time.LocalDateTime): String =
        s"${pad(v.getYear, 4)}-${pad(v.getMonthValue, 2)}-${pad(v.getDayOfMonth, 2)} " +
            s"${pad(v.getHour, 2)}:${pad(v.getMinute, 2)}:${pad(v.getSecond, 2)}${fraction(v.getNano)}"

    private def time(v: java.time.LocalTime): String =
        s"${pad(v.getHour, 2)}:${pad(v.getMinute, 2)}:${pad(v.getSecond, 2)}${fraction(v.getNano)}"

    /** A duration is a TIME span here, which reaches past 24 hours and may be negative, so the hour field is not a clock hour. */
    private def duration(v: java.time.Duration): String =
        val negative = v.isNegative
        val abs      = if negative then v.negated else v
        val hours    = abs.toHours
        val minutes  = abs.toMinutesPart
        val seconds  = abs.toSecondsPart
        val nanos    = abs.toNanosPart
        val sign     = if negative then "-" else ""
        s"$sign${pad(hours.toInt, 2)}:${pad(minutes, 2)}:${pad(seconds, 2)}${fraction(nanos)}"
    end duration

end DoltTemporalBinds
