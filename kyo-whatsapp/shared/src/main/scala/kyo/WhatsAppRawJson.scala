package kyo

/** The raw JSON of a webhook payload kyo-whatsapp delivers without modelling: what
  * [[kyo.WhatsAppNotification.Unknown]], [[kyo.WhatsAppInboundMessage.Unknown]] and the `Undecodable` entries hold.
  *
  * `json` is the payload as it arrived and `value` its JSON text. Its `Schema` is that JSON, so an
  * `Unknown` encodes back to what Meta sent.
  *
  * A raw payload is the sender's or the business's data: a message's text, a customer's phone
  * number, a template's content. So `toString` renders the text's length, never its content, and a
  * logged or printed notification shows no part of it. Values compare by their JSON.
  */
final class WhatsAppRawJson private (val json: Structure.Value, val value: String):
    override def equals(other: Any): Boolean =
        other match
            case that: WhatsAppRawJson => json == that.json
            case _                     => false
    override def hashCode: Int    = json.hashCode
    override def toString: String = s"WhatsAppRawJson(${value.length} characters)"
end WhatsAppRawJson

object WhatsAppRawJson:
    def apply(json: Structure.Value)(using Frame): WhatsAppRawJson = new WhatsAppRawJson(json, Json.encode(json))

    given Schema[WhatsAppRawJson] = Schema.init[WhatsAppRawJson](
        writeFn = (raw, writer) => Structure.Value.valueSchema.serializeWrite(raw.json, writer),
        readFn = reader => WhatsAppRawJson(Structure.Value.valueSchema.serializeRead(reader))(using reader.frame)
    )

    given CanEqual[WhatsAppRawJson, WhatsAppRawJson] = CanEqual.derived
end WhatsAppRawJson
