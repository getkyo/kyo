package kyo

/** The pre-signed url of an uploaded media file, as `WhatsAppMedia.resolveUrl` answers it in a [[kyo.WhatsAppMedia.MediaInfo]].
  *
  * Its query (`hash`, `ext` and the rest) authorizes a download on its own for about five minutes, so it is a type of its own rather
  * than an `HttpUrl`: no rendering of it shows the query. `toString` is the url without its query followed by `?<redacted>`, and a value
  * that holds one, such as a `MediaInfo` in a log line, inherits that. The full url is read only through `value`, which `downloadFrom`
  * uses.
  *
  * Its `Schema` reads and writes the full url as Meta's JSON string: serializing a value is an explicit act, and a string that is not a
  * url is refused. Equality is by the url.
  */
final class WhatsAppMediaUrl private (val value: HttpUrl):
    override def equals(other: Any): Boolean = other match
        case that: WhatsAppMediaUrl => value == that.value
        case _                      => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = s"WhatsAppMediaUrl(${value.copy(rawQuery = Absent).full}${value.rawQuery.fold("")(_ => "?<redacted>")})"
end WhatsAppMediaUrl

object WhatsAppMediaUrl:

    def apply(value: HttpUrl): WhatsAppMediaUrl = new WhatsAppMediaUrl(value)

    given (using Frame): Schema[WhatsAppMediaUrl] = Schema[String].transformVia((text: String) =>
        HttpUrl.parse(text) match
            case Result.Success(url) => Result.succeed(WhatsAppMediaUrl(url))
            case _                   => Result.fail("a url that does not parse")
    )(_.value.full)

    given CanEqual[WhatsAppMediaUrl, WhatsAppMediaUrl] = CanEqual.derived
end WhatsAppMediaUrl
