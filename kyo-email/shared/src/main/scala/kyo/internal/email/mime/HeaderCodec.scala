package kyo.internal.email.mime

import kyo.*
import kyo.charset.Charset
import kyo.internal.Ascii
import kyo.internal.charset.Utf8
import kyo.internal.email.charset.Charsets
import kyo.internal.mime.Grammar.quoted
import kyo.internal.mime.Grammar.quotedLength
import kyo.internal.mime.Grammar.quotedString
import kyo.internal.mime.Grammar.skipCfws
import kyo.internal.mime.Grammar.tokenEnd
import kyo.mime.Parameters

/** Header sections, encoded words (RFC 2047), and the structured values of `Content-Type`, `Content-Disposition` and
  * `Content-Transfer-Encoding` with their parameters (RFC 2045, RFC 2183, RFC 2231), reading.
  *
  * A line ends at LF, and a CR immediately before that LF belongs to the line end, so CRLF and bare LF are both line ends; a CR anywhere
  * else is data. A header line is `field-name *WSP ":"` (RFC 5322 sections 3.6.8 and 4.5), and a line that starts with SP or HTAB continues
  * the field before it. A section ends at its first empty line, which is consumed, or at the first line that is neither a header line nor
  * a continuation, which is left for the body.
  *
  * A field's value is unfolded (RFC 5322 section 2.2.3: each line end is removed, the white space after it kept) and turned into text one
  * field at a time: as UTF-8 when its octets are well-formed UTF-8 (RFC 6532 section 3), and as windows-1252 otherwise, which gives every
  * octet a character, so nothing is lost. A bare CR becomes one SP, and SP and HTAB at either end are removed. Reading never fails.
  *
  * Encoded words are recognized anywhere in a token of a context, with no length cap, and decoded where they stand: text joined to a word
  * is kept as written, with no space inserted, as CPython's `email.policy.default` parser reads it (RFC 2047 sections 5 (1) and 6.1 (1)
  * recognize words only between white space; mail with a tag prepended to an encoded subject is common). Adjacent words, separated by
  * white space only, are joined without it; a run of adjacent words whose charsets resolve
  * to the same [[kyo.charset.Charset]] is decoded as one octet sequence, so a character split across two words is whole again, except in the four
  * stateful encodings, whose words each start from the initial state. A word whose charset is unknown, whose encoding is neither `B` nor
  * `Q`, or whose encoded text does not decode stays as its text (RFC 2047 section 6.3).
  */
