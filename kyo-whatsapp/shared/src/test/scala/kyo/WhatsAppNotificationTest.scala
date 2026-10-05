package kyo

import kyo.internal.whatsapp.Codec

/** How a webhook POST becomes notifications: one per message and per status, in order, each with its change's metadata, and an
  * `Unknown` for what the module does not model or read. The wire objects themselves are tested against Meta's payloads in
  * `WhatsAppWebhookPayloadTest`, `WhatsAppInboundMessageTest` and `WhatsAppStatusTest`.
  */
class WhatsAppNotificationTest extends BaseWhatsAppTest:

    val metadataJson = """{"display_phone_number":"15550783881","phone_number_id":"106540352242922"}"""
    val metadata     = WhatsAppNotification.Metadata("15550783881", WhatsAppId.PhoneNumberId("106540352242922"))

    def post(changes: String*): String =
        s"""{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[${changes.mkString(",")}]}]}"""

    def messagesChange(items: String): String =
        s"""{"field":"messages","value":{"messaging_product":"whatsapp","metadata":$metadataJson,$items}}"""

    def decode(json: String)(using Frame): Result[WhatsAppDecodeException, Chunk[WhatsAppNotification]] =
        Codec.decodeNotifications(utf8(json))

    val textJson = """{"from":"16505551234","id":"wamid.TEST","timestamp":"1749416383","type":"text","text":{"body":"hi"}}"""
    val text     = WhatsAppInboundMessage.Text(
        WhatsAppId.WaId("16505551234"),
        WhatsAppId.MessageId("wamid.TEST"),
        epoch(1749416383L),
        Absent,
        WhatsAppInboundMessage.Text.Body("hi")
    )
    val statusJson = """{"id":"wamid.STATUS","status":"delivered","timestamp":"1749416383","recipient_id":"16505551234"}"""
    val status     = WhatsAppStatus(
        WhatsAppId.MessageId("wamid.STATUS"),
        WhatsAppStatus.Kind.Delivered,
        epoch(1749416383L),
        WhatsAppId.WaId("16505551234")
    )

    "a message is delivered with its change's metadata and the sender's profile from the change's contacts" in {
        val contact =
            WhatsAppWebhookPayload.Contact(Present(WhatsAppWebhookPayload.Contact.Profile("Sheena Nelson")), WhatsAppId.WaId("16505551234"))
        val json = post(messagesChange(
            s""""contacts":[{"profile":{"name":"Sheena Nelson"},"wa_id":"16505551234"},{"wa_id":"other"}],"messages":[$textJson]"""
        ))
        assert(decode(json) == Result.succeed(Chunk(WhatsAppNotification.Message(metadata, Present(contact), text))))
    }

    "a message whose sender is not among the contacts has no contact" in {
        assert(decode(post(messagesChange(s""""messages":[$textJson]"""))) ==
            Result.succeed(Chunk(WhatsAppNotification.Message(metadata, Absent, text))))
    }

    "a message of an unknown type is still a Message, its contact found by the from its JSON holds" in {
        val unknownJson = """{"from":"16505551234","id":"wamid.U","timestamp":"1","type":"poll","poll":{}}"""
        val json        = post(messagesChange(s""""contacts":[{"wa_id":"16505551234"}],"messages":[$unknownJson]"""))
        assert(decode(json) == Result.succeed(Chunk(WhatsAppNotification.Message(
            metadata,
            Present(WhatsAppWebhookPayload.Contact(Absent, WhatsAppId.WaId("16505551234"))),
            WhatsAppInboundMessage.Unknown("poll", WhatsAppRawJsonTest.of(unknownJson))
        ))))
    }

    "a change's messages and statuses are delivered in order, one notification each" in {
        val json = post(messagesChange(s""""messages":[$textJson,$textJson],"statuses":[$statusJson]"""))
        assert(decode(json) == Result.succeed(Chunk(
            WhatsAppNotification.Message(metadata, Absent, text),
            WhatsAppNotification.Message(metadata, Absent, text),
            WhatsAppNotification.Status(metadata, status)
        )))
    }

    "a message or status that does not decode is Unknown holding it, and the items beside it are delivered" in {
        val brokenMessage = """{"from":"16505551234","id":"wamid.B","timestamp":"1749416383","type":"text"}"""
        val brokenStatus  = """{"id":"wamid.NUMERIC","status":"sent","timestamp":1749416383,"recipient_id":"16505551234"}"""
        val json          = post(messagesChange(s""""messages":[$brokenMessage,$textJson],"statuses":[$brokenStatus,$statusJson]"""))
        assert(decode(json) == Result.succeed(Chunk(
            WhatsAppNotification.Unknown("messages", WhatsAppRawJsonTest.of(brokenMessage), Present(metadata)),
            WhatsAppNotification.Message(metadata, Absent, text),
            WhatsAppNotification.Unknown("messages", WhatsAppRawJsonTest.of(brokenStatus), Present(metadata)),
            WhatsAppNotification.Status(metadata, status)
        )))
    }

    "a change whose metadata does not decode is one Unknown holding the change's value, and the POST's other changes still decode" in {
        val brokenValue =
            s"""{"messaging_product":"whatsapp","metadata":{"display_phone_number":"1","phone_number_id":1},"messages":[$textJson]}"""
        val json = post(s"""{"field":"messages","value":$brokenValue}""", messagesChange(s""""messages":[$textJson]"""))
        assert(decode(json) == Result.succeed(Chunk(
            WhatsAppNotification.Unknown("messages", WhatsAppRawJsonTest.of(brokenValue), Absent),
            WhatsAppNotification.Message(metadata, Absent, text)
        )))
    }

    "a change of a field the module does not model is Unknown holding its value, with the value's metadata when it has one" in {
        val value = s"""{"metadata":$metadataJson,"event":"VERIFIED_ACCOUNT"}"""
        assert(decode(post(s"""{"field":"account_update","value":$value}""")) ==
            Result.succeed(Chunk(WhatsAppNotification.Unknown("account_update", WhatsAppRawJsonTest.of(value), Present(metadata)))))
    }

    "a messages change with neither messages nor statuses is one Unknown holding its value" in {
        val value =
            s"""{"messaging_product":"whatsapp","metadata":$metadataJson,"errors":[{"code":131000,"title":"Something went wrong"}]}"""
        decode(post(s"""{"field":"messages","value":$value}""")) match
            case Result.Success(Chunk(WhatsAppNotification.Unknown("messages", payload, Present(`metadata`)))) =>
                assert(unordered(payload.value) == unordered(value), payload.value)
            case other => fail(s"expected one Unknown holding the value, got $other")
        end match
    }

    "a body that is not the envelope is the decode failure, naming the webhook" in {
        decode("""{"entry":"nope"}""") match
            case Result.Failure(e: WhatsAppDecodeException) =>
                assert(e.method == "webhook" && e.part == WhatsAppDecodeException.Part.Notification)
            case other => fail(s"expected a WhatsAppDecodeException, got $other")
    }

    "a notification has no Schema, with the module's reason" in {
        typeCheckFailure("summon[kyo.Schema[kyo.WhatsAppNotification.Message]]")(
            "WhatsAppNotification has no Schema: it composes Meta's objects; WhatsAppWebhookPayload is the payload's"
        )
    }

    "Metadata is Meta's metadata object" in {
        assert(Json.decode[WhatsAppNotification.Metadata](metadataJson) == Result.succeed(metadata))
        assert(Json.encode(metadata) == metadataJson)
    }

end WhatsAppNotificationTest
