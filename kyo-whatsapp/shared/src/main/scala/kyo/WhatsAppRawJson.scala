package kyo

/** The raw JSON text of a webhook payload kyo-whatsapp delivers without modelling: what
  * [[kyo.WhatsAppNotification.Unknown]] and [[kyo.WhatsAppNotification.Content.Unknown]] hold.
  *
  * A raw payload is the sender's or the business's data: a message's text, a customer's phone
  * number, a template's content. So the text is reachable only through `value`, and `toString`
  * renders its length, never its content, so a logged or printed notification shows no part of it.
  * Values compare by their text and carry no `Schema`.
  */
final class WhatsAppRawJson private (val value: String):
    override def equals(other: Any): Boolean =
        other match
            case that: WhatsAppRawJson => value == that.value
            case _                     => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = s"WhatsAppRawJson(${value.length} characters)"
end WhatsAppRawJson

object WhatsAppRawJson:
    def apply(value: String): WhatsAppRawJson        = new WhatsAppRawJson(value)
    given CanEqual[WhatsAppRawJson, WhatsAppRawJson] = CanEqual.derived
end WhatsAppRawJson
