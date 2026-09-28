package kyo

import kyo.internal.whatsapp.Secret

/** The Meta app secret, the key of the HMAC-SHA256 that signs every webhook POST in its `X-Hub-Signature-256` header.
  *
  * A type of its own so it cannot be passed where the access token or the verify token is expected, and so no value that holds it can
  * print it: `toString` is `WhatsAppAppSecret(<redacted>)`. [[kyo.WhatsAppWebhookConfig]] holds it; its raw text is read only through
  * `value`, as the HMAC key.
  *
  * IMPORTANT: construction checks the text and panics with [[kyo.WhatsAppInvalidTokenException]] when it cannot be an app secret: empty,
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

    /** Builds an app secret, panicking with a [[kyo.WhatsAppInvalidTokenException]] when the text cannot be one. */
    def apply(value: String)(using Frame): WhatsAppAppSecret =
        Secret.check(WhatsAppInvalidTokenException.Token.AppSecret, value)
        new WhatsAppAppSecret(value)

    given CanEqual[WhatsAppAppSecret, WhatsAppAppSecret] = CanEqual.derived
end WhatsAppAppSecret
