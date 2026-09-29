package kyo.mime

import kyo.*
import kyo.internal.Ascii
import kyo.internal.mime.Grammar.*

/** A `Content-Disposition` value (RFC 2183 section 2, RFC 6266): the disposition type, an RFC 2045 token held lowercased (`inline`,
  * `attachment`, `form-data`, or any other), and parameters with lowercased names in the order written, each name once.
  *
  * Built by [[Disposition.init]] from parts, or by [[Disposition.parse]] from the value's text with CFWS removed, quoted strings resolved and RFC 2231 parameters merged, so `filename*=UTF-8''r%C3%A9sum%C3%A9.pdf` reads as the parameter
  * `filename`. `render` writes it back in a [[Parameters.Style]]: `Http` writes a non-ASCII file name as `filename*` (RFC 8187), which is
  * how a download name reaches a browser intact. A multipart/form-data part header follows the HTML standard instead, so it is read by
  * [[Disposition.parseFormData]] and written with `Parameters.Style.FormData`.
  */
final case class Disposition private (kind: String, parameters: Chunk[MediaType.Parameter]) derives CanEqual:

    /** The value of the parameter `name`, compared ASCII case-insensitively. */
    def parameter(name: String): Maybe[String] =
        val wanted = Ascii.toLower(name)
        Maybe.fromOption(parameters.find(_.name == wanted)).map(_.value)

    /** The `filename` parameter, when present. */
    def filename: Maybe[String] = parameter("filename")

    /** The `name` parameter of a `form-data` part, when present. */
    def name: Maybe[String] = parameter("name")

    /** This disposition as one HTTP header line (`Parameters.Style.Http`): `attachment; filename="report.pdf"`. */
    def render(using Frame): Result[MimeInvalidParameterException, String] = render(Parameters.Style.Http)

    /** This disposition as one header line in `style`. */
    def render(style: Parameters.Style)(using Frame): Result[MimeInvalidParameterException, String] =
        Parameters.render(kind, parameters.map(p => (p.name, p.value)), style)

end Disposition

object Disposition:

    /** A disposition from its parts, or the violation: a part that is not a token, or a name given twice. */
    def init(kind: String, parameters: (String, String)*)(using Frame): Result[MimeInvalidDispositionException, Disposition] =
        checked(kind, Chunk.from(parameters))

    /** The disposition `text` holds: a type, then parameters, with CFWS anywhere between them; `decode` and `unencoded` as in
      * [[MediaType.parse]]. Anything but a type and parameters is not a disposition.
      */
    def parse(
        text: String,
        decode: Parameters.Value => String = Parameters.decodeUtf8,
        unencoded: String => Span[Byte] = Parameters.utf8Octets
    )(using Frame): Result[MimeInvalidDispositionException, Disposition] =
        val start = skipCfws(text, 0)
        val end   = tokenEnd(text, start)
        val rest  = skipCfws(text, end)
        if end == start || !Parameters.opensParameters(text, rest) then
            Result.fail(MimeInvalidDispositionException(MimeException.Violation.NotWellFormed(text)))
        else checked(text.substring(start, end), Parameters.readDecoded(text, rest, decode, unencoded))
    end parse

    /** The disposition of a multipart/form-data part, read by the HTML standard's multipart/form-data parser rather than by RFC 6266:
      * exactly `form-data; name="..."`, optionally followed by `; filename="..."`. A quoted value runs to the next `"`, with no quoted
      * pairs, and a CR or LF inside it is no disposition; `%0A`, `%0D` and `%22` then read as LF, CR and `"`, and nothing else is decoded.
      * This is the reading of what a browser, and `Parameters.Style.FormData`, writes: a backslash in a Windows path stays a backslash.
      */
    def parseFormData(text: String)(using Frame): Result[MimeInvalidDispositionException, Disposition] =
        def malformed = Result.fail(MimeInvalidDispositionException(MimeException.Violation.NotWellFormed(text)))
        // The end of the quoted value that opens at `from`, when it closes before any CR or LF.
        def quotedEnd(from: Int): Maybe[Int] =
            @scala.annotation.tailrec
            def loop(at: Int): Maybe[Int] =
                if at >= text.length then Absent
                else
                    text.charAt(at) match
                        case '"'         => Present(at)
                        case '\r' | '\n' => Absent
                        case _           => loop(at + 1)
            loop(from)
        end quotedEnd
        val namePrefix     = "form-data; name=\""
        val filenamePrefix = "; filename=\""
        if !text.startsWith(namePrefix) then malformed
        else
            quotedEnd(namePrefix.length) match
                case Absent           => malformed
                case Present(nameEnd) =>
                    val name = Parameters.formDataUnescaped(text.substring(namePrefix.length, nameEnd))
                    val rest = nameEnd + 1
                    if rest == text.length then checked("form-data", Chunk("name" -> name))
                    else if !text.startsWith(filenamePrefix, rest) then malformed
                    else
                        val filenameStart = rest + filenamePrefix.length
                        quotedEnd(filenameStart) match
                            case Present(filenameEnd) if filenameEnd + 1 == text.length =>
                                val filename = Parameters.formDataUnescaped(text.substring(filenameStart, filenameEnd))
                                checked("form-data", Chunk("name" -> name, "filename" -> filename))
                            case _ => malformed
                        end match
                    end if
            end match
        end if
    end parseFormData

    given Schema[Disposition] =
        Schema.derivedVia((kind: String, parameters: Chunk[MediaType.Parameter]) =>
            checked(kind, parameters.map(p => (p.name, p.value)))(using Frame.internal)
        )

    private def checked(kind: String, parameters: Chunk[(String, String)])(using
        Frame
    ): Result[MimeInvalidDispositionException, Disposition] =
        if !isToken(kind) then Result.fail(MimeInvalidDispositionException(MimeException.Violation.NotAToken("disposition type", kind)))
        else
            MediaType.init("x", "x", parameters*) match
                case Result.Success(checkedType) => Result.succeed(new Disposition(Ascii.toLower(kind), checkedType.parameters))
                case Result.Failure(violation)   => Result.fail(MimeInvalidDispositionException(violation.violation))
                case Result.Panic(exception)     => Result.panic(exception)

end Disposition
