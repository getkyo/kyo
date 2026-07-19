package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*

class LinuxScanTest extends kyo.test.Test[Any]:

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

    "LinuxScan.longField" - {

        "reads the index-th token on the matched line and scales it" in {
            val (bytes, len) = span("cpu  100 200 300\nintr 9\n")
            val from         = LinuxScan.lineFields(bytes, len, LinuxScan.ascii("cpu "))
            assert(from >= 0)
            assert(LinuxScan.longField(bytes, len, from, 0, 1000L) == 100000L)
            assert(LinuxScan.longField(bytes, len, from, 1, 1L) == 200L)
            assert(LinuxScan.longField(bytes, len, from, 2, 1L) == 300L)
        }

        "a token past the end of the line is absent, never a value from the next line (no cross-line spillover)" in {
            val (bytes, len) = span("cpu 1 2\nintr 999\n")
            val from         = LinuxScan.lineFields(bytes, len, LinuxScan.ascii("cpu "))
            assert(LinuxScan.longField(bytes, len, from, 5, 1L) == Path.ReadHandle.AbsentLong)
        }

        "a non-numeric token is absent, not a partial value" in {
            val (bytes, len) = span("cpu abc 5\n")
            val from         = LinuxScan.lineFields(bytes, len, LinuxScan.ascii("cpu "))
            assert(LinuxScan.longField(bytes, len, from, 0, 1L) == Path.ReadHandle.AbsentLong)
        }

        "an overflowing token is absent, never a wrapped negative" in {
            val (bytes, len) = span("cpu 99999999999999999999999 1\n")
            val from         = LinuxScan.lineFields(bytes, len, LinuxScan.ascii("cpu "))
            assert(LinuxScan.longField(bytes, len, from, 0, 1L) == Path.ReadHandle.AbsentLong)
        }

        "a truncated final line with no trailing newline still decodes" in {
            val (bytes, len) = span("cpu 7 8")
            val from         = LinuxScan.lineFields(bytes, len, LinuxScan.ascii("cpu "))
            assert(LinuxScan.longField(bytes, len, from, 1, 1L) == 8L)
        }
    }

    "LinuxScan.lineFields" - {

        "a missing prefix yields -1 rather than matching a later line" in {
            val (bytes, len) = span("intr 1 2 3\n")
            assert(LinuxScan.lineFields(bytes, len, LinuxScan.ascii("cpu ")) == -1)
        }
    }

    "LinuxScan.keyedLong" - {

        "reads a `Name: value` line and scales it; an absent key is the sentinel" in {
            val (bytes, len) = span("MemTotal:       16384 kB\nMemFree:  512 kB\n")
            assert(LinuxScan.keyedLong(bytes, len, LinuxScan.ascii("MemTotal:"), 0, 1024L) == 16384L * 1024L)
            assert(LinuxScan.keyedLong(bytes, len, LinuxScan.ascii("MemFree:"), 0, 1024L) == 512L * 1024L)
            assert(LinuxScan.keyedLong(bytes, len, LinuxScan.ascii("Nope:"), 0, 1024L) == Path.ReadHandle.AbsentLong)
        }
    }

    "LinuxScan.doubleField" - {

        "decodes a fixed-point token with four arguments and no intermediate String" in {
            val (bytes, len) = span("0.57 1.25 3\n")
            assert(LinuxScan.doubleField(bytes, len, 0, 0) == 0.57)
            assert(LinuxScan.doubleField(bytes, len, 0, 1) == 1.25)
            assert(LinuxScan.doubleField(bytes, len, 0, 2) == 3.0)
        }

        "an absent or malformed field is NaN, which the cells skip" in {
            val (bytes, len) = span("x y\n")
            assert(LinuxScan.doubleField(bytes, len, 0, 0).isNaN)
            assert(LinuxScan.doubleField(bytes, len, 0, 9).isNaN)
        }
    }

    "LinuxScan.taggedDouble" - {

        "reads an `avg10=1.23`-style tag and is NaN when the tag is absent" in {
            val (bytes, len) = span("some avg10=1.23 avg60=4.56 total=789\n")
            assert(LinuxScan.taggedDouble(bytes, len, 0, LinuxScan.ascii("avg10=")) == 1.23)
            assert(LinuxScan.taggedDouble(bytes, len, 0, LinuxScan.ascii("avg60=")) == 4.56)
            assert(LinuxScan.taggedDouble(bytes, len, 0, LinuxScan.ascii("avg300=")).isNaN)
        }
    }

    "LinuxScan.taggedLong" - {

        "reads a `total=1234`-style tag and scales it; a missing tag is the sentinel" in {
            val (bytes, len) = span("some avg10=1.23 total=789\n")
            assert(LinuxScan.taggedLong(bytes, len, 0, LinuxScan.ascii("total="), 1000L) == 789L * 1000L)
            assert(LinuxScan.taggedLong(bytes, len, 0, LinuxScan.ascii("missing="), 1000L) == Path.ReadHandle.AbsentLong)
        }
    }

    "LinuxScan.scaled" - {

        "leaves the sentinel alone and scales a real value" in {
            assert(LinuxScan.scaled(Path.ReadHandle.AbsentLong, 1000000000L) == Path.ReadHandle.AbsentLong)
            assert(LinuxScan.scaled(5L, 1000L) == 5000L)
        }
    }

    "LinuxScan.plus" - {

        "treats an absent operand as contributing nothing" in {
            assert(LinuxScan.plus(Path.ReadHandle.AbsentLong, 7L) == 7L)
            assert(LinuxScan.plus(7L, Path.ReadHandle.AbsentLong) == 7L)
            assert(LinuxScan.plus(3L, 4L) == 7L)
        }

        "a sum of only-absent operands is itself absent, never zero" in {
            assert(LinuxScan.plus(Path.ReadHandle.AbsentLong, Path.ReadHandle.AbsentLong) == Path.ReadHandle.AbsentLong)
        }
    }

    "totality on an empty buffer" - {

        "every byte-scan entry point is total on an empty buffer (len 0), the shape MachineSampler.readInto passes when fill returns 0 on a transient proc file" in {
            val empty = Span.empty[Byte]
            assert(LinuxScan.lineFields(empty, 0, LinuxScan.ascii("MemTotal:")) == -1)
            assert(LinuxScan.longField(empty, 0, 0, 0, 1000L) == Path.ReadHandle.AbsentLong)
            assert(LinuxScan.keyedLong(empty, 0, LinuxScan.ascii("cpu "), 0, 1L) == Path.ReadHandle.AbsentLong)
            assert(LinuxScan.doubleField(empty, 0, 0, 0).isNaN)
            assert(LinuxScan.taggedDouble(empty, 0, 0, LinuxScan.ascii("avg10=")).isNaN)
            assert(LinuxScan.taggedLong(empty, 0, 0, LinuxScan.ascii("total="), 1L) == Path.ReadHandle.AbsentLong)
        }
    }

end LinuxScanTest
