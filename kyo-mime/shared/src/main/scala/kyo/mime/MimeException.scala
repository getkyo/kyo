package kyo.mime

import kyo.*
import kyo.internal.mime.Grammar

/** The failures of kyo-mime, all at the construction or the parsing of a value: a media type or a disposition whose text is not one, a
  * type, subtype, disposition type or parameter name that is not an RFC 2045 token, a parameter named twice, or a parameter that cannot
  * be written because its name carries the `*` RFC 2231 reserves. None reaches an effect row: `parse` and `render` return them in a
  * `Result`, and `apply` panics with them.
  *
  * A message names the offending token, escaped and cut at 200 characters; it never carries a whole header value.
  */
sealed abstract class MimeException(message: String)(using Frame) extends KyoException(message)

object MimeException:

    /** What is invalid, shared by the three leaves. */
    enum Violation derives CanEqual:
        /** The text is not the grammar's head: no type and subtype, or no disposition type, or other text after it. */
        case NotWellFormed(text: String)

        /** A type, subtype, disposition type or parameter name that is not an RFC 2045 token. */
        case NotAToken(part: String, value: String)

        /** A parameter name written twice; a reader keeps only the first, so the second could never be read back. */
        case DuplicateParameter(name: String)

        /** A parameter name holding `*`, which RFC 2231 reserves for continuations and encoded values, so no writer can emit it. */
        case UnwritableParameterName(name: String)

        def describe: String =
            this match
                case NotWellFormed(text)           => s"${Grammar.printable(text)} is not well-formed"
                case NotAToken(part, value)        => s"$part ${Grammar.printable(value)} is not a token"
                case DuplicateParameter(name)      => s"parameter ${Grammar.printable(name)} occurs twice"
                case UnwritableParameterName(name) => s"parameter name ${Grammar.printable(name)} holds *"
    end Violation

end MimeException

/** A [[MediaType]] was parsed from text that is not one, or constructed from parts that are not. */
final case class MimeInvalidMediaTypeException(violation: MimeException.Violation)(using Frame)
    extends MimeException(s"invalid media type: ${violation.describe}")

/** A [[Disposition]] was parsed from text that is not one, or constructed from parts that are not. */
final case class MimeInvalidDispositionException(violation: MimeException.Violation)(using Frame)
    extends MimeException(s"invalid disposition: ${violation.describe}")

/** A parameter cannot be written: its name is not a token or holds `*`. */
final case class MimeInvalidParameterException(violation: MimeException.Violation)(using Frame)
    extends MimeException(s"invalid parameter: ${violation.describe}")
