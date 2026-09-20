package kyo

import java.time.DateTimeException
import java.time.Instant as JInstant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.SplittableRandom
import kyo.internal.InstantText

/** Holds [[Instant]] equal to `java.time.Instant` on the JVM: the text it prints, the text it accepts (and the index and message of every
  * rejection), its arithmetic, truncation, ordering, and conversions. Each check runs over hand-picked corners and over generated values
  * from a fixed seed, so a failure reproduces.
  */
class InstantJdkTest extends kyo.test.Test[Any]:

    given CanEqual[JInstant, JInstant]             = CanEqual.derived
    given CanEqual[Duration.Units, Duration.Units] = CanEqual.derived

    private val MinSecond            = InstantText.MinSecond
    private val MaxSecond            = InstantText.MaxSecond
    private val SecondsPer10000Years = 146097L * 25L * 86400L
    private val Seconds0000To1970    = ((146097L * 5L) - (30L * 365L + 7L)) * 86400L

    private def random = new SplittableRandom(0x5eed1878L)

    private val nanoCorners = Seq(0, 1, 9, 10, 100, 999, 1000, 50000, 999999, 1000000, 5000000, 120000000, 100000000, 999999999)

    /** Seconds at the boundaries the printer branches on: the eras, year 0, the 10,000-year cycles near zero and near both range ends, and
      * the range ends themselves.
      */
    private val secondCorners: Seq[Long] =
        val cycles     = (-100000L to -99995L) ++ (-3L to 3L) ++ (99995L to 100000L)
        val cycleEdges = cycles.map(_ * SecondsPer10000Years - Seconds0000To1970)
        val around     = Seq(-1L, 0L, 1L, 86399L, 86400L)
        val points     = Seq(0L, -1L, 1L, MinSecond, MaxSecond, -Seconds0000To1970, -Seconds0000To1970 - 1, -Seconds0000To1970 + 1) ++
            cycleEdges.filter(s => s > MinSecond + 86400 && s < MaxSecond - 86400)
        points.flatMap(p => around.map(p + _)).filter(s => s >= MinSecond && s <= MaxSecond).distinct
    end secondCorners

    private def generatedInstants(count: Int): Seq[JInstant] =
        val r = random
        Seq.fill(count) {
            val seconds = r.nextLong(MinSecond, MaxSecond + 1)
            val nanos   = if r.nextBoolean() then nanoCorners(r.nextInt(nanoCorners.size)) else r.nextInt(1000000000)
            JInstant.ofEpochSecond(seconds, nanos.toLong)
        }
    end generatedInstants

    private def cornerInstants: Seq[JInstant] =
        for
            seconds <- secondCorners
            nanos   <- Seq(0, 1, 1000, 1000000, 999999999)
        yield JInstant.ofEpochSecond(seconds, nanos.toLong)

    "show prints what java.time.Instant.toString prints" - {
        "at the range ends, the epoch, and every era and cycle boundary" in {
            (cornerInstants ++ Seq(JInstant.MIN, JInstant.MAX, JInstant.EPOCH)).foreach { j =>
                assert(Instant.fromJava(j).show == j.toString, s"seconds=${j.getEpochSecond} nanos=${j.getNano}")
            }
            succeed
        }

        "for generated instants" in {
            generatedInstants(200000).foreach(j => assert(Instant.fromJava(j).show == j.toString, s"$j"))
            succeed
        }

        "through toString, as an interpolated Instant" in {
            generatedInstants(1000).foreach(j => assert(s"${Instant.fromJava(j)}" == j.toString))
            succeed
        }
    }

    /** The outcome of a parse, comparable across the two implementations. */
    private def jdkParse(text: String): Either[DateTimeParseException, JInstant] =
        try Right(JInstant.parse(text))
        catch case e: DateTimeParseException => Left(e)

    private def assertSameParse(text: String)(using kyo.test.AssertScope): Unit =
        (jdkParse(text), Instant.parse(text)) match
            case (Right(expected), Result.Success(actual)) =>
                assert(actual.toJava == expected, s"'$text' parsed to ${actual.toJava}, expected $expected")
            case (Left(expected), Result.Failure(actual)) =>
                assert(
                    actual.getErrorIndex == expected.getErrorIndex,
                    s"'$text' error index ${actual.getErrorIndex}, expected ${expected.getErrorIndex}"
                )
                assert(actual.getParsedString == expected.getParsedString)
                // The JDK's message for an input outside the range names its internal parse state; only the prefix is stable.
                if expected.getMessage.contains("Unable to obtain Instant") then
                    assert(actual.getMessage.startsWith(expected.getMessage.takeWhile(_ != ':') + ":"), s"'$text': ${actual.getMessage}")
                else assert(actual.getMessage == expected.getMessage, s"'$text'")
                end if
            case (expected, actual) =>
                fail(s"'$text': java.time gave $expected but Instant.parse gave $actual")
    end assertSameParse

    "parse accepts and rejects what java.time.Instant.parse does" - {
        "for every instant's own text" in {
            (cornerInstants ++ generatedInstants(50000)).foreach(j => assertSameParse(j.toString))
            succeed
        }

        "for hand-picked corners" in {
            val cases = Seq(
                "",
                "2024",
                "2024-01-01",
                "2024-01-01T",
                "2024-01-01T10:15",
                "2024-01-01T10:15Z",
                "2024-01-01T10:15:30",
                "2024-01-01T10:15:30Z",
                "2024-01-01t10:15:30z",
                "2024-01-01T10:15:30.Z",
                "2024-01-01T10:15:30.1Z",
                "2024-01-01T10:15:30.123456789Z",
                "2024-01-01T10:15:30.1234567890Z",
                "2024-01-01T10:15:30,5Z",
                "2024-01-01T10:15:30+01:00",
                "2024-01-01T10:15:30-01:00",
                "2024-01-01T10:15:30+01:00:30",
                "2024-01-01T10:15:30+01:00:3",
                "2024-01-01T10:15:30+01",
                "2024-01-01T10:15:30+0100",
                "2024-01-01T10:15:30+18:00",
                "2024-01-01T10:15:30-18:00",
                "2024-01-01T10:15:30+18:01",
                "2024-01-01T10:15:30+23:00",
                "2024-01-01T10:15:30+24:00",
                "2024-01-01T10:15:30+59:00",
                "2024-01-01T10:15:30+60:00",
                "2024-01-01T10:15:30+01:60",
                "2024-01-01T10:15:30-00:00",
                "2024-01-01T10:15:30ZZ",
                "2024-01-01T10:15:30Z ",
                " 2024-01-01T10:15:30Z",
                "2024-01-01T24:00:00Z",
                "2024-12-31T24:00:00Z",
                "2024-01-01T24:00:00.000000001Z",
                "2024-01-01T24:00:01Z",
                "2024-01-01T24:01:00Z",
                "2024-01-01T23:59:60Z",
                "2024-01-01T23:59:60.5Z",
                "2024-01-01T12:00:60Z",
                "2024-01-01T23:60:00Z",
                "2024-01-01T25:00:00Z",
                "2024-02-29T00:00:00Z",
                "2023-02-29T00:00:00Z",
                "1900-02-29T00:00:00Z",
                "2000-02-29T00:00:00Z",
                "2024-04-31T00:00:00Z",
                "2024-00-01T00:00:00Z",
                "2024-13-01T00:00:00Z",
                "2024-01-00T00:00:00Z",
                "2024-01-32T00:00:00Z",
                "0000-01-01T00:00:00Z",
                "-0000-01-01T00:00:00Z",
                "-0001-01-01T00:00:00Z",
                "+0001-01-01T00:00:00Z",
                "+2024-01-01T00:00:00Z",
                "10000-01-01T00:00:00Z",
                "+10000-01-01T00:00:00Z",
                "-10000-01-01T00:00:00Z",
                "-10000-01-01T00:00:00.5Z",
                "+99999-12-31T23:59:59Z",
                "+1000000000-12-31T23:59:59.999999999Z",
                "+1000000000-12-31T23:59:59.999999999-01:00",
                "+1000000001-01-01T00:00:00Z",
                "-1000000000-01-01T00:00:00Z",
                "-1000000000-01-01T00:00:00+01:00",
                "-1000000001-01-01T00:00:00Z",
                "+9999999999-01-01T00:00:00Z",
                "+99999999999-01-01T00:00:00Z",
                "-9999999999-01-01T00:00:00Z",
                "20240-01-01T00:00:00Z",
                "202-01-01T00:00:00Z",
                "2024-1-01T00:00:00Z",
                "2024-01-1T00:00:00Z",
                "2024-01-01T1:00:00Z",
                "2024-01-01T10:1:00Z",
                "2024-01-01T10:10:1Z",
                "2024-+1-01T00:00:00Z",
                "2024--1-01T00:00:00Z",
                "2024/01/01T00:00:00Z",
                "2024-01-01 00:00:00Z",
                "2024-01-01T00:00:00+",
                "2024-01-01T00:00:00+01:",
                "2024-01-01T00:00:00x",
                "٢٠٢٤-01-01T00:00:00Z",
                "2024-01-01T00:00:00Z" + "x" * 80,
                "x" * 80
            )
            cases.foreach(assertSameParse)
            succeed
        }

        "for every single-character mutation of valid texts" in {
            val alphabet = "0123456789-+:.TZtz ,x"
            val seeds    = Seq(
                "2024-02-29T23:59:60.123456789Z",
                "+10000-12-31T24:00:00-18:00",
                "-0001-01-01T00:00:00.5+01:00:30",
                "1970-01-01T00:00:00Z"
            )
            seeds.foreach { seed =>
                (0 to seed.length).foreach { i =>
                    alphabet.foreach { c =>
                        if i < seed.length then assertSameParse(seed.updated(i, c))
                        assertSameParse(seed.patch(i, c.toString, 0))
                    }
                    if i < seed.length then assertSameParse(seed.patch(i, "", 1))
                }
            }
            succeed
        }

        "for generated noisy texts" in {
            val r        = random
            val alphabet = "0123456789-+:.TZtz"
            (1 to 200000).foreach { _ =>
                val base = generatedInstantText(r)
                val text =
                    r.nextInt(4) match
                        case 0 => base
                        case 1 => base.updated(r.nextInt(base.length), alphabet.charAt(r.nextInt(alphabet.length)))
                        case 2 => base.patch(r.nextInt(base.length + 1), alphabet.charAt(r.nextInt(alphabet.length)).toString, 0)
                        case _ => base.patch(r.nextInt(base.length), "", 1)
                assertSameParse(text)
            }
            succeed
        }
    }

    /** A plausible instant text with a random year width and sign, fields that may be out of range, a fraction, and an offset. */
    private def generatedInstantText(r: SplittableRandom): String =
        val yearDigits = 1 + r.nextInt(11)
        val year       = (1 to yearDigits).map(_ => ('0' + r.nextInt(10)).toChar).mkString
        val sign       = r.nextInt(3) match
            case 0 => "";
            case 1 => "+";
            case _ => "-"
        def two(max: Int) = f"${r.nextInt(max)}%02d"
        val fraction      = if r.nextBoolean() then "" else "." + (1 to r.nextInt(11)).map(_ => ('0' + r.nextInt(10)).toChar).mkString
        val offset        =
            r.nextInt(4) match
                case 0 => "Z"
                case 1 => s"+${two(26)}:${two(61)}"
                case 2 => s"-${two(26)}:${two(61)}:${two(61)}"
                case _ => "z"
        s"$sign$year-${two(14)}-${two(33)}T${two(26)}:${two(62)}:${two(62)}$fraction$offset"
    end generatedInstantText

    "arithmetic matches java.time.Instant, clamped at the range ends" - {
        "adding and subtracting durations" in {
            val r         = random
            val durations = Seq(0L, 1L, 999999999L, 1000000000L, 86400000000000L, Long.MaxValue - 1) ++
                Seq.fill(2000)(r.nextLong(0L, Long.MaxValue))
            val instants = cornerInstants.take(2000) ++ generatedInstants(2000)
            instants.zip(durations.iterator ++ Iterator.continually(r.nextLong(0L, Long.MaxValue))).foreach { case (j, nanos) =>
                val kyoInstant = Instant.fromJava(j)
                val d          = Duration.fromNanos(nanos)
                val plus       =
                    try j.plusNanos(d.toNanos)
                    catch case _: DateTimeException | _: ArithmeticException => JInstant.MAX
                val minus =
                    try j.minusNanos(d.toNanos)
                    catch case _: DateTimeException | _: ArithmeticException => JInstant.MIN
                assert((kyoInstant + d).toJava == (if d == Duration.Zero then j else plus), s"$j + $nanos")
                assert((kyoInstant - d).toJava == (if d == Duration.Zero then j else minus), s"$j - $nanos")
            }
            assert(Instant.Epoch + Duration.Infinity == Instant.Max)
            assert(Instant.Epoch - Duration.Infinity == Instant.Min)
            succeed
        }

        "the duration between two instants" in {
            val r = random
            generatedInstants(5000).zip(generatedInstants(5000).reverse).foreach { case (a, b) =>
                val seconds  = a.getEpochSecond - b.getEpochSecond
                val nanos    = a.getNano - b.getNano
                val expected = Duration.fromNanos(seconds.seconds.toNanos + nanos)
                assert(Instant.fromJava(a) - Instant.fromJava(b) == expected)
            }
            succeed
        }

        "truncation to each unit" in {
            val units = Seq(
                Duration.Units.Nanos,
                Duration.Units.Micros,
                Duration.Units.Millis,
                Duration.Units.Seconds,
                Duration.Units.Minutes,
                Duration.Units.Hours,
                Duration.Units.Days
            )
            (cornerInstants ++ generatedInstants(20000)).foreach { j =>
                units.foreach { unit =>
                    assert(Instant.fromJava(j).truncatedTo(unit).toJava == j.truncatedTo(unit.chronoUnit), s"$j to $unit")
                }
            }
            succeed
        }

        "ordering, equality, and hashing" in {
            val all = (cornerInstants ++ generatedInstants(5000)).toVector
            all.zip(all.reverse).foreach { case (a, b) =>
                val (ka, kb) = (Instant.fromJava(a), Instant.fromJava(b))
                val expected = a.compareTo(b).sign
                assert(summon[Ordering[Instant]].compare(ka, kb).sign == expected)
                assert((ka < kb) == (expected < 0) && (ka <= kb) == (expected <= 0))
                assert((ka > kb) == (expected > 0) && (ka >= kb) == (expected >= 0))
                assert((ka == kb) == a.equals(b))
                assert((ka min kb).toJava == (if a.isBefore(b) then a else b))
                assert((ka max kb).toJava == (if a.isAfter(b) then a else b))
                assert(ka.hashCode == a.hashCode)
            }
            succeed
        }

        "of and the java.time conversions" in {
            generatedInstants(5000).foreach { j =>
                assert(Instant.fromJava(j).toJava == j)
            }
            assert(Instant.Min.toJava == JInstant.MIN && Instant.Max.toJava == JInstant.MAX && Instant.Epoch.toJava == JInstant.EPOCH)
            val r = random
            (1 to 5000).foreach { _ =>
                val seconds  = r.nextLong(0L, 9000000000L)
                val nanos    = r.nextLong(0L, Long.MaxValue)
                val expected =
                    JInstant.ofEpochSecond(Duration.fromNanos(seconds * 1000000000L).toSeconds, Duration.fromNanos(nanos).toNanos)
                assert(Instant.of(Duration.fromNanos(seconds * 1000000000L), Duration.fromNanos(nanos)).toJava == expected)
            }
            succeed
        }
    }

    "Duration units carry the durations of their java.time units" in {
        Duration.Units.values.foreach { unit =>
            assert(unit.factor == unit.chronoUnit.getDuration.toNanos.toDouble, s"$unit")
            assert(Duration.Units.fromJava(unit.chronoUnit) == unit)
        }
        succeed
    }

    "the Instant flag reader accepts what java.time.Instant.parse or OffsetDateTime.parse accepts" in {
        val reader = summon[Flag.Reader.Scalar[Instant]]
        val cases  = Seq(
            "2024-01-01T10:15:30Z",
            " 2024-01-01T10:15:30Z ",
            "2024-01-01T10:15Z",
            "2024-01-01T10:15+01:00",
            "2024-01-01t10:15z",
            "2024-01-01T24:00Z",
            "2024-01-01T24:00:00Z",
            "2024-01-01T23:59:60Z",
            "2024-01-01T10:15:5Z",
            "2024-01-01T10:15.5Z",
            "+1000000000-01-01T00:00Z",
            "+999999999-12-31T23:59-18:00",
            "-999999999-01-01T00:00+18:00",
            "2024-01-01",
            "garbage"
        )
        val r = random
        (cases ++ Seq.fill(20000)(generatedInstantText(r).replaceFirst(":[0-9]{2}(?=[.Zz+-])", ""))).foreach { text =>
            val trimmed  = text.trim
            val expected =
                try Some(JInstant.parse(trimmed))
                catch
                    case _: DateTimeParseException =>
                        try Some(OffsetDateTime.parse(trimmed).toInstant)
                        catch case _: Throwable => None
            reader(text) match
                case Right(instant) => assert(expected.contains(instant.toJava), s"'$text' read as ${instant.toJava}, expected $expected")
                case Left(error)    =>
                    assert(expected.isEmpty, s"'$text' was rejected, expected $expected")
                    assert(error.getMessage == s"Invalid Instant format: $text")
            end match
        }
        succeed
    }
end InstantJdkTest
