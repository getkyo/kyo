package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*

class LinuxPressureDecodeTest extends kyo.test.Test[Any]:

    given CanEqual[Machine.PsiReading, Machine.PsiReading] = CanEqual.derived

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

    "LinuxPressureDecode.parse" - {

        "some/full lines decode to avg percentages and total ns (total= microseconds x1000)" in {
            val (bytes, len) = span(
                "some avg10=1.00 avg60=2.00 avg300=3.00 total=1000\n" +
                    "full avg10=0.00 avg60=0.00 avg300=0.00 total=0\n"
            )
            val result = LinuxPressureDecode.parse(bytes, len)
            assert(result.isDefined)
            val (some, full) = result.getOrElse(throw new NoSuchElementException)
            assert(some == Machine.PsiReading(Present(1.00), Present(2.00), Present(3.00), Present(1000000L)))
            assert(full == Machine.PsiReading(Present(0.00), Present(0.00), Present(0.00), Present(0L)))
        }

        "a full total=0 line is parsed without error, distinct from the some line" in {
            val (bytes, len) = span(
                "some avg10=5.00 avg60=5.00 avg300=5.00 total=9999\n" +
                    "full avg10=0.00 avg60=0.00 avg300=0.00 total=0\n"
            )
            val result = LinuxPressureDecode.parse(bytes, len)
            assert(result.isDefined)
            val (some, full) = result.getOrElse(throw new NoSuchElementException)
            assert(some.total == Present(9999000L))
            assert(full == Machine.PsiReading(Present(0.00), Present(0.00), Present(0.00), Present(0L)))
        }
    }

end LinuxPressureDecodeTest
