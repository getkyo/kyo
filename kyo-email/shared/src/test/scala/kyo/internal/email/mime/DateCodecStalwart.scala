package kyo.internal.email.mime

import kyo.*
import kyo.internal.Ascii
import kyo.internal.email.mime.HeaderCodecStalwart.escape
import kyo.internal.email.mime.HeaderCodecStalwart.unescape
import kyo.internal.email.vectors.EmbeddedDate

/** Where the module's reading of dates differs from Stalwart mail-parser's, over every vector of its `date.json`, compared as epoch
  * seconds. The rows are compared with `stalwart-date-differences.tsv`, kept by hand from the rows a failing test prints; a difference no
  * reason explains fails.
  */
object DateCodecStalwart:

    final case class DateExpected(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        tz_before_gmt: Boolean,
        tz_hour: Int,
        tz_minute: Int
    ) derives Schema, CanEqual

    final case class DateVector(header: String, expected: Maybe[DateExpected]) derives Schema, CanEqual

    def vectors(using Frame): Chunk[DateVector] =
        EmailLiterals.valid(Json.decode[Chunk[DateVector]](EmbeddedDate.text))

    /** Stalwart's reading as an epoch second, computed by java.time. */
    def referenceReading(vector: DateVector): Maybe[Long] =
        vector.expected.map { e =>
            val sign = if e.tz_before_gmt then -1 else 1
            java.time.LocalDateTime.of(e.year, e.month, e.day, e.hour, e.minute, e.second)
                .toEpochSecond(java.time.ZoneOffset.ofTotalSeconds(sign * (e.tz_hour * 3600 + e.tz_minute * 60)))
        }

    def moduleReading(vector: DateVector): Maybe[Long] =
        DateCodec.parse(HeaderCodecStalwart.field(vector.header).value).map(DateCodec.epochSecondOf)

    /** Why the module and Stalwart read a date vector differently. */
    enum Reason(val description: String) derives CanEqual:
        case ZoneWithDigits
            extends Reason(
                "the zone is letters followed by digits (EST5EDT, a POSIX TZ string); Stalwart reads the letters, and the module reads a zone followed by anything but CFWS as unreadable"
            )
        case NotRfc5322DateTime
            extends Reason(
                "Stalwart finds a date in text that is not an RFC 5322 date-time with its obsolete forms: no month name (a numeric month, a list of numbers), or a first word before the date that is neither a day nor a month name (a date after other text); the module reads only that grammar, so the date is absent and the header stays in the headers"
            )
    end Reason

    final case class Row(index: Int, header: String, module: String, reference: String, reason: Reason) derives CanEqual:
        def line: String = Seq(index.toString, escape(header), escape(module), escape(reference), reason.toString).mkString("\t")

    private def show(reading: Maybe[Long]): String = reading.map(s => java.time.Instant.ofEpochSecond(s).toString).getOrElse("null")

    def observed(using Frame): Chunk[Row] =
        Chunk.from(vectors.zipWithIndex).flatMap { (vector, index) =>
            val module    = moduleReading(vector)
            val reference = referenceReading(vector)
            if module == reference then Chunk.empty
            else
                classify(vector.header, module, reference) match
                    case Present(reason) => Chunk(Row(index, vector.header, show(module), show(reference), reason))
                    case Absent          =>
                        throw new IllegalStateException(
                            s"vector $index [${escape(vector.header)}]: the module gives [${show(module)}], Stalwart [${show(reference)}], and no reason explains it"
                        )
            end if
        }

    private val zoneWithDigits = "[A-Za-z]+[0-9][A-Za-z0-9]*\\s*$".r

    private val letterRun = "[A-Za-z]+".r

    private val monthNames = Set("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val dayNames = Set("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    // No month name at all, or a first word before the first digit that is neither a day name nor a month name.
    private def notDateTime(header: String): Boolean =
        val runs       = letterRun.findAllMatchIn(header).toSeq
        val firstDigit = header.indexWhere(Ascii.isDigit)
        !runs.exists(r => monthNames.contains(Ascii.toLower(r.matched))) ||
        runs.headOption.exists { r =>
            val word = Ascii.toLower(r.matched)
            !dayNames.contains(word) && !monthNames.contains(word) && r.start < firstDigit
        }
    end notDateTime

    def classify(header: String, module: Maybe[Long], reference: Maybe[Long]): Maybe[Reason] =
        (module, reference) match
            case (Absent, Present(_)) if zoneWithDigits.findFirstIn(header).isDefined => Present(Reason.ZoneWithDigits)
            case (Absent, Present(_)) if notDateTime(header)                          => Present(Reason.NotRfc5322DateTime)
            case _                                                                    => Absent

    def render(rows: Seq[Row]): String = rows.map(_.line).mkString("\n")

    def parse(text: String): Chunk[Row] =
        Chunk.from(text.split("\n").toSeq.filterNot(l => l.startsWith("#") || l.isEmpty)).map { line =>
            val fields = line.split("\t", -1)
            Row(fields(0).toInt, unescape(fields(1)), unescape(fields(2)), unescape(fields(3)), Reason.valueOf(fields(4)))
        }

end DateCodecStalwart
