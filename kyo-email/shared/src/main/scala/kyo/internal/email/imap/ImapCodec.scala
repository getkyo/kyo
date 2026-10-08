package kyo.internal.email.imap

import kyo.*
import kyo.internal.Ascii
import kyo.internal.charset.Utf8
import kyo.internal.email.charset.ImapModifiedUtf7
import kyo.internal.email.mime.DateCodec
import kyo.internal.email.net.LineConnection
import scala.util.boundary
import scala.util.boundary.break

/** IMAP's wire forms (RFC 9051 and RFC 3501): the strings a command carries, and the responses a server sends.
  *
  * A response arrives as segments: the text of a line, and after a line ending in a literal marker `{n}`, the `n` octets of the literal
  * followed by the rest of the response as another line. `parse` reads one response from its segments into the forms the session acts on,
  * and refuses anything else with the text it could not read, which the session reports as a `Protocol` transport failure.
  *
  * Octets keep their bytes (`Chunk[Byte]`, compared by content) until the session decides how to decode them: a mailbox name is modified
  * UTF-7 or UTF-8 depending on the server, and a message body is MIME.
  */
private[kyo] object ImapCodec:

    enum Segment derives CanEqual:
        case Text(octets: Span[Byte])
        case Literal(octets: Span[Byte])

    /** One value of IMAP's data grammar: `NIL`, a number, an atom (flags and fetch item names included), a quoted or literal string, or a
      * parenthesized list.
      */
    enum Value derives CanEqual:
        case Nil
        case Number(value: Long)
        case Atom(text: String)
        case Str(octets: Chunk[Byte])
        case Items(values: Chunk[Value])
    end Value

    enum Condition derives CanEqual:
        case Ok, No, Bad, Bye, PreAuth

    /** A bracketed response code: its atom, uppercased, and whatever followed it before the `]`, as sent. */
    final case class Code(atom: String, arguments: Maybe[String]) derives CanEqual

    enum Response derives CanEqual:
        case Continuation(text: String)
        case Tagged(tag: String, condition: Condition, code: Maybe[Code], text: String)
        case Untagged(data: Data)
    end Response

    enum Data derives CanEqual:
        case Status(condition: Condition, code: Maybe[Code], text: String)
        case Capability(names: Chunk[String])
        case Exists(count: Long)
        case Recent(count: Long)
        case Expunge(sequence: Long)
        case Flags(flags: Chunk[String])
        case ListItem(attributes: Chunk[String], delimiter: Maybe[Char], name: Chunk[Byte])
        case MailboxStatus(name: Chunk[Byte], items: Chunk[(String, Long)])
        case Search(numbers: Chunk[Long])
        case ESearch(ranges: Chunk[(Long, Long)])
        case Fetch(sequence: Long, items: Chunk[(String, Value)])
        case Other(keyword: String, values: Chunk[Value])
    end Data

    /** A piece of a command: text written as it is, or a literal, which a synchronizing form sends only after the server's `+`. */
    enum Part derives CanEqual:
        case Text(text: String)
        case Literal(octets: Chunk[Byte], synchronizing: Boolean)

    // RFC 9051's number64 reaches 2^63 - 1, past `Ascii.parseDigits`'s `Int`.
    private[imap] def number(text: String): Maybe[Long] =
        if text.isEmpty || text.length > 18 || !text.forall(Ascii.isDigit) then Absent
        else Present(text.foldLeft(0L)((acc, c) => acc * 10 + (c - '0')))

    /** The size a line's trailing literal marker (`{n}`, or `{n+}`) announces: the line is followed by that many octets, then more of the
      * response. `Absent` when the line does not end in one, or the size has more digits than a `Long` holds.
      */
    def literalAt(line: Span[Byte]): Maybe[Long] =
        val end = line.size - 1
        if end < 2 || line(end) != '}' then Absent
        else
            val last  = if line(end - 1) == '+' then end - 2 else end - 1
            var first = last
            while first >= 0 && line(first) >= '0' && line(first) <= '9' do first -= 1
            if first < 0 || first == last || line(first) != '{' then Absent
            else number(new String(line.slice(first + 1, last + 1).toArray, "US-ASCII"))
        end if
    end literalAt

    /** Whether a response starting with `line` can hold a literal. A continuation, a tagged completion and an untagged status response end
      * in text (RFC 9051 section 9's `resp-text`), which may itself end in `{n}`; only untagged data carries literals.
      */
    def carriesLiterals(line: Span[Byte]): Boolean =
        line.size >= 2 && line(0) == '*' && line(1) == ' ' && {
            val keyword = Ascii.toUpper(new String(line.slice(2, Math.min(line.size, 10)).toArray, "US-ASCII").takeWhile(_ != ' '))
            !statusKeywords.contains(keyword)
        }

    private val statusKeywords = Set("OK", "NO", "BAD", "BYE", "PREAUTH")

    def parse(segments: Chunk[Segment]): Result[String, Response] =
        segments.headMaybe match
            case Present(Segment.Text(first)) =>
                boundary(Result.succeed(new Reader(segments, Result.fail(LineConnection.shown(first))).response()))
            case _ => Result.fail("")

    // A cursor over the segments for one call of `parse`, which a refusal leaves through the call's boundary with `refused`.
    final private class Reader(segments: Chunk[Segment], refused: Result[String, Response])(using boundary.Label[Result[String, Response]]):

        private var segment = 0
        private var at      = 0

        private def text: Span[Byte] =
            segments(segment) match
                case Segment.Text(octets) => octets
                case Segment.Literal(_)   => refuse()

        private def refuse(): Nothing = break(refused)

        private def atEnd: Boolean = at >= text.size && segment >= segments.size - 1

        private def peek: Int = if at < text.size then text(at) & 0xff else -1

        private def expect(c: Char): Unit =
            if peek == c then at += 1 else refuse()

        private def space(): Unit = expect(' ')

        private def rest(): String =
            val out = Utf8.decode(text.slice(at, text.size))
            at = text.size
            out
        end rest

        def response(): Response =
            if peek == '+' then
                at += 1
                if peek == ' ' then at += 1
                Response.Continuation(rest())
            else if peek == '*' then
                at += 1
                space()
                val data = untagged()
                if !atEnd then refuse()
                Response.Untagged(data)
            else
                val tag = atom()
                space()
                val condition = conditionOf(Ascii.toUpper(atom())) match
                    case Present(c @ (Condition.Ok | Condition.No | Condition.Bad)) => c
                    case _                                                          => refuse()
                val (code, message) = statusText()
                Response.Tagged(tag, condition, code, message)

        private def untagged(): Data =
            val first = atom()
            number(first) match
                case Present(number) =>
                    space()
                    Ascii.toUpper(atom()) match
                        case "EXISTS"  => Data.Exists(number)
                        case "RECENT"  => Data.Recent(number)
                        case "EXPUNGE" => Data.Expunge(number)
                        case "FETCH"   =>
                            space()
                            Data.Fetch(number, fetchItems())
                        case other => Data.Other(other, remaining())
                    end match
                case Absent =>
                    val keyword = Ascii.toUpper(first)
                    conditionOf(keyword) match
                        case Present(condition) =>
                            val (code, message) = statusText()
                            Data.Status(condition, code, message)
                        case Absent =>
                            keyword match
                                case "CAPABILITY" => Data.Capability(atoms())
                                case "FLAGS"      =>
                                    space()
                                    Data.Flags(atomList())
                                case "LIST" | "LSUB" => listItem()
                                case "STATUS"        => mailboxStatus()
                                case "SEARCH"        => Data.Search(numbers())
                                case "ESEARCH"       => esearch()
                                case other           => Data.Other(other, remaining())
                    end match
            end match
        end untagged

        private def conditionOf(keyword: String): Maybe[Condition] =
            keyword match
                case "OK"      => Present(Condition.Ok)
                case "NO"      => Present(Condition.No)
                case "BAD"     => Present(Condition.Bad)
                case "BYE"     => Present(Condition.Bye)
                case "PREAUTH" => Present(Condition.PreAuth)
                case _         => Absent

        // `resp-text`: an optional bracketed code, then the human-readable text. The space before the text is optional in practice.
        private def statusText(): (Maybe[Code], String) =
            if peek == ' ' then at += 1
            if peek == '[' then
                at += 1
                val name = new java.lang.StringBuilder
                while peek != ' ' && peek != ']' && peek >= 0 do
                    discard(name.append(peek.toChar))
                    at += 1
                val arguments =
                    if peek == ' ' then
                        at += 1
                        val start  = at
                        var quoted = false
                        while peek >= 0 && (quoted || peek != ']') do
                            if peek == '"' then quoted = !quoted
                            at += 1
                        Present(Utf8.decode(text.slice(start, at)))
                    else Absent
                expect(']')
                if peek == ' ' then at += 1
                (Present(Code(Ascii.toUpper(name.toString), arguments)), rest())
            else (Absent, rest())
            end if
        end statusText

        private def atoms(): Chunk[String] =
            val out = ChunkBuilder.init[String]
            while peek == ' ' do
                at += 1
                if peek >= 0 then discard(out.addOne(atom()))
            out.result()
        end atoms

        private def atomList(): Chunk[String] =
            value() match
                case Value.Items(values) =>
                    values.map {
                        case Value.Atom(a)   => a
                        case Value.Number(n) => n.toString
                        case _               => refuse()
                    }
                case _ => refuse()

        private def numbers(): Chunk[Long] =
            val out = ChunkBuilder.init[Long]
            while peek == ' ' do
                at += 1
                if peek == '(' then discard(value())
                else if peek >= 0 then
                    number(atom()) match
                        case Present(n) => discard(out.addOne(n))
                        case Absent     => refuse()
                end if
            end while
            out.result()
        end numbers

        private def listItem(): Data =
            space()
            val attributes = atomList()
            space()
            val delimiter = value() match
                case Value.Nil                             => Absent
                case Value.Str(octets) if octets.size == 1 => Present((octets(0) & 0xff).toChar)
                case _                                     => refuse()
            space()
            val name = string()
            discard(remaining())
            Data.ListItem(attributes, delimiter, name)
        end listItem

        private def mailboxStatus(): Data =
            space()
            val name = string()
            space()
            val items = value() match
                case Value.Items(values) if values.size % 2 == 0 =>
                    Chunk.from(values.grouped(2).map {
                        case Seq(Value.Atom(key), Value.Number(n)) => Ascii.toUpper(key) -> n
                        case _                                     => refuse()
                    }.toSeq)
                case _ => refuse()
            Data.MailboxStatus(name, items)
        end mailboxStatus

        // RFC 4731: `* ESEARCH [(TAG "t")] [UID] *(SP key SP value)`; only the ALL set is a result.
        private def esearch(): Data =
            val values = remaining()
            val body   = values match
                case Value.Items(_) +: tail => tail
                case all                    => all
            val pairs = body match
                case Value.Atom(uid) +: tail if Ascii.equalsIgnoreCase(uid, "UID") => tail
                case other                                                         => other
            if pairs.size % 2 != 0 then refuse()
            val all = Maybe.fromOption(pairs.grouped(2).collectFirst {
                case Seq(Value.Atom(key), Value.Atom(set)) if Ascii.equalsIgnoreCase(key, "ALL")   => ranges(set)
                case Seq(Value.Atom(key), Value.Number(one)) if Ascii.equalsIgnoreCase(key, "ALL") => Chunk((one, one))
            })
            Data.ESearch(all.getOrElse(Chunk.empty))
        end esearch

        // A sequence set's ranges, each low to high, unexpanded: a range of a few octets can name billions of UIDs.
        private def ranges(set: String): Chunk[(Long, Long)] =
            Chunk.from(set.split(",")).map { range =>
                range.split(":").map(number).toSeq match
                    case Seq(Present(a))             => (a, a)
                    case Seq(Present(a), Present(b)) => (math.min(a, b), math.max(a, b))
                    case _                           => refuse()
            }

        private def fetchItems(): Chunk[(String, Value)] =
            value() match
                case Value.Items(values) if values.size % 2 == 0 =>
                    Chunk.from(values.grouped(2).map {
                        case Seq(Value.Atom(key), v) => Ascii.toUpper(key) -> v
                        case _                       => refuse()
                    }.toSeq)
                case _ => refuse()

        private def remaining(): Chunk[Value] =
            val out = ChunkBuilder.init[Value]
            while peek == ' ' do
                at += 1
                if !atEnd then discard(out.addOne(value()))
            out.result()
        end remaining

        // An astring: an atom read as its octets, a quoted string, or a literal.
        private def string(): Chunk[Byte] =
            value() match
                case Value.Str(octets) => octets
                case Value.Atom(a)     => Chunk.from(Utf8.encode(a).toArray)
                case Value.Number(n)   => Chunk.from(Utf8.encode(n.toString).toArray)
                case _                 => refuse()

        def value(): Value =
            peek match
                case '(' =>
                    at += 1
                    val out = ChunkBuilder.init[Value]
                    if peek != ')' then
                        var last = value()
                        discard(out.addOne(last))
                        // body-type-mpart writes its parts with no space between them, the one place two values touch.
                        while peek == ' ' || (peek == '(' && last.isInstanceOf[Value.Items]) do
                            if peek == ' ' then at += 1
                            last = value()
                            discard(out.addOne(last))
                        end while
                    end if
                    expect(')')
                    Value.Items(out.result())
                case '"' => Value.Str(quoted())
                case '{' => Value.Str(literal())
                case -1  => refuse()
                case _   =>
                    val a = atom()
                    if Ascii.equalsIgnoreCase(a, "NIL") then Value.Nil
                    else number(a).fold(Value.Atom(a))(Value.Number(_))

        private def quoted(): Chunk[Byte] =
            expect('"')
            val out = ChunkBuilder.init[Byte]
            while peek != '"' do
                if peek < 0 then refuse()
                if peek == '\\' then at += 1
                if peek < 0 then refuse()
                discard(out.addOne(text(at)))
                at += 1
            end while
            at += 1
            out.result()
        end quoted

        // `{n}` or `{n+}` ends its text segment; the next segment is the literal and the one after it continues the response.
        private def literal(): Chunk[Byte] =
            expect('{')
            val start = at
            while peek >= '0' && peek <= '9' do at += 1
            val size = number(Utf8.decode(text.slice(start, at))).getOrElse(refuse())
            if peek == '+' then at += 1
            expect('}')
            if at != text.size || segment + 2 >= segments.size then refuse()
            segments(segment + 1) match
                case Segment.Literal(octets) if octets.size == size =>
                    segment += 2
                    at = 0
                    Chunk.from(octets.toArray)
                case _ => refuse()
            end match
        end literal

        // Atom characters up to a delimiter; a `[` opens a section read through its `]` whatever it holds (`BODY[HEADER.FIELDS (A B)]`).
        def atom(): String =
            val start   = at
            var section = false
            while peek >= 0 && (section || (peek != ' ' && peek != '(' && peek != ')' && peek != '"')) do
                if peek == '[' then section = true
                else if peek == ']' then section = false
                at += 1
            end while
            if at == start || section then refuse()
            Utf8.decode(text.slice(start, at))
        end atom

    end Reader

    /** `value` as an atom when every character is an ATOM-CHAR, a quoted string when it is printable ASCII, and a literal of its UTF-8
      * otherwise: non-synchronizing under `LITERAL+` or IMAP4rev2's `LITERAL-` (for which the session passes `literalPlus` only up to 4096
      * octets), synchronizing otherwise.
      */
    def astring(value: String, literalPlus: Boolean): Chunk[Part] =
        if value.nonEmpty && value.forall(isAtomChar) then Chunk(Part.Text(value))
        else if value.forall(c => c >= ' ' && c <= '~') then
            Chunk(Part.Text("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""))
        else Chunk(Part.Literal(Chunk.from(Utf8.encode(value).toArray), synchronizing = !literalPlus))

    // RFC 9051 section 9: ATOM-CHAR is any CHAR except atom-specials: ( ) { SP CTL % * " \ ].
    private def isAtomChar(c: Char): Boolean =
        c > ' ' && c < '\u007f' && "(){%*\"\\]".indexOf(c.toInt) < 0

    /** A mailbox name as its wire form: canonical modified UTF-7 (RFC 3501 section 5.1.3) or the octets a server listed, a literal unless
      * they are printable ASCII; in a session that speaks only IMAP4rev2 (RFC 9051 5.1), a name that is not verbatim as the UTF-8 of its
      * text.
      */
    def mailbox(name: Email.MailboxName, utf8: Boolean, literalPlus: Boolean = false): Chunk[Part] =
        val wire = name.wire
        if utf8 && !name.verbatim then astring(name.value, literalPlus)
        else if wire.forall(c => c >= ' ' && c <= '~') then astring(wire, literalPlus)
        else Chunk(Part.Literal(Chunk.from(wire.getBytes("ISO-8859-1")), synchronizing = !literalPlus))
    end mailbox

    /** The UIDs sorted, without repeats, as ranges (`1:5,9,11`), in as few sets as keep each within `room` characters, so a command
      * carrying one stays within the line length RFC 7162 section 4 has clients keep to.
      */
    def uidSets(uids: Chunk[Long], room: Int): Chunk[String] =
        val sorted = uids.distinct.sorted
        val sets   = ChunkBuilder.init[String]
        val out    = new java.lang.StringBuilder
        var i      = 0
        while i < sorted.size do
            var j = i
            while j + 1 < sorted.size && sorted(j + 1) == sorted(j) + 1 do j += 1
            val range = if j > i then s"${sorted(i)}:${sorted(j)}" else sorted(i).toString
            if out.length > 0 && out.length + 1 + range.length > room then
                discard(sets.addOne(out.toString))
                out.setLength(0)
            if out.length > 0 then discard(out.append(','))
            discard(out.append(range))
            i = j + 1
        end while
        if out.length > 0 then discard(sets.addOne(out.toString))
        sets.result()
    end uidSets

    /** An `INTERNALDATE` (`dd-Mon-yyyy hh:mm:ss +zzzz`, the day possibly padded with a space) as an instant. */
    def internalDate(text: String): Maybe[Instant] =
        val trimmed = text.trim
        val parts   = trimmed.split(" ")
        if parts.length != 3 then Absent
        else
            val date = parts(0).split("-")
            val time = parts(1).split(":")
            val zone = parts(2)
            if date.length != 3 || time.length != 3 || zone.length != 5 || (zone(0) != '+' && zone(0) != '-') then Absent
            else
                for
                    day    <- Ascii.parseDigits(date(0))
                    month  <- DateCodec.monthOf(date(1))
                    year   <- Ascii.parseDigits(date(2))
                    hour   <- Ascii.parseDigits(time(0))
                    minute <- Ascii.parseDigits(time(1))
                    second <- Ascii.parseDigits(time(2))
                    zh     <- Ascii.parseDigits(zone.substring(1, 3))
                    zm     <- Ascii.parseDigits(zone.substring(3, 5))
                    if day >= 1 && day <= DateCodec.daysIn(year, month) && hour < 24 && minute < 60 && second < 61 && zm < 60
                yield
                    val offset = (zh * 3600 + zm * 60) * (if zone(0) == '-' then -1 else 1)
                    DateCodec.instantOf(DateCodec.daysFromCivil(year, month, day.toInt) * 86400 + hour * 3600 + minute * 60 + second -
                        offset)
            end if
        end if
    end internalDate

end ImapCodec
