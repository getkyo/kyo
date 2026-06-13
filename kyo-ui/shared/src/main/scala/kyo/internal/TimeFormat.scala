package kyo.internal

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Cross-platform temporal tick-label formatter.
  *
  * Formats epoch millis to a calendar label whose granularity is derived from the tick step: sub-day step uses
  * HH:mm, day-to-month step uses yyyy-MM-dd, year-scale step uses yyyy. java.time works in shared source because
  * scala-java-time supplies the JS/Native shim.
  */
private[kyo] object TimeFormat:

    private val hourMin = DateTimeFormatter.ofPattern("HH:mm")
    private val isoDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val yearFmt = DateTimeFormatter.ofPattern("yyyy")

    private val dayMillis  = 86_400_000L
    private val yearMillis = 365L * dayMillis

    /** Formats `ms` (epoch millis, UTC) at the granularity implied by `stepMillis`. */
    def epochMillisLabel(ms: Long, stepMillis: Long): String =
        val fmt =
            if stepMillis < dayMillis then hourMin
            else if stepMillis < yearMillis then isoDate
            else yearFmt
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).format(fmt)
    end epochMillisLabel

end TimeFormat
