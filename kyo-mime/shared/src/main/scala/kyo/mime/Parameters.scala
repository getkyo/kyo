package kyo.mime

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.Ascii
import kyo.internal.mime.Grammar
import kyo.internal.mime.Grammar.*
import kyo.internal.mime.Utf8

/** The `name=value` parameters of a MIME or HTTP header value (RFC 2045 section 5.1, RFC 2231, RFC 8187), read and written on their
  * own, for a header that is neither a `Content-Type` nor a `Content-Disposition`, or for a caller that needs the RFC 2231 encoded value
  * before it is decoded.
  *
  * Reading is total. Parameters are read as written, with CFWS between every token (comments nest), a quoted value with its quoted pairs
  * resolved, an unquoted value running to the next `;`, and a parameter written with no `;` before it still read. They are then merged by
  * RFC 2231 section 7: the segments of `name*0`, `name*1`, ... are joined in index order; `name*` or a `name*N*` segment marks an encoded
  * value, whose charset and language come from segment 0's `charset'language'` prefix and whose `%XX` escapes are octets. An encoded
  * value is returned as [[Parameters.Value.Encoded]], its octets undecoded: the charset a caller accepts is the caller's policy (RFC 8187
  * allows UTF-8 only; mail allows any charset), and [[Parameters.decodeUtf8]] is the HTTP one. The same policy function sees the plain
  * values, for a protocol that reads a further encoding inside them. Names are lowercased, and a name written
  * twice keeps its first value; the continuation set wins over `name*`, which wins over the plain form.
  *
  * Writing takes a [[Parameters.Style]]: `Mime` follows RFC 2231 with 76-octet segments and quoted continuations, for a header that will
  * be folded; `Http` follows RFC 8187, one `name*=UTF-8''...` unit with no continuation and no length cap, for a header that will not.
  * In both, a value that is a token is written bare, a printable one as a quoted string, and any other (non-ASCII, a control, CR or LF)
  * percent-encoded, so nothing written here can end a header line. The one value that cannot be written is a name holding `*`.
  */
