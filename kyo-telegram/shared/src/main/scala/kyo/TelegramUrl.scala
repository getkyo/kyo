package kyo

/** A link Telegram opens for a user: an inline button's URL, a callback answer's URL, a text link in a
  * message.
  *
  * Telegram accepts `tg://` links here as well as `http` and `https` (`tg://user?id=<id>` opens a
  * profile), which `HttpUrl` does not parse, so these fields take this type rather than `HttpUrl`. The
  * module never sends a request to it: Telegram's clients open it.
  *
  * IMPORTANT: construction accepts text that starts with `http://`, `https://` or `tg://` in any ASCII
  * case, followed by one or more printable ASCII characters other than space, and panics with
  * [[kyo.TelegramInvalidUrlException]] otherwise. A text link that arrives in a message with a URL that
  * does not pass is the entity kind `Other("text_link")`, as one with no URL is.
  *
  * @see
  *   [[kyo.TelegramKeyboard.InlineButton]] a button that opens a link
  */
opaque type TelegramUrl = String

object TelegramUrl:

    /** The link `value`, panicking with [[kyo.TelegramInvalidUrlException]] when it is not an http, https or tg URL. */
    def apply(value: String)(using Frame): TelegramUrl =
        problemOf(value).foreach(p => throw TelegramInvalidUrlException(p))
        value

    extension (self: TelegramUrl) def value: String = self

    /** `value` as a link, when it is one; how a link that arrives in a message is read. */
    private[kyo] def parse(value: String): Maybe[TelegramUrl] = if problemOf(value).isEmpty then Present(value) else Absent

    given CanEqual[TelegramUrl, TelegramUrl] = CanEqual.derived

    inline given Schema[TelegramUrl] = compiletime.error("TelegramUrl has no Schema: kyo-telegram encodes and decodes it itself")

    /** Why `value` is not a link this type holds. */
    private[kyo] def problemOf(value: String): Maybe[TelegramInvalidUrlException.Problem] =
        import TelegramInvalidUrlException.Problem
        Schemes.filter(s => value.length >= s.length && asciiLower(value.substring(0, s.length)) == s).headMaybe match
            case Absent          => Present(Problem.Scheme)
            case Present(scheme) =>
                if value.length == scheme.length then Present(Problem.Empty)
                else
                    val bad = value.indexWhere(c => c <= ' ' || c > '~')
                    if bad >= 0 then Present(Problem.Character(bad)) else Absent
        end match
    end problemOf

    private val Schemes: Chunk[String] = Chunk("http://", "https://", "tg://")

    private def asciiLower(s: String): String = s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)

end TelegramUrl
