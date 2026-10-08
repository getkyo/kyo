package kyo.internal.email.mime

import kyo.*

/** The media types a reader gives a part whose own `Content-Type` does not decide it: `text/plain; charset=us-ascii` for a part with no
  * valid field (RFC 2045 section 5.2), `message/rfc822` for such a part inside a `multipart/digest` (RFC 2046 section 5.1.5), and
  * `application/octet-stream` for a part whose transfer encoding is not known (RFC 2045 section 6.4).
  */
final private[kyo] case class MediaTypeDefaults(
    plainText: Email.MediaType,
    messageRfc822: Email.MediaType,
    octetStream: Email.MediaType
)

private[kyo] object MediaTypeDefaults:

    /** The defaults, each built through `MediaType.init`; a reader's entry point resolves them and hands them to the parser and model. */
    def built(using Frame): Result[MimeInvalidMediaTypeException, MediaTypeDefaults] =
        for
            plainText     <- Email.MediaType.init("text", "plain", "charset" -> "us-ascii")
            messageRfc822 <- Email.MediaType.init("message", "rfc822")
            octetStream   <- Email.MediaType.init("application", "octet-stream")
        yield MediaTypeDefaults(plainText, messageRfc822, octetStream)
        end for
    end built

end MediaTypeDefaults