object Parameters:

    /** A parameter's value as read: plain text, or an RFC 2231 encoded value with its charset label (`Absent` when segment 0 wrote none),
      * its language tag, and its octets, `%XX` escapes decoded and the unencoded segments' text turned into octets.
      */
    enum Value derives CanEqual:
        case Text(text: String)
        case Encoded(charset: Maybe[String], language: Maybe[String], octets: Span[Byte])

    /** How a parameter is written: `Mime` (RFC 2231, 76-octet segments, for a folded header) or `Http` (RFC 8187, one unit). */
    enum Style derives CanEqual:
        case Mime, Http

    /** The RFC 8187 reading of a value: plain text as it is, an encoded value's octets as UTF-8 whatever charset label it carries. */
    val decodeUtf8: Value => String =
        case Value.Text(text)            => text
        case Value.Encoded(_, _, octets) => new String(octets.toArray, StandardCharsets.UTF_8)

    /** The UTF-8 octets of text, the default for [[read]] and what [[write]] percent-encodes: an unpaired surrogate is U+FFFD on every
      * platform.
      */
    val utf8Octets: String => Span[Byte] = Utf8.encode

    /** The parameters in `text` from `from`, which is where the head token ends; `unencoded` gives the octets of an unencoded segment
      * that is merged into an encoded value.
      */
    def read(text: String, from: Int, unencoded: String => Span[Byte] = utf8Octets): Chunk[(String, Value)] =
        mergeParameters(rawParameters(text, skipCfws(text, from)), unencoded)

    /** The parameters in `text` from `from`, every value turned into text by `decode`, which sees the plain values too: a protocol whose
      * plain values carry a further encoding (mail's RFC 2047 encoded words inside a quoted string) decodes them there.
      */
    def readDecoded(
        text: String,
        from: Int,
        decode: Value => String = decodeUtf8,
        unencoded: String => Span[Byte] = utf8Octets
    ): Chunk[(String, String)] =
        read(text, from, unencoded).map((name, value) => (name, decode(value)))

    /** True when the text at `at` is the end, a `;`, or a parameter written with no `;` before it. */
    private[kyo] def opensParameters(text: String, at: Int): Boolean =
        at >= text.length || text.charAt(at) == ';' || startsParameter(text, at, text.length)

    /** The units `name=value` of one parameter in `style`, each a whole `name*N=...` or `name=...` the caller joins with `;` and SP or
      * folds onto its own line.
      */
    def write(name: String, value: String, style: Style)(using Frame): Result[MimeInvalidParameterException, Chunk[String]] =
        if !isToken(name) then Result.fail(MimeInvalidParameterException(MimeException.Violation.NotAToken("parameter name", name)))
        else if name.indexOf('*') >= 0 then
            Result.fail(MimeInvalidParameterException(MimeException.Violation.UnwritableParameterName(name)))
        else
            style match
                case Style.Http => Result.succeed(Chunk(httpUnit(name, value)))
                case Style.Mime => Result.succeed(mimeUnits(name, value))

    /** `head` and every parameter's units joined into one header line: `head; a=1; b="x y"`. */
    def render(head: String, parameters: Chunk[(String, String)], style: Style)(using
        Frame
    ): Result[MimeInvalidParameterException, String] =
        parameters.foldLeft(Result.succeed(Chunk.empty[String]): Result[MimeInvalidParameterException, Chunk[String]]) {
            (done, parameter) => done.flatMap(units => write(parameter._1, parameter._2, style).map(units ++ _))
        }.map(units => if units.isEmpty then head else head + "; " + units.mkString("; "))

    // --- reading ---

    // The parameters from `start`, as written.
    private def rawParameters(text: String, start: Int): Chunk[(String, String)] =
        val raw = ChunkBuilder.init[(String, String)]
        @scala.annotation.tailrec
        def loop(at: Int): Unit =
            if at < text.length then
                if text.charAt(at) == ';' then loop(skipCfws(text, at + 1))
                else
                    val nameEnd = tokenEnd(text, at)
                    val equals  = skipCfws(text, nameEnd)
                    if nameEnd == at || equals >= text.length || text.charAt(equals) != '=' then loop(semicolonOrEnd(text, at))
                    else
                        val valueStart = skipCfws(text, equals + 1)
                        val name       = text.substring(at, nameEnd)
                        if valueStart < text.length && text.charAt(valueStart) == '"' then
                            val (content, after) = quotedString(text, valueStart)
                            discard(raw.addOne(name -> content))
                            val next = skipCfws(text, after)
                            loop(if opensParameters(text, next) then next else semicolonOrEnd(text, next))
                        else
                            val (valueEnd, next) = unquotedValue(text, valueStart)
                            if valueEnd > valueStart then discard(raw.addOne(name -> text.substring(valueStart, valueEnd)))
                            loop(next)
                        end if
                    end if
        loop(start)
        raw.result()
    end rawParameters

    // An unquoted value from `start`: where it ends and where reading resumes. It runs to the next `;`, or to the CFWS before a parameter
    // written with no `;` before it; white space and closed comments at its end are removed, and inside it every other character is data.
    // One pass: a comment is matched once where it starts, and an unclosed `(` ends the scan, since everything from it on is data.
    private def unquotedValue(text: String, start: Int): (Int, Int) =
        val limit = semicolonOrEnd(text, start)
        @scala.annotation.tailrec
        def loop(at: Int, contentEnd: Int): (Int, Int) =
            if at >= limit then (contentEnd, limit)
            else
                val c = text.charAt(at)
                if isWhiteSpace(c) then loop(at + 1, contentEnd)
                else if c == '(' then
                    val close = commentEnd(text, at, limit)
                    if close >= 0 then loop(close, contentEnd)
                    else (withoutTrailingWhiteSpace(text, at, limit), limit)
                else if contentEnd < at && at > start && startsParameter(text, at, limit) then (contentEnd, at)
                else loop(at + 1, at + 1)
                end if
        loop(start, start)
    end unquotedValue

    // A token at `at`, then CFWS, then `=`, all before `limit`.
    private def startsParameter(text: String, at: Int, limit: Int): Boolean =
        val end = tokenEnd(text, at)
        if end == at then false
        else
            val equals = cfwsEnd(text, end, limit)
            equals < limit && text.charAt(equals) == '='
        end if
    end startsParameter

    // How a parameter name reads under RFC 2231 section 7: `attribute`, then optionally `*` and a section number, then optionally `*` for
    // an encoded value. A name of any other shape is a plain parameter of that whole name.
    private enum NameForm derives CanEqual:
        case Plain(name: String)
        case Extended(base: String)
        case Section(base: String, index: Int, encoded: Boolean)
    end NameForm

    private def nameForm(name: String): NameForm =
        val star = name.indexOf('*')
        if star <= 0 then NameForm.Plain(name)
        else
            val base = name.substring(0, star)
            val rest = name.substring(star + 1)
            if rest.isEmpty then NameForm.Extended(base)
            else
                val encoded = rest.endsWith("*")
                Ascii.parseDigits(if encoded then rest.dropRight(1) else rest) match
                    case Present(index) => NameForm.Section(base, index, encoded)
                    case Absent         => NameForm.Plain(name)
            end if
        end if
    end nameForm

    // What the parameters of one name hold: its first plain value, its first `name*` value, and its first segment per index.
    final private case class Forms(
        plain: Maybe[String] = Absent,
        extended: Maybe[String] = Absent,
        sections: Map[Int, (Boolean, String)] = Map.empty
    )

    // Merges the parameters by name, lowercased, each where its name first occurs: the continuation set if it has a segment, else
    // `name*`, else the plain value.
    private def mergeParameters(raw: Chunk[(String, String)], unencoded: String => Span[Byte]): Chunk[(String, Value)] =
        val (byName, names) =
            raw.foldLeft((Map.empty[String, Forms], Chunk.empty[String])) { (merged, parameter) =>
                val (byName, names) = merged
                val (name, value)   = parameter
                val form            = nameForm(name)
                val key             =
                    form match
                        case NameForm.Plain(plain)        => Ascii.toLower(plain)
                        case NameForm.Extended(base)      => Ascii.toLower(base)
                        case NameForm.Section(base, _, _) => Ascii.toLower(base)
                val forms   = byName.getOrElse(key, Forms())
                val updated =
                    form match
                        case NameForm.Plain(_) =>
                            if forms.plain.isEmpty then forms.copy(plain = Present(value)) else forms
                        case NameForm.Extended(_) =>
                            if forms.extended.isEmpty then forms.copy(extended = Present(value)) else forms
                        case NameForm.Section(_, index, encoded) =>
                            if forms.sections.contains(index) then forms
                            else forms.copy(sections = forms.sections.updated(index, (encoded, value)))
                (byName.updated(key, updated), if byName.contains(key) then names else names.append(key))
            }
        names.flatMap { name =>
            Maybe.fromOption(byName.get(name)).flatMap { forms =>
                if forms.sections.nonEmpty then Present(joinSegments(Chunk.from(forms.sections.toSeq.sortBy(_._1)), unencoded))
                else
                    forms.extended.map(v => joinSegments(Chunk(0 -> (true, v)), unencoded))
                        .orElse(forms.plain.map(Value.Text(_)))
            }.map(name -> _).toChunk
        }
    end mergeParameters

    // The value of a continuation, segments in index order. With no encoded segment, the segments' texts are joined. With one, every
    // segment becomes octets, and segment 0's `charset'language'` prefix, when it has one, names the charset and language (RFC 2231
    // section 4.1).
    private def joinSegments(segments: Chunk[(Int, (Boolean, String))], unencoded: String => Span[Byte]): Value =
        if !segments.exists(_._2._1) then Value.Text(segments.map(_._2._2).mkString)
        else
            val (charset, language, pieces) =
                segments.headMaybe match
                    case Present((0, (true, first))) =>
                        val open  = first.indexOf('\'')
                        val close = if open < 0 then -1 else first.indexOf('\'', open + 1)
                        if close < 0 then (Maybe.empty[String], Maybe.empty[String], segments.map(_._2))
                        else
                            val charset  = first.substring(0, open)
                            val language = first.substring(open + 1, close)
                            (
                                if charset.isEmpty then Absent else Present(charset),
                                if language.isEmpty then Absent else Present(language),
                                (true, first.substring(close + 1)) +: segments.drop(1).map(_._2)
                            )
                        end if
                    case Present(_) | Absent => (Maybe.empty[String], Maybe.empty[String], segments.map(_._2))
            Value.Encoded(charset, language, segmentOctets(pieces, unencoded))
    end joinSegments

    // The octets of the segments in order: an encoded segment's `%XX` is its octet and its other characters go through `unencoded` in
    // runs; an unencoded segment's whole text goes through `unencoded`.
    private def segmentOctets(pieces: Chunk[(Boolean, String)], unencoded: String => Span[Byte]): Span[Byte] =
        val out = new java.io.ByteArrayOutputStream
        pieces.foreach { (encoded, text) =>
            if !encoded then
                val octets = unencoded(text)
                out.write(octets.toArray, 0, octets.size)
            else
                val run              = new java.lang.StringBuilder
                def flushRun(): Unit =
                    if run.length > 0 then
                        val octets = unencoded(run.toString)
                        out.write(octets.toArray, 0, octets.size)
                        run.setLength(0)
                var i = 0
                while i < text.length do
                    val c = text.charAt(i)
                    if c == '%' && i + 2 < text.length && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2)) then
                        flushRun()
                        out.write((hexValue(text.charAt(i + 1)) << 4) | hexValue(text.charAt(i + 2)))
                        i += 3
                    else
                        discard(run.append(c))
                        i += 1
                    end if
                end while
                flushRun()
        }
        // Unsafe: the stream's array is fresh and held by nothing else once returned.
        Span.fromUnsafe(out.toByteArray)
    end segmentOctets

    // --- writing ---

    private inline val MimeLineRoom = 76

    // RFC 2231 with a 76-octet room per unit: a token or a quoted string when it fits and reads back as it is; quoted continuations for a
    // longer printable ASCII value; `%XX` segments otherwise, which is where a value holding `=?`, a control character or non-ASCII goes.
    private def mimeUnits(name: String, value: String): Chunk[String] =
        if value.nonEmpty && value.forall(isTokenChar) && name.length + 1 + value.length <= MimeLineRoom then Chunk(s"$name=$value")
        else if value.forall(c => c >= ' ' && c <= '~') && !value.contains("=?") then
            if name.length + 1 + quotedLength(value) <= MimeLineRoom then Chunk(s"$name=${quoted(value)}")
            else quotedContinuations(name, value)
        else extendedSegments(name, utf8Octets(value))

    // `name*0="..."`, `name*1="..."`, each at most 76 octets where the name leaves room, never cut inside a quoted pair.
    private def quotedContinuations(name: String, value: String): Chunk[String] =
        val segments = ChunkBuilder.init[String]
        @scala.annotation.tailrec
        def loop(index: Int, start: Int): Unit =
            if start < value.length then
                val prefix = s"$name*$index=\""
                val room   = math.max(2, MimeLineRoom - prefix.length - 1)
                @scala.annotation.tailrec
                def take(at: Int, used: Int): Int =
                    if at >= value.length then at
                    else
                        val cost = if value.charAt(at) == '"' || value.charAt(at) == '\\' then 2 else 1
                        if used + cost > room && at > start then at else take(at + 1, used + cost)
                val end = take(start, 0)
                discard(segments.addOne(prefix + quotedContent(value.substring(start, end)) + "\""))
                loop(index + 1, end)
        loop(0, 0)
        segments.result()
    end quotedContinuations

    // `name*=utf-8''...` when it fits in 76 octets, else `name*0*=utf-8''...`, `name*1*=...`, never cut inside a `%XX`.
    private def extendedSegments(name: String, octets: Span[Byte]): Chunk[String] =
        val whole = s"$name*=utf-8''" + percentEncoded(octets, 0, octets.size)
        if whole.length <= MimeLineRoom then Chunk(whole)
        else
            val segments = ChunkBuilder.init[String]
            @scala.annotation.tailrec
            def loop(index: Int, start: Int): Unit =
                if start < octets.size then
                    val prefix = if index == 0 then s"$name*0*=utf-8''" else s"$name*$index*="
                    val room   = math.max(3, MimeLineRoom - prefix.length)
                    @scala.annotation.tailrec
                    def take(at: Int, used: Int): Int =
                        if at >= octets.size then at
                        else
                            val cost = if isAttributeChar(octets(at) & 0xff) then 1 else 3
                            if used + cost > room && at > start then at else take(at + 1, used + cost)
                    val end = take(start, 0)
                    discard(segments.addOne(prefix + percentEncoded(octets, start, end)))
                    loop(index + 1, end)
            loop(0, 0)
            segments.result()
        end if
    end extendedSegments

    // RFC 8187: a token bare, a printable ASCII value as a quoted string, anything else as one `name*=UTF-8''...` unit.
    private def httpUnit(name: String, value: String): String =
        if value.nonEmpty && value.forall(isTokenChar) then s"$name=$value"
        else if value.forall(c => c >= ' ' && c <= '~') then s"$name=${quoted(value)}"
        else
            val octets = utf8Octets(value)
            s"$name*=UTF-8''" + percentEncoded(octets, 0, octets.size)

    private def percentEncoded(octets: Span[Byte], from: Int, until: Int): String =
        var size = 0
        var i    = from
        while i < until do
            size += (if isAttributeChar(octets(i) & 0xff) then 1 else 3)
            i += 1
        val out = new java.lang.StringBuilder(size)
        i = from
        while i < until do
            val b = octets(i) & 0xff
            if isAttributeChar(b) then discard(out.append(b.toChar))
            else discard(out.append('%').append(HexDigits.charAt(b >> 4)).append(HexDigits.charAt(b & 0xf)))
            i += 1
        end while
        out.toString
    end percentEncoded

end Parameters
