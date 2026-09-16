package kyo.internal

import java.time.ZoneOffset
import kyo.*

/** The civil calendar against java.time's, which is the only independent implementation available to hold it to.
  *
  * `CivilTest`, in shared, asks whether the conversion inverts itself and whether it follows the Gregorian rule. Both
  * can hold while the answer is wrong in the same way in both directions. This asks a second implementation, on the
  * one platform that has one, and it is the reason the module can stop calling that implementation at run time: the
  * HTTP Date header and every other civil field now come from `Civil`, and this says they are the same fields.
  */
class CivilJdkTest extends kyo.test.Test[Any]:

    private def sameAsJava(second: Long): Maybe[String] =
        val civil = Civil.of(second)
        val jdk   = java.time.Instant.ofEpochSecond(second).atZone(ZoneOffset.UTC)
        if civil.year != jdk.getYear then Maybe(s"year ${civil.year} against ${jdk.getYear}")
        else if civil.month != jdk.getMonthValue then Maybe(s"month ${civil.month} against ${jdk.getMonthValue}")
        else if civil.day != jdk.getDayOfMonth then Maybe(s"day ${civil.day} against ${jdk.getDayOfMonth}")
        else if civil.hour != jdk.getHour then Maybe(s"hour ${civil.hour} against ${jdk.getHour}")
        else if civil.minute != jdk.getMinute then Maybe(s"minute ${civil.minute} against ${jdk.getMinute}")
        else if civil.second != jdk.getSecond then Maybe(s"second ${civil.second} against ${jdk.getSecond}")
        // `Civil` counts Monday as 0 through Sunday as 6, which is what indexes the IMF-fixdate day names.
        // `java.time.DayOfWeek` counts Monday as 1 through Sunday as 7, so the two differ by exactly one.
        else if civil.dayOfWeek != jdk.getDayOfWeek.getValue - 1 then
            Maybe(s"day of week ${civil.dayOfWeek} against ${jdk.getDayOfWeek.getValue - 1}")
        else Absent
        end if
    end sameAsJava

    private def report(seconds: Iterable[Long]): Chunk[String] =
        Chunk.from(seconds.flatMap(s => sameAsJava(s).map(detail => s"$s: $detail").toList))

    "every field agrees, across two centuries" in {
        // A prime step so the sample does not land on the same hour or weekday each time, over 1900 to 2100, which
        // covers 1900 (a century that is not a leap year), 2000 (one that is) and every ordinary leap year between.
        val from = -2208988800L
        val to   = 4102444800L
        val step = 100003L
        val bad  = report(Iterator.iterate(from)(_ + step).takeWhile(_ < to).to(Iterable))
        assert(bad.isEmpty, bad.take(5).mkString("; "))
    }

    "every field agrees at the ends and the boundaries" in {
        val edges = Chunk(
            0L,            // the epoch itself
            -1L,           // the second before it
            -62135596800L, // 0001-01-01T00:00:00Z
            253402300799L, // 9999-12-31T23:59:59Z
            951782400L,    // 2000-02-29, the leap day of a leap century
            -2203977600L,  // 1900-02-28, the year a century is not a leap year
            4107542400L,   // 2100-03-01, the next one
            1709164800L,   // 2024-02-29
            -2208988800L,  // 1900-01-01, the first second of the swept range
            4102444799L    // the last second of 2099
        )
        val bad = report(edges)
        assert(bad.isEmpty, bad.mkString("; "))
    }

    "the day of the week agrees over a full week at every century boundary" in {
        val starts = Chunk(-2208988800L, -62135596800L, 946684800L, 4102444800L)
        val bad    = report(starts.flatMap(start => (0 until 7).map(d => start + d * 86400L)))
        assert(bad.isEmpty, bad.mkString("; "))
    }

end CivilJdkTest