private[kyo] object HeaderCodec:

    /** How a field's octets became its text: no octet from 0x80, well-formed UTF-8, or windows-1252. Both decodings of octets from 0x80
      * are injective, so a structured reader recovers a raw octet from the text by encoding it back.
      */
    enum ValueText derives CanEqual:
        case UsAscii, Utf8, Windows1252

    /** A field as read: its name as written, and its value unfolded and decoded as text, with no encoded word decoded. */
    final case class Field(name: String, value: String, text: ValueText) derives CanEqual

    /** The fields of a header section in order, and the offset where the body starts. */
    final case class Section(fields: Chunk[Field], bodyStart: Int) derives CanEqual

    /** The header section of `input` starting at `start`. */
    def readSection(input: Span[Byte], start: Int): Section = readSection(input, start, (_, _) => false)

    /** The header section of `input` starting at `start`, ending also before the first line for which `endsBefore(lineStart, contentEnd)`
      * is true. It is asked at every line before the line is read, `contentEnd` being where the line's end starts.
      */
    def readSection(input: Span[Byte], start: Int, endsBefore: (Int, Int) => Boolean): Section =
        val fields = ChunkBuilder.init[Field]
        @scala.annotation.tailrec
        def loop(lineStart: Int): Int =
            if lineStart >= input.size then input.size
            else
                val lineEnd    = lineEndAt(input, lineStart)
                val contentEnd = contentEndOf(input, lineStart, lineEnd)
                if endsBefore(lineStart, contentEnd) then lineStart
                else if contentEnd == lineStart then nextLine(input, lineEnd)
                else
                    val nameEnd = fieldNameEnd(input, lineStart, contentEnd)
                    val colon   = colonAt(input, nameEnd, contentEnd)
                    if nameEnd == lineStart || colon < 0 then lineStart
                    else
                        val extent = fieldExtent(input, colon + 1, contentEnd, lineEnd, endsBefore)
                        discard(fields.addOne(Field(asciiText(input, lineStart, nameEnd), value(input, colon + 1, extent), extent.text)))
                        loop(extent.next)
                    end if
                end if
            end if
        end loop
        val bodyStart = loop(start)
        Section(fields.result(), bodyStart)
    end readSection

    final private case class Extent(contentEnd: Int, next: Int, text: ValueText)

    // The field's last line, where the line after it starts, and how its value octets, line ends excluded, read. Each line is checked on
    // its own: the next line of a field starts with SP or HTAB, which ends any pending UTF-8 sequence as malformed, so a sequence cut by a
    // fold is malformed either way.
    private def fieldExtent(input: Span[Byte], valueStart: Int, firstContentEnd: Int, firstLineEnd: Int, endsBefore: (Int, Int) => Boolean)
        : Extent =
        var text       = ValueText.UsAscii
        var contentEnd = firstContentEnd
        var lineEnd    = firstLineEnd
        var from       = valueStart
        var continues  = true
        while continues do
            val line = octetText(input, from, contentEnd)
            if line.ordinal > text.ordinal then text = line
            val next = nextLine(input, lineEnd)
            if next < input.size && isWhiteSpace(input(next)) then
                val nextEnd        = lineEndAt(input, next)
                val nextContentEnd = contentEndOf(input, next, nextEnd)
                if endsBefore(next, nextContentEnd) then continues = false
                else
                    from = next
                    contentEnd = nextContentEnd
                    lineEnd = nextEnd
                end if
            else continues = false
            end if
        end while
        Extent(contentEnd, nextLine(input, lineEnd), text)
    end fieldExtent

    // How the octets `[from, until)` read: no octet from 0x80, well-formed UTF-8, or neither. Validation follows the WHATWG UTF-8
    // decoder's byte ranges, so well-formed means that decoder emits no U+FFFD.
    private[mime] def octetText(input: Span[Byte], from: Int, until: Int): ValueText =
        var high   = false
        var valid  = true
        var needed = 0
        var lower  = 0x80
        var upper  = 0xbf
        var i      = from
        while i < until do
            val b = input(i) & 0xff
            if needed == 0 then
                if b >= 0x80 then
                    high = true
                    if b >= 0xc2 && b <= 0xdf then needed = 1
                    else if b >= 0xe0 && b <= 0xef then
                        if b == 0xe0 then lower = 0xa0
                        if b == 0xed then upper = 0x9f
                        needed = 2
                    else if b >= 0xf0 && b <= 0xf4 then
                        if b == 0xf0 then lower = 0x90
                        if b == 0xf4 then upper = 0x8f
                        needed = 3
                    else valid = false
                    end if
                end if
            else if b < lower || b > upper then
                valid = false
                needed = 0
                lower = 0x80
                upper = 0xbf
                // The unexpected octet starts over, as in the WHATWG decoder.
                i -= 1
            else
                lower = 0x80
                upper = 0xbf
                needed -= 1
            end if
            i += 1
        end while
        if !high then ValueText.UsAscii else if valid && needed == 0 then ValueText.Utf8 else ValueText.Windows1252
    end octetText

    // The unfolded value from `valueStart` to the end of the field's last line: line ends removed, a bare CR as SP, SP and HTAB at either
    // end removed. One builder, sized by the octets, which are never fewer than the characters.
    private def value(input: Span[Byte], valueStart: Int, extent: Extent): String =
        val out     = new java.lang.StringBuilder(math.max(0, extent.contentEnd - valueStart))
        var kept    = 0
        var started = false
        var i       = valueStart
        while i < extent.contentEnd do
            val b = input(i) & 0xff
            if b == '\n' then i += 1
            else if b == '\r' && i + 1 < input.size && input(i + 1) == '\n' then i += 2
            else if b == ' ' || b == '\t' || b == '\r' then
                if started then discard(out.append(if b == '\t' then '\t' else ' '))
                i += 1
            else
                started = true
                if b < 0x80 then
                    discard(out.append(b.toChar))
                    i += 1
                else if extent.text == ValueText.Windows1252 then
                    discard(out.append(windows1252High.charAt(b - 0x80)))
                    i += 1
                else i = appendUtf8(input, i, b, out)
                end if
                kept = out.length
            end if
        end while
        out.setLength(kept)
        out.toString
    end value

    // Appends the code point whose lead octet `b` is at `i` of well-formed UTF-8, and answers the offset after it.
    private def appendUtf8(input: Span[Byte], i: Int, b: Int, out: java.lang.StringBuilder): Int =
        val length    = if b < 0xe0 then 2 else if b < 0xf0 then 3 else 4
        var codePoint = b & (0xff >> (length + 1))
        var k         = 1
        while k < length do
            codePoint = (codePoint << 6) | (input(i + k) & 0x3f)
            k += 1
        discard(out.appendCodePoint(codePoint))
        i + length
    end appendUtf8

    private[mime] def lineEndAt(input: Span[Byte], from: Int): Int =
        var i = from
        while i < input.size && input(i) != '\n' do i += 1
        i
    end lineEndAt

    private[mime] def contentEndOf(input: Span[Byte], lineStart: Int, lineEnd: Int): Int =
        if lineEnd < input.size && lineEnd > lineStart && input(lineEnd - 1) == '\r' then lineEnd - 1 else lineEnd

    private[mime] def nextLine(input: Span[Byte], lineEnd: Int): Int = if lineEnd < input.size then lineEnd + 1 else input.size

    // RFC 5322 3.6.8: ftext = %d33-57 / %d59-126.
    private def fieldNameEnd(input: Span[Byte], lineStart: Int, contentEnd: Int): Int =
        var i = lineStart
        while i < contentEnd && isFieldNameOctet(input(i) & 0xff) do i += 1
        i
    end fieldNameEnd

    private def isFieldNameOctet(b: Int): Boolean = b >= 33 && b <= 126 && b != ':'

    private def colonAt(input: Span[Byte], nameEnd: Int, contentEnd: Int): Int =
        var i = nameEnd
        while i < contentEnd && isWhiteSpace(input(i)) do i += 1
        if i < contentEnd && input(i) == ':' then i else -1
    end colonAt

    private def asciiText(input: Span[Byte], from: Int, until: Int): String =
        val chars = new Array[Char](until - from)
        var i     = 0
        while i < chars.length do
            chars(i) = (input(from + i) & 0xff).toChar
            i += 1
        new String(chars)
    end asciiText

    private def isWhiteSpace(b: Byte): Boolean = b == ' ' || b == '\t'

    private def isWhiteSpace(c: Char): Boolean = c == ' ' || c == '\t'

    /** A `Content-Disposition` value: its type, and its parameters after RFC 2231 and encoded words, names lowercased. */
    final case class Disposition(kind: DispositionKind, parameters: Chunk[(String, String)]) derives CanEqual

    /** A disposition type (RFC 2183 section 2): `inline`, `attachment`, or any other token, compared without case and held lowercased. */
    enum DispositionKind derives CanEqual:
        case Inline, Attachment
        case Other(name: String)

        /** The type as written in a field. */
        def label: String =
            this match
                case Inline      => "inline"
                case Attachment  => "attachment"
                case Other(name) => name
    end DispositionKind

    private def dispositionKind(token: String): DispositionKind =
        Ascii.toLower(token) match
            case "inline"     => DispositionKind.Inline
            case "attachment" => DispositionKind.Attachment
            case other        => DispositionKind.Other(other)

    /** The media type of a `Content-Type` value, or `Absent` when the value is not a type, a subtype and parameters (RFC 2045 section 5.1),
      * so the caller applies its default (RFC 2045 section 5.2). White space at the end of `boundary` is removed (RFC 2046 section 5.1.1).
      */
    def contentType(field: Field)(using Frame): Maybe[Email.MediaType] =
        Email.MediaType.parse(field.value, mailValue, unencodedOctets(field.text)).toMaybe

    /** The disposition type and parameters of a `Content-Disposition` value (RFC 2183 section 2), or `Absent` when it has no type. */
    def contentDisposition(field: Field)(using Frame): Maybe[Disposition] =
        kyo.mime.Disposition.parse(field.value, mailValue, unencodedOctets(field.text)).toMaybe.map { parsed =>
            Disposition(dispositionKind(parsed.kind), parsed.parameters.map(p => (p.name, p.value)))
        }

    // A parameter value as mail reads it: a plain value for encoded words, and an encoded value in the charset its segment 0 names, or as
    // UTF-8 when well-formed and windows-1252 otherwise when that names none the module knows.
    private def mailValue(value: Parameters.Value): String =
        value match
            case Parameters.Value.Text(plain)                 => decodeUnstructured(plain)
            case Parameters.Value.Encoded(charset, _, octets) =>
                charset.flatMap(Charsets.resolve).getOrElse(
                    if octetText(octets, 0, octets.size) == ValueText.Windows1252 then Charset.Windows1252 else Charset.Utf8
                ).decode(octets)

    // The octets an unencoded segment merged into an encoded value was read from, through the field's `ValueText`.
    private def unencodedOctets(valueText: ValueText): String => Span[Byte] =
        if valueText == ValueText.Utf8 then Utf8.encode
        else
            text =>
                // Unsafe: the array is fresh and fully written; only the span escapes.
                Span.fromUnsafe(Array.tabulate(text.length) { i =>
                    val c = text.charAt(i)
                    (if c < 0x80 then c.toInt else windows1252Octet(c)).toByte
                })

    /** The encoding a `Content-Transfer-Encoding` value names, CFWS removed, or `Absent` when it names none the module knows. */
    def transferEncoding(field: Field): Maybe[TransferEncoding.Kind] =
        val value = field.value
        val start = skipCfws(value, 0)
        if start >= value.length then Absent
        else if value.charAt(start) == '"' then
            val (label, after) = quotedString(value, start)
            if skipCfws(value, after) == value.length then TransferEncoding.resolve(label) else Absent
        else
            val end = tokenEnd(value, start)
            if end > start && skipCfws(value, end) == value.length then TransferEncoding.resolve(value.substring(start, end)) else Absent
        end if
    end transferEncoding

    // The octet windows-1252 reads as `c`. A field read as windows-1252 holds only characters its 256 octets decode to.
    private[mime] def windows1252Octet(c: Char): Int = 0x80 + windows1252High.indexOf(c.toInt)

    // The characters windows-1252 reads for 0x80 to 0xFF, one per octet.
    private lazy val windows1252High: String = Charset.Windows1252.decode(Span.from(Array.tabulate(128)(i => (0x80 + i).toByte)))

    /** Why a header value cannot be written. */
    enum WriteFailure derives CanEqual:
        /** A unit that makes its line pass 998 octets even on a line of its own (RFC 5322 section 2.1.1). */
        case LineTooLong

        /** A unit holding CR or LF, which would end the header line. */
        case LineBreakInValue

        /** A parameter name holding `*`, which RFC 2231 section 7 excludes from a written name, or that is not a token. */
        case UnwritableParameterName(name: String)

        /** An address that is not an RFC 5322 `addr-spec`, which the reader would not take back as the same mailbox. */
        case InvalidAddress(address: String)

        /** An instant before year 0000, which RFC 5322's four-digit year cannot write. */
        case DateOutOfRange
    end WriteFailure

    /** Where an encoded word stands, which decides the characters Q may leave as they are (RFC 2047 sections 4.2 and 5). */
    enum WordContext derives CanEqual:
        case Text, Phrase

    /** The field `name: value`, CRLF-terminated, the value given as units: the first unit, then units that each start with the white
      * space before them. A unit is never broken; a new line starts before a unit that would take its line past 78 octets, before the
      * first unit too (RFC 5322 sections 2.1.1 and 3.2.2). Every header line is written here, so a unit holding CR or LF is refused.
      */
    def fold(name: String, units: Chunk[String]): Result[WriteFailure, String] =
        if units.exists(u => u.indexOf('\r') >= 0 || u.indexOf('\n') >= 0) then Result.fail(WriteFailure.LineBreakInValue)
        else
            val breaks = new Array[Boolean](units.size)
            @scala.annotation.tailrec
            def plan(i: Int, line: Int): Boolean =
                if line > 998 then false
                else if i == units.size then true
                else
                    val unit      = units(i)
                    val piece     = utf8Length(unit) + (if i == 0 then 1 else 0)
                    val breakable = i == 0 || (unit.nonEmpty && isWhiteSpace(unit.charAt(0)))
                    if line + piece > 78 && breakable then
                        breaks(i) = true
                        plan(i + 1, piece)
                    else plan(i + 1, line + piece)
                    end if
            if !plan(0, utf8Length(name) + 1) then Result.fail(WriteFailure.LineTooLong)
            else
                val size = name.length + 3 + units.foldLeft(if units.isEmpty then 0 else 1)(_ + _.length) + 2 * breaks.count(identity)
                val out  = new java.lang.StringBuilder(size)
                discard(out.append(name).append(':'))
                units.zipWithIndex.foreach { (unit, i) =>
                    if breaks(i) then discard(out.append("\r\n"))
                    if i == 0 then discard(out.append(' '))
                    discard(out.append(unit))
                }
                Result.succeed(out.append("\r\n").toString)
            end if
        end if
    end fold

    /** An extra header's value as units, split before each run of white space, so it reads back exactly as written. */
    def rawUnits(value: String): Chunk[String] =
        val units = ChunkBuilder.init[String]
        @scala.annotation.tailrec
        def loop(start: Int, at: Int): Unit =
            if at >= value.length then
                if value.nonEmpty then discard(units.addOne(value.substring(start)))
            else if isWhiteSpace(value.charAt(at)) && !isWhiteSpace(value.charAt(at - 1)) then
                discard(units.addOne(value.substring(start, at)))
                loop(at, at + 1)
            else loop(start, at + 1)
        loop(0, 1)
        units.result()
    end rawUnits

    /** Unstructured text (`Subject`): as it is when it reads back identically and every unit fits a line, otherwise encoded words. */
    def unstructuredUnits(text: String): Chunk[String] =
        if text.isEmpty then Chunk.empty
        else
            val raw  = rawUnits(text)
            val asIs = text.forall(c => c >= ' ' && c <= '~') && text.charAt(0) != ' ' && text.charAt(text.length - 1) != ' ' &&
                !text.contains("=?") && raw.forall(u => u.length <= 77 && u.length - u.lastIndexOf(' ') - 1 <= 76)
            if asIs then raw else encodedWords(text, WordContext.Text)
        end if
    end unstructuredUnits

    /** A display name: atoms, a quoted string, or encoded words in the phrase context (RFC 2047 section 5 (3)). */
    def phraseUnits(name: String): Chunk[String] =
        if name.isEmpty then Chunk.empty
        else if name.contains("=?") then encodedWords(name, WordContext.Phrase)
        else
            val words = name.split(" ", -1)
            if words.forall(w => w.nonEmpty && w.length <= 76 && w.forall(isAtext)) then rawUnits(name)
            else if name.forall(c => c >= ' ' && c <= '~') && quotedLength(name) <= 76 then Chunk(quoted(name))
            else encodedWords(name, WordContext.Phrase)
        end if
    end phraseUnits

    /** `text` as encoded words over its UTF-8 octets: cut at code point boundaries into the longest pieces whose word is at most 75
      * characters, each in the shorter of B and Q (Q on a tie), every word after the first starting with SP (RFC 2047 sections 2, 4, 5).
      */
    def encodedWords(text: String, context: WordContext): Chunk[String] =
        val octets = Utf8.encode(text)
        val words  = ChunkBuilder.init[String]
        // The encoded text of a word is at most 75 - "=?utf-8?Q?".length - "?=".length characters.
        val room = 63
        @scala.annotation.tailrec
        def loop(start: Int, at: Int, qLength: Int, first: Boolean): Unit =
            if at >= octets.size then
                if at > start then discard(words.addOne(word(octets, start, at, qLength, context, !first)))
            else
                val size  = utf8SequenceLength(octets(at))
                val end   = math.min(at + size, octets.size)
                val q     = qLength + qCost(octets, at, end, context)
                val bytes = end - start
                if at > start && math.min(q, (bytes + 2) / 3 * 4) > room then
                    discard(words.addOne(word(octets, start, at, qLength, context, !first)))
                    loop(at, at, 0, false)
                else loop(start, end, q, first)
                end if
        loop(0, 0, 0, true)
        words.result()
    end encodedWords

    // One word over `octets(from until until)`, whose Q text is `qLength` characters.
    private def word(octets: Span[Byte], from: Int, until: Int, qLength: Int, context: WordContext, spaceBefore: Boolean): String =
        val bLength = (until - from + 2) / 3 * 4
        val lead    = if spaceBefore then " " else ""
        if qLength <= bLength then
            val out = new java.lang.StringBuilder(lead.length + 12 + qLength)
            discard(out.append(lead).append("=?utf-8?Q?"))
            var i = from
            while i < until do
                val b = octets(i) & 0xff
                if b == ' ' then discard(out.append('_'))
                else if isQLiteral(b, context) then discard(out.append(b.toChar))
                else discard(out.append('=').append(HexDigits.charAt(b >> 4)).append(HexDigits.charAt(b & 0xf)))
                i += 1
            end while
            out.append("?=").toString
        else lead + "=?utf-8?B?" + Base64.encode(octets.slice(from, until)) + "?="
        end if
    end word

    private def qCost(octets: Span[Byte], from: Int, until: Int, context: WordContext): Int =
        var cost = 0
        var i    = from
        while i < until do
            val b = octets(i) & 0xff
            cost += (if b == ' ' || isQLiteral(b, context) then 1 else 3)
            i += 1
        end while
        cost
    end qCost

    // RFC 2047 section 4.2 (3) in text; section 5 (3) in a phrase: letters, digits, "!", "*", "+", "-", "/".
    private def isQLiteral(b: Int, context: WordContext): Boolean =
        context match
            case WordContext.Text   => b > ' ' && b < 0x7f && b != '=' && b != '?' && b != '_'
            case WordContext.Phrase => Ascii.isAlphaNumeric(b.toChar) || b == '!' || b == '*' || b == '+' || b == '-' || b == '/'

    private def utf8SequenceLength(lead: Byte): Int =
        val b = lead & 0xff
        if b < 0xc0 then 1 else if b < 0xe0 then 2 else if b < 0xf0 then 3 else 4

    private val HexDigits = "0123456789ABCDEF"

    // RFC 5322 section 3.2.3: atext.
    private[mime] def isAtext(c: Char): Boolean = Ascii.isAlphaNumeric(c) || "!#$%&'*+-/=?^_`{|}~".indexOf(c.toInt) >= 0

    /** A `Content-Type` value as units: the type and subtype, then each parameter's segments. */
    def contentTypeUnits(mediaType: Email.MediaType)(using Frame): Result[WriteFailure, Chunk[String]] =
        withParameters(mediaType.baseType, mediaType.parameters.map(p => (p.name, p.value)))

    /** A `Content-Disposition` value as units: the disposition type, then each parameter's segments. Only the two kinds a rendered part
      * has are writable, so no disposition name needs checking here.
      */
    def dispositionUnits(
        kind: DispositionKind.Inline.type | DispositionKind.Attachment.type,
        parameters: Chunk[(String, String)]
    )(using Frame): Result[WriteFailure, Chunk[String]] =
        withParameters(kind.label, parameters)

    // The head, then each parameter's RFC 2231 segments (kyo-mime's `Style.Mime`, 76 octets each) as units starting with SP, `;` ending
    // the unit before it, so `fold` puts each on its own line when the field passes 78.
    private def withParameters(head: String, parameters: Chunk[(String, String)])(using Frame): Result[WriteFailure, Chunk[String]] =
        parameters.foldLeft(Result.succeed(Chunk.empty[String]): Result[WriteFailure, Chunk[String]]) { (done, parameter) =>
            val (name, value) = parameter
            done.flatMap { segments =>
                Parameters.write(name, value, Parameters.Style.Mime) match
                    case Result.Success(units) => Result.succeed(segments ++ units)
                    case Result.Failure(_)     => Result.fail(WriteFailure.UnwritableParameterName(name))
                    case Result.Panic(error)   => Result.panic(error)
            }
        }.map { segments =>
            if segments.isEmpty then Chunk(head)
            else Chunk(head + ";") ++ segments.dropRight(1).map(s => s" $s;") ++ segments.lastMaybe.map(" " + _).toChunk
        }

    // The UTF-8 length of `text`: a surrogate pair is 4 octets, 2 for each half.
    private def utf8Length(text: String): Int =
        var size = 0
        var i    = 0
        while i < text.length do
            val c = text.charAt(i)
            size += (if c < 0x80 then 1 else if c < 0x800 || Character.isSurrogate(c) then 2 else 3)
            i += 1
        end while
        size
    end utf8Length

    /** An unstructured field value (RFC 5322 section 3.2.5, `Subject`) with its encoded words decoded: tokens are separated by SP and
      * HTAB, and every other character is part of a token (RFC 2047 section 6.1 (1)).
      */
    def decodeUnstructured(value: CharSequence): String =
        val words = new EncodedWords(value.length)
        var i     = 0
        while i < value.length do
            val space = isWhiteSpace(value.charAt(i))
            var j     = i + 1
            while j < value.length && isWhiteSpace(value.charAt(j)) == space do j += 1
            if space then words.space(value, i, j) else words.token(value, i, j)
            i = j
        end while
        words.result()
    end decodeUnstructured

    /** The encoded-word decoder every context shares. A context splits its text into white space, plain text and tokens that may be encoded
      * words, and hands them over in order; the decoder joins adjacent words and decodes each run. It lives for one value.
      */
    final private[mime] class EncodedWords(capacity: Int):
        private val out          = new java.lang.StringBuilder(capacity)
        private val run          = ChunkBuilder.init[Span[Byte]]
        private var runOctets    = 0
        private var runCharset   = Maybe.empty[Charset]
        private var afterWord    = false
        private var pending      = Maybe.empty[CharSequence]
        private var pendingFrom  = 0
        private var pendingUntil = 0

        /** The whole run of white space between two items, in one call. Between two decoded words it is dropped; anywhere else it is kept.
          */
        def space(text: CharSequence, from: Int, until: Int): Unit =
            if afterWord then
                pending = Present(text)
                pendingFrom = from
                pendingUntil = until
            else discard(out.append(text, from, until))

        /** Text that is never an encoded word. */
        def text(text: CharSequence, from: Int, until: Int): Unit =
            flushRun()
            flushPending()
            afterWord = false
            discard(out.append(text, from, until))
        end text

        /** A token: each encoded word in it decoded where it stands, and the text around the words kept as written. */
        def token(text: CharSequence, from: Int, until: Int): Unit =
            var at = from
            recognize(text, from, until).foreach { w =>
                if w.start > at then this.text(text, at, w.start)
                decodeWord(text, w) match
                    case Present((charset, octets)) => word(charset, octets)
                    case Absent                     => this.text(text, w.start, w.end)
                at = w.end
            }
            if until > at then this.text(text, at, until)
        end token

        /** The decoded text; the decoder is not used afterwards. */
        def result(): String =
            flushRun()
            flushPending()
            out.toString
        end result

        private def word(charset: Charset, octets: Span[Byte]): Unit =
            pending = Absent
            val joins = afterWord && runCharset.contains(charset) && !isStateful(charset)
            if !joins then flushRun()
            discard(run.addOne(octets))
            runOctets += octets.size
            runCharset = Present(charset)
            afterWord = true
        end word

        private def flushRun(): Unit =
            runCharset.foreach { charset =>
                val parts  = run.result()
                val octets =
                    if parts.size == 1 then parts(0)
                    else
                        val joined = new Array[Byte](runOctets)
                        var at     = 0
                        parts.foreach { part =>
                            discard(part.copyToArray(joined, at))
                            at += part.size
                        }
                        // Unsafe: `joined` is local and fully written; only the span escapes.
                        Span.fromUnsafe(joined)
                discard(out.append(charset.decode(octets)))
            }
            runOctets = 0
            runCharset = Absent
        end flushRun

        private def flushPending(): Unit =
            pending.foreach(text => discard(out.append(text, pendingFrom, pendingUntil)))
            pending = Absent
        end flushPending

    end EncodedWords

    /** One encoded word's position in its token: the whole word `[start, end)`, its charset, its encoding letters and its encoded text. */
    final private[mime] case class RawWord(start: Int, charsetEnd: Int, encodingEnd: Int, end: Int) derives CanEqual:
        def charsetStart: Int  = start + 2
        def encodingStart: Int = charsetEnd + 1
        def textStart: Int     = encodingEnd + 1
        def textEnd: Int       = end - 2
    end RawWord

    /** The encoded words in `[from, until)`, in order; the characters between them are text. */
    private[mime] def recognize(text: CharSequence, from: Int, until: Int): Chunk[RawWord] =
        val words = ChunkBuilder.init[RawWord]
        // Adds the word starting at `start` if there is one, and answers where the search for the next `=?` resumes. A failed attempt
        // resumes at the character before the one that stopped it: a charset or encoding holds neither `=` nor `?` and encoded text
        // holds no `?`, so no `=?` starts earlier inside what the attempt read, and each character is read a bounded number of times.
        def attempt(start: Int): Int =
            val charsetEnd = componentEnd(text, start + 2, until)
            if !closesComponent(text, start + 2, charsetEnd, until) then math.max(start + 2, charsetEnd - 1)
            else
                val encodingEnd = componentEnd(text, charsetEnd + 1, until)
                if !closesComponent(text, charsetEnd + 1, encodingEnd, until) then math.max(start + 2, encodingEnd - 1)
                else
                    var i = encodingEnd + 1
                    while i < until && isEncodedTextChar(text.charAt(i)) do i += 1
                    if i + 1 < until && text.charAt(i) == '?' && text.charAt(i + 1) == '=' then
                        discard(words.addOne(RawWord(start, charsetEnd, encodingEnd, i + 2)))
                        i + 2
                    else math.max(start + 2, i - 1)
                    end if
                end if
            end if
        end attempt
        var at = from
        while at + 1 < until do
            if text.charAt(at) == '=' && text.charAt(at + 1) == '?' then at = attempt(at)
            else at += 1
        words.result()
    end recognize

    // Where the characters a charset or encoding holds, starting at `from`, stop.
    private def componentEnd(text: CharSequence, from: Int, until: Int): Int =
        var i = from
        while i < until && isComponentChar(text.charAt(i)) do i += 1
        i
    end componentEnd

    private def closesComponent(text: CharSequence, from: Int, end: Int, until: Int): Boolean =
        end > from && end < until && text.charAt(end) == '?'

    private def isComponentChar(c: Char): Boolean = c >= '!' && c <= '~' && c != '?' && c != '='

    private def isEncodedTextChar(c: Char): Boolean = c >= '!' && c <= '~' && c != '?'

    /** The charset and the octets of one word, or `Absent` when the word stays as its text. */
    private[mime] def decodeWord(text: CharSequence, word: RawWord): Maybe[(Charset, Span[Byte])] =
        Charsets.resolve(text.subSequence(word.charsetStart, word.charsetEnd).toString).flatMap { charset =>
            val octets =
                if word.encodingEnd - word.encodingStart != 1 then Absent
                else
                    text.charAt(word.encodingStart) match
                        case 'B' | 'b' => decodeB(text, word.textStart, word.textEnd)
                        case 'Q' | 'q' => decodeQ(text, word.textStart, word.textEnd)
                        case _         => Absent
            octets.map(charset -> _)
        }

    // RFC 2045 base64, as TransferEncoding reads it: every character in the alphabet or `=`, and no segment with a dangling character.
    private def decodeB(text: CharSequence, from: Int, until: Int): Maybe[Span[Byte]] =
        val chars = new Array[Byte](until - from)
        @scala.annotation.tailrec
        def copy(i: Int): Boolean =
            if i == chars.length then true
            else
                val c = text.charAt(from + i)
                if isBase64Char(c) then
                    chars(i) = c.toByte
                    copy(i + 1)
                else false
                end if
        if !copy(0) then Absent
        else
            // Unsafe: `chars` is local and fully written; the span is only read by the decoder.
            // A refused piece leaves the word as its text, as any word whose encoded text does not decode (RFC 2047 section 6.3).
            TransferEncoding.decodeBase64Pieces(Span.fromUnsafe(chars), 0, chars.length).toMaybe.filter(!_.truncated).map(_.content)
        end if
    end decodeB

    private def isBase64Char(c: Char): Boolean =
        (c >= 'A' && c <= 'Z') ||
            (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '='

    // RFC 2047 4.2: `=XX` in either case, `_` for 0x20, any other character as its octet. A `=` not followed by two hex digits is not Q.
    private def decodeQ(text: CharSequence, from: Int, until: Int): Maybe[Span[Byte]] =
        @scala.annotation.tailrec
        def count(i: Int, size: Int): Int =
            if i >= until then size
            else if text.charAt(i) != '=' then count(i + 1, size + 1)
            else if i + 2 < until && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2)) then count(i + 3, size + 1)
            else -1
        val size = count(from, 0)
        if size < 0 then Absent
        else
            val out = new Array[Byte](size)
            @scala.annotation.tailrec
            def fill(i: Int, o: Int): Unit =
                if i < until then
                    val c = text.charAt(i)
                    if c == '=' then
                        out(o) = ((hexValue(text.charAt(i + 1)) << 4) | hexValue(text.charAt(i + 2))).toByte
                        fill(i + 3, o + 1)
                    else
                        out(o) = (if c == '_' then ' ' else c).toByte
                        fill(i + 1, o + 1)
                    end if
            fill(from, 0)
            // Unsafe: `out` is local and fully written; only the span escapes.
            Present(Span.fromUnsafe(out))
        end if
    end decodeQ

    private def isHex(c: Char): Boolean = Ascii.isHexDigit(c)

    private def hexValue(c: Char): Int =
        if c >= '0' && c <= '9' then c - '0' else if c >= 'a' && c <= 'f' then c - 'a' + 10 else c - 'A' + 10

    // ISO-2022-JP, ISO-2022-KR, HZ-GB-2312 and UTF-7 carry a mode from one octet to the next; each of their words starts from the initial
    // mode.
    private def isStateful(charset: Charset): Boolean =
        charset == Charset.Iso2022Jp || charset == Charset.Iso2022Kr || charset == Charset.HzGb2312 || charset == Charset.Utf7

end HeaderCodec
