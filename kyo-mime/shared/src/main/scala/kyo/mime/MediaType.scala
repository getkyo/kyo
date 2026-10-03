package kyo.mime

import kyo.*
import kyo.internal.Ascii
import kyo.internal.mime.Grammar
import kyo.internal.mime.Grammar.*

/** A media type (RFC 2045 section 5.1, RFC 9110 section 8.3.1): a type and a subtype, each an RFC 2045 token held lowercased, and
  * parameters with lowercased names in the order written, each name once.
  *
  * A value of this type has passed its checks: it is built by [[MediaType.init]] from parts, or by [[MediaType.parse]] from the text of
  * a `Content-Type` value, with CFWS and comments removed, quoted strings resolved and RFC 2231
  * parameters merged. `render` writes it back as one line in a [[Parameters.Style]]. The `boundary` parameter never ends in white space
  * (RFC 2046 section 5.1.1), so a boundary written with it reads back equal.
  *
  * @see
  *   [[Parameters]] for the parameter grammar on its own and for an RFC 2231 encoded value before it is decoded
  */
final case class MediaType private (mainType: String, subType: String, parameters: Chunk[MediaType.Parameter]) derives CanEqual:

    /** The type and subtype without parameters, such as `text/plain`. */
    def baseType: String = s"$mainType/$subType"

    /** The value of the parameter `name`, compared ASCII case-insensitively. */
    def parameter(name: String): Maybe[String] =
        val wanted = Ascii.toLower(name)
        Maybe.fromOption(parameters.find(_.name == wanted)).map(_.value)

    /** The `charset` parameter, when present. */
    def charset: Maybe[String] = parameter("charset")

    /** This media type as one HTTP header line (`Parameters.Style.Http`): `text/plain; charset=utf-8`. */
    def render(using Frame): Result[MimeInvalidParameterException, String] = render(Parameters.Style.Http)

    /** This media type as one header line in `style`. */
    def render(style: Parameters.Style)(using Frame): Result[MimeInvalidParameterException, String] =
        Parameters.render(baseType, parameters.map(p => (p.name, p.value)), style)

end MediaType

object MediaType:

    /** One `name=value` parameter. `name` is lowercase. Its `Schema` decodes through the checks of `init`. */
    final case class Parameter private[MediaType] (name: String, value: String) derives CanEqual

    object Parameter:
        given Schema[Parameter] =
            Schema.derivedVia((name: String, value: String) => checkedParameter(name, value))

    /** A media type from its parts, or the violation: a part that is not a token, or a name given twice. */
    def init(mainType: String, subType: String, parameters: (String, String)*)(using
        Frame
    ): Result[MimeInvalidMediaTypeException, MediaType] =
        checked(mainType, subType, Chunk.from(parameters))

    /** The media type `text` holds: `type/subtype`, then parameters, with CFWS anywhere between them. `decode` turns each parameter
      * value, plain or RFC 2231 encoded, into text ([[Parameters.decodeUtf8]] by default, the HTTP policy) and `unencoded` gives the
      * octets of an unencoded segment merged into an encoded value. Anything but a type, a subtype and parameters is not a media type.
      */
    def parse(
        text: String,
        decode: Parameters.Value => String = Parameters.decodeUtf8,
        unencoded: String => Span[Byte] = Parameters.utf8Octets
    )(using Frame): Result[MimeInvalidMediaTypeException, MediaType] =
        val typeStart = skipCfws(text, 0)
        val typeEnd   = tokenEnd(text, typeStart)
        val slash     = skipCfws(text, typeEnd)
        if typeEnd == typeStart || slash >= text.length || text.charAt(slash) != '/' then
            Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.NotWellFormed(text)))
        else
            val subStart = skipCfws(text, slash + 1)
            val subEnd   = tokenEnd(text, subStart)
            val rest     = skipCfws(text, subEnd)
            if subEnd == subStart || !Parameters.opensParameters(text, rest) then
                Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.NotWellFormed(text)))
            else
                checked(
                    text.substring(typeStart, typeEnd),
                    text.substring(subStart, subEnd),
                    Parameters.readDecoded(text, rest, decode, unencoded)
                )
            end if
        end if
    end parse

    given Schema[MediaType] =
        Schema.derivedVia((mainType: String, subType: String, parameters: Chunk[Parameter]) =>
            checked(mainType, subType, parameters.map(p => (p.name, p.value)))
        )

    private def checked(mainType: String, subType: String, parameters: Chunk[(String, String)])(using
        Frame
    ): Result[MimeInvalidMediaTypeException, MediaType] =
        if !isToken(mainType) then Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.NotAToken("type", mainType)))
        else if !isToken(subType) then Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.NotAToken("subtype", subType)))
        else
            Maybe.fromOption(parameters.find((name, _) => !isToken(name))) match
                case Present((name, _)) =>
                    Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.NotAToken("parameter name", name)))
                case Absent =>
                    val normalized = parameters.map(normalizedParameter)
                    val names      = normalized.map(_.name)
                    names.diff(names.distinct).headMaybe match
                        case Present(name) => Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.DuplicateParameter(name)))
                        case Absent        => Result.succeed(new MediaType(Ascii.toLower(mainType), Ascii.toLower(subType), normalized))
                    end match

    private def checkedParameter(name: String, value: String)(using Frame): Result[MimeInvalidMediaTypeException, Parameter] =
        if isToken(name) then Result.succeed(normalizedParameter(name, value))
        else Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.NotAToken("parameter name", name)))

    private def normalizedParameter(name: String, value: String): Parameter =
        val lower = Ascii.toLower(name)
        Parameter(lower, if lower == "boundary" then Grammar.withoutTrailingWhiteSpace(value) else value)

end MediaType
