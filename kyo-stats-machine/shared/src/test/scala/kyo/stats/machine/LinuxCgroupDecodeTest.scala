package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*

class LinuxCgroupDecodeTest extends kyo.test.Test[Any]:

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

    "LinuxCgroup.applyV1LimitSentinel (the production readV1Limit routing)" - {

        "a value at or above the v1 unlimited sentinel routes to Absent" in {
            // Decode the raw limit exactly as production does, then route it through the production sentinel
            // predicate (readV1Limit's own branch), never a test-local comparison.
            val (bytes, len) = span("9223372036854771712\n")
            val raw          = LinuxCgroupDecode.singleLong(bytes, len)
            assert(raw == Present(9223372036854771712L))
            assert(LinuxCgroup.applyV1LimitSentinel(raw) == Absent)
        }

        "a value below the sentinel passes through unchanged" in {
            val (bytes, len) = span("1073741824\n")
            val raw          = LinuxCgroupDecode.singleLong(bytes, len)
            assert(LinuxCgroup.applyV1LimitSentinel(raw) == Present(1073741824L))
        }

        "an Absent raw read stays Absent through the sentinel routing" in {
            assert(LinuxCgroup.applyV1LimitSentinel(Absent) == Absent)
        }
    }

    "LinuxCgroupDecode.statField" - {

        "nr_bursts/burst_usec are ignored; nr_throttled/throttled_usec decode correctly" in {
            val (bytes, len) = span(
                "nr_periods 500\n" +
                    "nr_throttled 12\n" +
                    "throttled_usec 5000\n" +
                    "nr_bursts 3\n" +
                    "burst_usec 700\n"
            )
            val throttled     = LinuxCgroupDecode.statField(bytes, len, "nr_throttled", 1L)
            val throttledUsec = LinuxCgroupDecode.statField(bytes, len, "throttled_usec", 1000L)
            assert(throttled == Present(12L))
            assert(throttledUsec == Present(5000000L))
        }

        "v2 microsecond scale (x1000) vs v1 nanosecond scale (x1) on the same raw field value" in {
            val (bytes, len) = span("throttled_usec 5000\nthrottled_time 5000000\n")
            val v2Scaled     = LinuxCgroupDecode.statField(bytes, len, "throttled_usec", 1000L)
            val v1Scaled     = LinuxCgroupDecode.statField(bytes, len, "throttled_time", 1L)
            assert(v2Scaled == Present(5000000L))
            assert(v1Scaled == Present(5000000L))
        }
    }

    "LinuxCgroupDecode.v2Limit" - {

        "the literal max routes memoryLimit to Absent" in {
            val (bytes, len) = span("max\n")
            assert(LinuxCgroupDecode.v2Limit(bytes, len) == Absent)
        }

        "a numeric value passes through unchanged" in {
            val (bytes, len) = span("1073741824\n")
            assert(LinuxCgroupDecode.v2Limit(bytes, len) == Present(1073741824L))
        }
    }

    "LinuxCgroupDecode.v2Quota" - {

        "the literal max quota routes to Absent; a numeric quota scales microseconds to nanoseconds" in {
            val (maxBytes, maxLen) = span("max 100000\n")
            assert(LinuxCgroupDecode.v2Quota(maxBytes, maxLen) == Absent)

            val (numBytes, numLen) = span("200000 100000\n")
            assert(LinuxCgroupDecode.v2Quota(numBytes, numLen) == Present(200000000L))
        }
    }

    "LinuxCgroupDecode.v2Period" - {

        "the second token scales microseconds to nanoseconds; a single-token line routes to Absent" in {
            val (bytes, len) = span("200000 100000\n")
            assert(LinuxCgroupDecode.v2Period(bytes, len) == Present(100000000L))

            val (truncBytes, truncLen) = span("200000\n")
            assert(LinuxCgroupDecode.v2Period(truncBytes, truncLen) == Absent)
        }
    }

end LinuxCgroupDecodeTest
