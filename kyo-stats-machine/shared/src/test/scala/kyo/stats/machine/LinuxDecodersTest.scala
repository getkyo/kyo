package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*

class LinuxDecodersTest extends kyo.test.Test[Any]:

    given CanEqual[Machine.CpuReading, Machine.CpuReading]       = CanEqual.derived
    given CanEqual[Machine.MemoryReading, Machine.MemoryReading] = CanEqual.derived
    given CanEqual[Machine.SwapReading, Machine.SwapReading]     = CanEqual.derived
    given CanEqual[Machine.LoadReading, Machine.LoadReading]     = CanEqual.derived

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

    "LinuxDecoders.cpu" - {

        "scales the aggregate cpu line by the jiffies-to-ns scale; total sums every mode incl irq/softirq/steal; system folds in irq+softirq" in {
            // Field order: cpu user(1) nice(2) system(3) idle(4) iowait(5) irq(6) softirq(7) steal(8)
            //              guest(9) guest_nice(10). guest/guest_nice are already folded into user/nice by
            //              the kernel, so they are NOT summed again.
            val (bytes, len) = span("cpu 100 10 50 800 20 5 3 12 40 4\n")
            val result       = LinuxDecoders.cpu(bytes, len, 10000000L)
            assert(result == Present(Machine.CpuReading(
                // total = (user 100 + nice 10 + system 50 + idle 800 + iowait 20 + irq 5 + softirq 3 + steal 12) = 1000 jiffies
                total = Present(10000000000L),
                user = Present(1000000000L),
                // system mode includes irq+softirq: (50 + 5 + 3) = 58 jiffies
                system = Present(580000000L),
                idle = Present(8000000000L),
                iowait = Present(200000000L)
            )))
        }

        "an older kernel line with only user/nice/system/idle omits the trailing modes without under-counting the columns it has" in {
            // No iowait/irq/softirq/steal columns present: total sums the four present columns only, and
            // the missing modes are Absent (never fabricated as 0).
            val (bytes, len) = span("cpu 100 10 50 800\n")
            val result       = LinuxDecoders.cpu(bytes, len, 10000000L)
            assert(result == Present(Machine.CpuReading(
                total = Present(9600000000L), // (100 + 10 + 50 + 800) = 960 jiffies
                user = Present(1000000000L),
                system = Present(500000000L), // no irq/softirq to fold in
                idle = Present(8000000000L),
                iowait = Absent
            )))
        }

        "a non-numeric field routes that mode to Absent; the other modes stay present, no throw" in {
            // Field order is user(1) nice(2) system(3) idle(4) [iowait(5)]; "x" lands on system(3),
            // the malformed mode under test. nice(2) is consumed only for the total sum, not
            // reported as its own CpuReading field.
            val (bytes, len) = span("cpu 100 0 x 800\n")
            val result       = LinuxDecoders.cpu(bytes, len, 10000000L)
            assert(result == Present(Machine.CpuReading(
                total = Present(9000000000L),
                user = Present(1000000000L),
                system = Absent,
                idle = Present(8000000000L),
                iowait = Absent
            )))
        }
    }

    "LinuxDecoders.memory" - {

        "MemTotal/MemFree convert kB to bytes; a missing MemAvailable is Absent" in {
            val (bytes, len) = span("MemTotal:        1048576 kB\nMemFree:          204800 kB\n")
            val result       = LinuxDecoders.memory(bytes, len)
            assert(result == Present(Machine.MemoryReading(
                total = Present(1073741824L),
                available = Absent,
                free = Present(209715200L)
            )))
        }

        "a full MemTotal/MemAvailable/MemFree line set decodes all three fields" in {
            val (bytes, len) = span(
                "MemTotal:        2097152 kB\n" +
                    "MemAvailable:    1048576 kB\n" +
                    "MemFree:          524288 kB\n"
            )
            val result = LinuxDecoders.memory(bytes, len)
            assert(result == Present(Machine.MemoryReading(
                total = Present(2147483648L),
                available = Present(1073741824L),
                free = Present(536870912L)
            )))
        }
    }

    "LinuxDecoders.swap" - {

        "SwapTotal/SwapFree convert kB to bytes" in {
            val (bytes, len) = span("SwapTotal:        1048576 kB\nSwapFree:          262144 kB\n")
            val result       = LinuxDecoders.swap(bytes, len)
            assert(result == Present(Machine.SwapReading(
                total = Present(1073741824L),
                free = Present(268435456L)
            )))
        }

        "a missing SwapTotal/SwapFree line routes that field to Absent, no throw" in {
            val (bytes, len) = span("MemTotal:        1048576 kB\n")
            val result       = LinuxDecoders.swap(bytes, len)
            assert(result == Present(Machine.SwapReading(total = Absent, free = Absent)))
        }
    }

    "LinuxDecoders.load" - {

        "a full three-field line yields the three averages; a truncated line yields Absent" in {
            val (fullBytes, fullLen) = span("0.10 0.20 0.30 1/200 12345\n")
            val fullResult           = LinuxDecoders.load(fullBytes, fullLen)
            assert(fullResult == Present(Machine.LoadReading(Present(0.10), Present(0.20), Present(0.30))))

            val (truncBytes, truncLen) = span("0.10\n")
            assert(LinuxDecoders.load(truncBytes, truncLen) == Absent)
        }
    }

end LinuxDecodersTest
