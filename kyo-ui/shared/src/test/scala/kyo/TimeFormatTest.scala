package kyo

import kyo.Test
import kyo.internal.TimeFormat

class TimeFormatTest extends Test:

    // 2024-06-01 00:00:00 UTC = 1717200000000L epoch millis
    // 2024-06-03 00:00:00 UTC = 1717372800000L epoch millis
    private val june1Millis = 1717200000000L
    private val june3Millis = 1717372800000L
    private val dayMs       = 86_400_000L
    private val hourMs      = 3_600_000L
    private val yearMs      = 365L * dayMs * 2L // 2 years

    // INV-010, test 7: day-span time labels are calendar dates
    "day-span epoch millis format to yyyy-MM-dd" in {
        val label1 = TimeFormat.epochMillisLabel(june1Millis, dayMs)
        val label2 = TimeFormat.epochMillisLabel(june3Millis, dayMs)
        assert(label1 == "2024-06-01", s"Expected 2024-06-01, got: $label1")
        assert(label2 == "2024-06-03", s"Expected 2024-06-03, got: $label2")
    }

    // INV-010, test 8: sub-day step uses HH:mm; year-scale step uses yyyy
    "sub-day step formats HH:mm and year-scale formats yyyy" in {
        val subDayLabel = TimeFormat.epochMillisLabel(june1Millis, hourMs)
        val yearLabel   = TimeFormat.epochMillisLabel(june1Millis, yearMs)
        assert(subDayLabel.matches("\\d{2}:\\d{2}"), s"Expected HH:mm pattern, got: $subDayLabel")
        assert(yearLabel == "2024", s"Expected 2024, got: $yearLabel")
    }

end TimeFormatTest
