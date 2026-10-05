package kyo

import kyo.internal.whatsapp.Secret

/** The Cloud API access token, sent as the bearer of every outbound request.
  *
  * A type of its own so it cannot be passed where the app secret or the verify token is expected, and so no value that holds it can print
  * it: `toString` is `WhatsAppToken(<redacted>)`, and `WhatsAppConfig`'s derived `toString` inherits that. The raw text is read only
  * through `value`, at the point the `Authorization` header is built.
  *
  * IMPORTANT: `init` checks the text and fails with [[kyo.WhatsAppInvalidTokenException]] when it cannot be sent in the
  * `Authorization` header: empty, or holding a character other than printable ASCII without space (a CR or LF would end the header). Meta
  * documents no alphabet and no length ("Use a variable-length data type without a specific maximum size to store access tokens", Access
  * Token Guide), and an app access token is `{app-id}|{app-secret}`, so neither is bounded further.
  *
  * Equality is by value. It has no `Schema`, so it cannot be serialized by accident.
  *
  * @see
  *   [[kyo.WhatsAppConfig]] the config that holds it
  */
final class WhatsAppToken private (val value: String):
    override def equals(other: Any): Boolean = other match
        case that: WhatsAppToken => value == that.value
        case _                   => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = "WhatsAppToken(<redacted>)"
end WhatsAppToken

object WhatsAppToken:

    /** The token `value`, or the [[kyo.WhatsAppInvalidTokenException]] saying why the text cannot be one. */
    def init(value: String)(using Frame): Result[WhatsAppInvalidTokenException, WhatsAppToken] =
        Secret.init(WhatsAppInvalidTokenException.Token.AccessToken, value)(new WhatsAppToken(_))

    given CanEqual[WhatsAppToken, WhatsAppToken] = CanEqual.derived
end WhatsAppToken
