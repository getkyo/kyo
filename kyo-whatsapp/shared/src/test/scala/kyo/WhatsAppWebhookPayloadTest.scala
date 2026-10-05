package kyo

/** The webhook POST body against Meta's documented payloads (developers.facebook.com/docs/whatsapp/cloud-api/webhooks/payload-examples
  * and the interactive message guides): each decodes to the value and encodes back to the same JSON.
  */
class WhatsAppWebhookPayloadTest extends BaseWhatsAppTest:

    val metadata = WhatsAppNotification.Metadata("15550783881", WhatsAppId.PhoneNumberId("106540352242922"))

    def roundTrips(json: String, expected: WhatsAppWebhookPayload)(using Frame, kyo.test.AssertScope): Unit =
        assert(Json.decode[WhatsAppWebhookPayload](json) == Result.succeed(expected))
        assert(unordered(Json.encode(expected)) == unordered(json))

    def payload(value: WhatsAppWebhookPayload.MessagesValue): WhatsAppWebhookPayload =
        WhatsAppWebhookPayload(
            "whatsapp_business_account",
            Chunk(WhatsAppWebhookPayload.Entry(
                WhatsAppId.WabaId("102290129340398"),
                Chunk(WhatsAppWebhookPayload.Change.Messages(value))
            ))
        )

    "Meta's received-text example" in {
        roundTrips(
            """{"object":"whatsapp_business_account","entry":[{"id":"102290129340398","changes":[{"value":{
              |"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550783881","phone_number_id":"106540352242922"},
              |"contacts":[{"profile":{"name":"Sheena Nelson"},"wa_id":"16505551234"}],
              |"messages":[{"from":"16505551234","id":"wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA=",
              |"timestamp":"1749416383","type":"text","text":{"body":"Does it come in another color?"}}]},
              |"field":"messages"}]}]}""".stripMargin,
            payload(WhatsAppWebhookPayload.MessagesValue(
                Present("whatsapp"),
                metadata,
                Chunk(WhatsAppWebhookPayload.Contact(
                    Present(WhatsAppWebhookPayload.Contact.Profile("Sheena Nelson")),
                    WhatsAppId.WaId("16505551234")
                )),
                Chunk(WhatsAppInboundMessage.Text(
                    WhatsAppId.WaId("16505551234"),
                    WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA="),
                    epoch(1749416383L),
                    Absent,
                    WhatsAppInboundMessage.Text.Body("Does it come in another color?")
                ))
            ))
        )
    }

    "Meta's delivered-status example" in {
        roundTrips(
            """{"object":"whatsapp_business_account","entry":[{"id":"102290129340398","changes":[{"value":{
              |"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550783881","phone_number_id":"106540352242922"},
              |"statuses":[{"id":"wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI3MTE5MjVBOTE3MDk5QUVFM0YA","status":"delivered",
              |"timestamp":"1750263773","recipient_id":"16505551234",
              |"conversation":{"id":"6ceb9d929c9bdc4f90e967a32f8639b4","origin":{"type":"service"}},
              |"pricing":{"billable":true,"pricing_model":"CBP","category":"service"}}]},"field":"messages"}]}]}""".stripMargin,
            payload(WhatsAppWebhookPayload.MessagesValue(
                Present("whatsapp"),
                metadata,
                statuses = Chunk(WhatsAppStatus(
                    WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI3MTE5MjVBOTE3MDk5QUVFM0YA"),
                    WhatsAppStatus.Kind.Delivered,
                    epoch(1750263773L),
                    WhatsAppId.WaId("16505551234"),
                    Present(WhatsAppStatus.Conversation(
                        "6ceb9d929c9bdc4f90e967a32f8639b4",
                        Absent,
                        WhatsAppStatus.Conversation.Origin("service")
                    )),
                    Present(WhatsAppStatus.Pricing(true, "CBP", "service"))
                ))
            ))
        )
    }

    "Meta's reply-button example" in {
        roundTrips(
            """{"object":"whatsapp_business_account","entry":[{"id":"102290129340398","changes":[{"value":{
              |"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550783881","phone_number_id":"106540352242922"},
              |"contacts":[{"profile":{"name":"Pablo Morales"},"wa_id":"16505551234"}],
              |"messages":[{"context":{"from":"15550783881","id":"wamid.HBgLMTY0NjcwNDM1OTUVAgARGBJBM0Y4RUU0RUNFQkFDMjYzQUMA"},
              |"from":"16505551234","id":"wamid.HBgLMTY0NjcwNDM1OTUVAgASGBQzQThBREYwNzc2RDc2QjA1QTIwMgA=","timestamp":"1714510003",
              |"type":"interactive","interactive":{"type":"button_reply","button_reply":{"id":"change-button","title":"Change"}}}]},
              |"field":"messages"}]}]}""".stripMargin,
            payload(WhatsAppWebhookPayload.MessagesValue(
                Present("whatsapp"),
                metadata,
                Chunk(WhatsAppWebhookPayload.Contact(
                    Present(WhatsAppWebhookPayload.Contact.Profile("Pablo Morales")),
                    WhatsAppId.WaId("16505551234")
                )),
                Chunk(WhatsAppInboundMessage.Interactive(
                    WhatsAppId.WaId("16505551234"),
                    WhatsAppId.MessageId("wamid.HBgLMTY0NjcwNDM1OTUVAgASGBQzQThBREYwNzc2RDc2QjA1QTIwMgA="),
                    epoch(1714510003L),
                    Present(WhatsAppInboundMessage.Context(
                        WhatsAppId.WaId("15550783881"),
                        WhatsAppId.MessageId("wamid.HBgLMTY0NjcwNDM1OTUVAgARGBJBM0Y4RUU0RUNFQkFDMjYzQUMA")
                    )),
                    WhatsAppInboundMessage.Interactive.Reply.ButtonReply(
                        WhatsAppInboundMessage.Interactive.Reply.ButtonReply.Body("change-button", "Change")
                    )
                ))
            ))
        )
    }

    "a change of a field the module does not model is Other, holding the change, and writes it back" in {
        val change = """{"field":"account_update","value":{"phone_number":"15550783881","event":"VERIFIED_ACCOUNT"}}"""
        val json   = s"""{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[$change]}]}"""
        roundTrips(
            json,
            WhatsAppWebhookPayload(
                "whatsapp_business_account",
                Chunk(WhatsAppWebhookPayload.Entry(
                    WhatsAppId.WabaId("1"),
                    Chunk(WhatsAppWebhookPayload.Change.Other("account_update", WhatsAppRawJsonTest.of(change)))
                ))
            )
        )
    }

    "a messages change that does not decode is Undecodable holding the change, beside the changes that do" in {
        val broken = """{"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"phone_number_id":1}}}"""
        val json   = s"""{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[$broken]}]}"""
        assert(Json.decode[WhatsAppWebhookPayload](json) == Result.succeed(WhatsAppWebhookPayload(
            "whatsapp_business_account",
            Chunk(WhatsAppWebhookPayload.Entry(
                WhatsAppId.WabaId("1"),
                Chunk(WhatsAppWebhookPayload.ChangeEntry.Undecodable(WhatsAppRawJsonTest.of(broken)))
            ))
        )))
    }

    "a body that is not the envelope does not decode" in {
        assert(Json.decode[WhatsAppWebhookPayload]("""{"entry":"nope"}""").isFailure)
    }

end WhatsAppWebhookPayloadTest
