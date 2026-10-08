package kyo.internal.email.net

import kyo.*
import kyo.internal.charset.Utf8
import kyo.internal.email.imap.ImapCodec
import scala.annotation.tailrec

/** Every form in which one credential crosses the wire, replaced by `<redacted>` in server text before a failure stores it.
  *
  * A server may echo a command into its reply, so the forms are the ones the clients write: the secret itself, IMAP's quoted form of it
  * (whose escapes split the plain text), the base64 of PLAIN's and XOAUTH2's initial responses and of an `AUTH LOGIN` line, and the
  * `\xNN` form `LineConnection.shown` gives a non-ASCII secret. Longer forms are replaced first, so a form containing another is replaced
  * whole.
  */
final private[kyo] class Redactor private (forms: Chunk[String]):

    def redact(text: String): String =
        forms.foldLeft(text)((redacted, form) => redacted.replace(form, Redactor.Mask))

    /** `text` redacted, then cut to `limit` characters. Cutting first would split a form and leave the part before the cut in place.
      *
      * A text over `limit` may itself have been cut where it was read, splitting a form at its end, and the replacements shorten what
      * precedes that fragment; so the redacted text also loses its longest tail that begins a form. That tail is found only after
      * redacting: a periodic secret makes the end of a whole form equal to the start of another, and dropping it first would break the
      * whole form and leave its head. It never reaches into a mask, whose last characters may begin a form too.
      */
    def redact(text: String, limit: Int): String =
        val redacted = redact(text)
        val kept     = if text.length <= limit then redacted.length else redacted.length - partialTail(redacted)
        redacted.take(Math.min(limit, kept))
    end redact

    private def partialTail(text: String): Int =
        val afterMask = text.lastIndexOf(Redactor.Mask) match
            case -1   => text.length
            case mask => text.length - mask - Redactor.Mask.length
        @tailrec def tail(form: String, n: Int): Int =
            if n == 0 || text.endsWith(form.substring(0, n)) then n else tail(form, n - 1)
        forms.foldLeft(0)((longest, form) => Math.max(longest, tail(form, Math.min(form.length - 1, afterMask))))
    end partialTail

end Redactor

private[kyo] object Redactor:

    inline val Mask = "<redacted>"

    def password(user: String, password: Email.Password): Redactor =
        val secret = password.value
        val quoted = ImapCodec.astring(secret, literalPlus = false).collect { case ImapCodec.Part.Text(text) => text }
        of(Chunk(secret, Sasl.base64(secret), Sasl.plain(user, password)).concat(quoted))
    end password

    def token(user: String, token: Email.OAuthToken): Redactor =
        of(Chunk(token.value, Sasl.xoauth2(user, token)))

    private def of(secrets: Chunk[String]): Redactor =
        val shown = secrets.map(secret => LineConnection.shown(Utf8.encode(secret)))
        new Redactor(secrets.concat(shown).filter(_.nonEmpty).distinct.sortBy(-_.length))

end Redactor
