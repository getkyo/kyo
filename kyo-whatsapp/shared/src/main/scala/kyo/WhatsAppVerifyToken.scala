package kyo

/** The verify token of the webhook registration handshake: the string you enter in the Meta dashboard, which Meta echoes in
  * `hub.verify_token` when it registers the endpoint.
  *
  * It is a shared secret, since whoever knows it can confirm a registration, so it has a type of its own that cannot be passed where the
  * access token or the app secret is expected, and a `toString` of `WhatsAppVerifyToken(<redacted>)`. `WhatsApp.Webhook.verificationHandler`
  * compares it with the echoed value in constant time.
  *
  * IMPORTANT: `init` fails with [[kyo.WhatsAppInvalidTokenException]] on an empty text, which would accept any handshake. Nothing
  * else is checked: the module never sends the token, it compares what Meta echoes from the dashboard field, and Meta documents no bound
  * for that field.
  *
  * Equality is by value. It has no `Schema`, so it cannot be serialized by accident.
  *
  * @see
  *   [[kyo.WhatsAppWebhookConfig]] the webhook config that holds it
  */
final class WhatsAppVerifyToken private (val value: String):
    override def equals(other: Any): Boolean = other match
        case that: WhatsAppVerifyToken => value == that.value
        case _                         => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = "WhatsAppVerifyToken(<redacted>)"
end WhatsAppVerifyToken

object WhatsAppVerifyToken:

    /** The verify token `value`, or a [[kyo.WhatsAppInvalidTokenException]] when the text is empty. */
    def init(value: String)(using Frame): Result[WhatsAppInvalidTokenException, WhatsAppVerifyToken] =
        if value.isEmpty then
            Result.fail(WhatsAppInvalidTokenException(
                WhatsAppInvalidTokenException.Token.VerifyToken,
                WhatsAppInvalidTokenException.Problem.Empty
            ))
        else Result.succeed(new WhatsAppVerifyToken(value))

    given CanEqual[WhatsAppVerifyToken, WhatsAppVerifyToken] = CanEqual.derived
end WhatsAppVerifyToken
