package kyo

import kyo.internal.whatsapp.Secret

/** The Meta app secret, the key of the HMAC-SHA256 that signs every webhook POST in its `X-Hub-Signature-256` header.
  *
  * A type of its own so it cannot be passed where the access token or the verify token is expected, and so no value that holds it can
  * print it: `toString` is `WhatsAppAppSecret(<redacted>)`. [[kyo.WhatsAppWebhookConfig]] holds it; its raw text is read only through
  * `value`, as the HMAC key.
  *
  * IMPORTANT: `init` checks the text and fails with [[kyo.WhatsAppInvalidTokenException]] when it cannot be an app secret: empty,
  * or holding a character other than printable ASCII without space, such as the newline a paste leaves. Meta documents no alphabet or
  * length for it, so neither is bounded further.
  *
  * Equality is by value. It has no `Schema`, so it cannot be serialized by accident.
  *
  * @see
  *   [[kyo.WhatsAppWebhookConfig]] the webhook config that holds it
  */
final class WhatsAppAppSecret private (val value: String):
    override def equals(other: Any): Boolean = other match
        case that: WhatsAppAppSecret => value == that.value
        case _                       => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = "WhatsAppAppSecret(<redacted>)"
end WhatsAppAppSecret

object WhatsAppAppSecret:

    /** The app secret `value`, or the [[kyo.WhatsAppInvalidTokenException]] saying why the text cannot be one. */
    def init(value: String)(using Frame): Result[WhatsAppInvalidTokenException, WhatsAppAppSecret] =
        Secret.init(WhatsAppInvalidTokenException.Token.AppSecret, value)(new WhatsAppAppSecret(_))

    given CanEqual[WhatsAppAppSecret, WhatsAppAppSecret] = CanEqual.derived
end WhatsAppAppSecret
