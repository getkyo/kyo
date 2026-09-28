package kyo

import kyo.internal.whatsapp.Codec

class WhatsAppNotificationTest extends BaseWhatsAppTest:

    val textWebhookJson: String =
        """{
          |  "object": "whatsapp_business_account",
          |  "entry": [
          |    {
          |      "id": "102290129340398",
          |      "changes": [
          |        {
          |          "value": {
          |            "messaging_product": "whatsapp",
          |            "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" },
          |            "contacts": [ { "profile": { "name": "Sheena Nelson" }, "wa_id": "16505551234" } ],
          |            "messages": [
          |              {
          |                "from": "16505551234",
          |                "id": "wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA=",
          |                "timestamp": "1749416383",
          |                "type": "text",
          |                "text": { "body": "Does it come in another color?" }
          |              }
          |            ]
          |          },
          |          "field": "messages"
          |        }
          |      ]
          |    }
          |  ]
          |}""".stripMargin

    val statusWebhookJson: String =
        """{
          |  "object": "whatsapp_business_account",
          |  "entry": [
          |    {
          |      "id": "102290129340398",
          |      "changes": [
          |        {
          |          "value": {
          |            "messaging_product": "whatsapp",
          |            "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" },
          |            "statuses": [
          |              {
          |                "id": "wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI3MTE5MjVBOTE3MDk5QUVFM0YA",
          |                "status": "delivered",
          |                "timestamp": "1750263773",
          |                "recipient_id": "16505551234",
          |                "conversation": { "id": "6ceb9d929c9bdc4f90e967a32f8639b4", "origin": { "type": "service" } },
          |                "pricing": { "billable": true, "pricing_model": "CBP", "category": "service" }
          |              }
          |            ]
          |          },
          |          "field": "messages"
          |        }
          |      ]
          |    }
          |  ]
          |}""".stripMargin

    def makeChange(msgType: String, payloadFields: String): String =
        s"""{
           |  "object": "whatsapp_business_account",
           |  "entry": [{
           |    "id": "1",
           |    "changes": [{
           |      "value": {
           |        "messaging_product": "whatsapp",
           |        "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" },
           |        "messages": [{
           |          "from": "16505551234",
           |          "id": "wamid.TEST",
           |          "timestamp": "1749416383",
           |          "type": "$msgType",
           |          $payloadFields
           |        }]
           |      },
           |      "field": "messages"
           |    }]
           |  }]
           |}""".stripMargin

    def makeStatusChange(statusValue: String, extraFields: String = ""): String =
        s"""{
           |  "object": "whatsapp_business_account",
           |  "entry": [{
           |    "id": "1",
           |    "changes": [{
           |      "value": {
           |        "messaging_product": "whatsapp",
           |        "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" },
           |        "statuses": [{
           |          "id": "wamid.STATUS",
           |          "status": "$statusValue",
           |          "timestamp": "1749416383",
           |          "recipient_id": "16505551234"
           |          $extraFields
           |        }]
           |      },
           |      "field": "messages"
           |    }]
           |  }]
           |}""".stripMargin

    def decode(json: String)(using Frame): Result[WhatsAppDecodeException, Chunk[WhatsAppNotification]] =
        Codec.decodeNotifications(Span.from(json.getBytes("UTF-8")))

    def decoded(notifications: WhatsAppNotification*): Result[WhatsAppDecodeException, Chunk[WhatsAppNotification]] =
        Result.succeed(Chunk.from(notifications))

    val expectedMeta = WhatsAppNotification.Metadata("15550783881", WhatsAppId.PhoneNumberId("106540352242922"))

    def testMessage(
        content: WhatsAppNotification.Content,
        context: Maybe[WhatsAppNotification.Context] = Absent
    ): WhatsAppNotification =
        WhatsAppNotification.InboundMessage(
            metadata = expectedMeta,
            from = WhatsAppId.WaId("16505551234"),
            id = WhatsAppId.MessageId("wamid.TEST"),
            timestamp = epoch(1749416383L),
            content = content,
            context = context
        )

    def testStatus(
        status: WhatsAppNotification.Status,
        errors: Chunk[WhatsAppNotification.DeliveryIssue] = Chunk.empty
    ): WhatsAppNotification =
        WhatsAppNotification.StatusUpdate(
            metadata = expectedMeta,
            id = WhatsAppId.MessageId("wamid.STATUS"),
            status = status,
            timestamp = epoch(1749416383L),
            recipientId = WhatsAppId.WaId("16505551234"),
            errors = errors
        )

    "verbatim inbound text webhook decodes to InboundMessage with sender profile name" in {
        assert(decode(textWebhookJson) == decoded(
            WhatsAppNotification.InboundMessage(
                metadata = expectedMeta,
                from = WhatsAppId.WaId("16505551234"),
                id = WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA="),
                timestamp = epoch(1749416383L),
                content = WhatsAppNotification.Content.Text("Does it come in another color?"),
                senderProfileName = Present("Sheena Nelson")
            )
        ))
    }

    "an image message decodes to Content.Media with its metadata" in {
        val json =
            makeChange("image", """"image": {"id": "MEDIA123", "mime_type": "image/jpeg", "sha256": "abc123", "caption": "nice photo"}""")
        assert(decode(json) == decoded(testMessage(WhatsAppNotification.Content.Media(
            WhatsAppNotification.Content.Media.Kind.Image,
            WhatsAppId.MediaId("MEDIA123"),
            "image/jpeg",
            "abc123",
            Present("nice photo"),
            Absent
        ))))
    }

    "each media message type decodes to its Kind" in {
        import WhatsAppNotification.Content.Media.Kind
        Seq("image" -> Kind.Image, "video" -> Kind.Video, "audio" -> Kind.Audio, "document" -> Kind.Document, "sticker" -> Kind.Sticker)
            .foreach { case (tpe, kind) =>
                val json = makeChange(tpe, s""""$tpe": {"id": "M", "mime_type": "x/y", "sha256": "h"}""")
                assert(
                    decode(json) == decoded(testMessage(WhatsAppNotification.Content.Media(kind, WhatsAppId.MediaId("M"), "x/y", "h"))),
                    s"for $tpe"
                )
            }
        succeed
    }

    "a location message decodes to Content.Location" in {
        val json = makeChange(
            "location",
            """"location": {"latitude": 37.483307, "longitude": 122.148981, "name": "Pablo Morales Residential Park", "address": "Menlo Park, CA, United States"}"""
        )
        assert(decode(json) == decoded(testMessage(WhatsAppNotification.Content.Location(
            37.483307,
            122.148981,
            Present("Pablo Morales Residential Park"),
            Present("Menlo Park, CA, United States")
        ))))
    }

    "a reaction message decodes to Content.Reaction" in {
        val json = makeChange("reaction", """"reaction": {"message_id": "wamid.ORIG", "emoji": "😀"}""")
        assert(decode(json) == decoded(testMessage(WhatsAppNotification.Content.Reaction(WhatsAppId.MessageId("wamid.ORIG"), "😀"))))
    }

    "button and interactive replies decode to the correct Content cases" in {
        val buttonJson = makeChange("button", """"button": {"payload": "PAYLOAD_VALUE", "text": "Click me"}""")
        assert(decode(buttonJson) == decoded(testMessage(WhatsAppNotification.Content.Button("PAYLOAD_VALUE", "Click me"))))

        val btnReplyJson =
            makeChange("interactive", """"interactive": {"type": "button_reply", "button_reply": {"id": "BTN1", "title": "Yes"}}""")
        assert(decode(btnReplyJson) == decoded(testMessage(WhatsAppNotification.Content.ButtonReply("BTN1", "Yes"))))

        val listReplyJson = makeChange(
            "interactive",
            """"interactive": {"type": "list_reply", "list_reply": {"id": "ITEM1", "title": "Option A", "description": "First option"}}"""
        )
        assert(decode(listReplyJson) == decoded(
            testMessage(WhatsAppNotification.Content.ListReply("ITEM1", "Option A", Present("First option")))
        ))
    }

    "contacts, order, and system messages decode to the correct Content cases" in {
        val contactsJson = makeChange(
            "contacts",
            """"contacts": [{"name": {"formatted_name": "Jane Smith"}, "phones": [{"phone": "+1234567890", "type": "CELL"}]}]"""
        )
        assert(decode(contactsJson) == decoded(testMessage(WhatsAppNotification.Content.Contacts(Chunk(
            WhatsAppContact(
                name = WhatsAppContact.Name("Jane Smith"),
                phones = Chunk(WhatsAppContact.Phone(Present("+1234567890"), Present("CELL")))
            )
        )))))

        val orderJson = makeChange(
            "order",
            """"order": {"catalog_id": "CAT123", "product_items": [{"product_retailer_id": "PROD1"}, {"product_retailer_id": "PROD2"}]}"""
        )
        assert(decode(orderJson) == decoded(testMessage(WhatsAppNotification.Content.Order("CAT123", Chunk("PROD1", "PROD2")))))

        val systemJson = makeChange("system", """"system": {"body": "User changed their number"}""")
        assert(decode(systemJson) == decoded(testMessage(WhatsAppNotification.Content.System("User changed their number"))))
    }

    "a message with a context decodes the reply context" in {
        val json = makeChange("text", """"text": {"body": "reply"}, "context": {"from": "16505551234", "id": "wamid.ORIG123"}""")
        assert(decode(json) == decoded(testMessage(
            WhatsAppNotification.Content.Text("reply"),
            Present(WhatsAppNotification.Context(WhatsAppId.WaId("16505551234"), WhatsAppId.MessageId("wamid.ORIG123")))
        )))
    }

    "verbatim status webhook decodes to StatusUpdate" in {
        assert(decode(statusWebhookJson) == decoded(
            WhatsAppNotification.StatusUpdate(
                metadata = expectedMeta,
                id = WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI3MTE5MjVBOTE3MDk5QUVFM0YA"),
                status = WhatsAppNotification.Status.Delivered,
                timestamp = epoch(1750263773L),
                recipientId = WhatsAppId.WaId("16505551234"),
                conversation = Present(WhatsAppNotification.Conversation("6ceb9d929c9bdc4f90e967a32f8639b4", Absent, "service")),
                pricing = Present(WhatsAppNotification.Pricing(true, "CBP", "service", Absent)),
                errors = Chunk.empty
            )
        ))
    }

    "a sent status's conversation expiration_timestamp, a string in Meta's status webhook reference, decodes to its Instant" in {
        val conversation =
            """, "conversation": { "id": "CONVERSATION_ID", "expiration_timestamp": "1750116480", "origin": { "type": "utility" } }"""
        assert(decode(makeStatusChange("sent", conversation)) == decoded(
            WhatsAppNotification.StatusUpdate(
                metadata = expectedMeta,
                id = WhatsAppId.MessageId("wamid.STATUS"),
                status = WhatsAppNotification.Status.Sent,
                timestamp = epoch(1749416383L),
                recipientId = WhatsAppId.WaId("16505551234"),
                conversation = Present(WhatsAppNotification.Conversation("CONVERSATION_ID", Present(epoch(1750116480L)), "utility")),
                errors = Chunk.empty
            )
        ))
    }

    "an item that does not decode arrives as Unknown with its JSON, and every other notification of the POST is delivered" in {
        val json =
            """{
              |  "object": "whatsapp_business_account",
              |  "entry": [
              |    { "id": "1", "changes": [{ "field": "messages", "value": {
              |      "messaging_product": "whatsapp",
              |      "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" },
              |      "messages": [{ "from": "16505551234", "id": "wamid.TEST", "timestamp": "1749416383", "type": "text", "text": { "body": "hi" } }]
              |    }}]},
              |    { "id": "2", "changes": [{ "field": "messages", "value": {
              |      "messaging_product": "whatsapp",
              |      "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" },
              |      "statuses": [
              |        { "id": "wamid.NUMERIC", "status": "sent", "timestamp": "1749416383", "recipient_id": "16505551234",
              |          "conversation": { "id": "CONVERSATION_ID", "expiration_timestamp": 1750116480, "origin": { "type": "utility" } } },
              |        { "id": "wamid.STATUS", "status": "delivered", "timestamp": "1749416383", "recipient_id": "16505551234" }
              |      ]
              |    }}]}
              |  ]
              |}""".stripMargin
        decode(json) match
            case Result.Success(Chunk(text, unknown: WhatsAppNotification.Unknown, status)) =>
                assert(text == testMessage(WhatsAppNotification.Content.Text("hi")))
                assert(unknown.`type` == "messages" && unknown.metadata == Present(expectedMeta))
                assert(unknown.payload.value.contains("wamid.NUMERIC"), unknown.payload.value)
                assert(status == testStatus(WhatsAppNotification.Status.Delivered))
            case other => fail(s"expected the text, the undecodable status as Unknown, and the delivered status, got: $other")
        end match
    }

    "a change whose metadata does not decode is one Unknown holding the change, and the POST's other changes still decode" in {
        val json =
            """{
              |  "object": "whatsapp_business_account",
              |  "entry": [
              |    { "id": "1", "changes": [{ "field": "messages", "value": {
              |      "messaging_product": "whatsapp",
              |      "metadata": { "display_phone_number": "15550783881", "phone_number_id": 106540352242922 },
              |      "messages": [{ "from": "16505551234", "id": "wamid.BROKEN", "timestamp": "1749416383", "type": "text", "text": { "body": "lost?" } }]
              |    }}]},
              |    { "id": "2", "changes": [{ "field": "messages", "value": {
              |      "messaging_product": "whatsapp",
              |      "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" },
              |      "messages": [{ "from": "16505551234", "id": "wamid.TEST", "timestamp": "1749416383", "type": "text", "text": { "body": "hi" } }]
              |    }}]}
              |  ]
              |}""".stripMargin
        decode(json) match
            case Result.Success(Chunk(unknown: WhatsAppNotification.Unknown, text)) =>
                assert(unknown.`type` == "messages" && unknown.metadata == Absent)
                assert(
                    unknown.payload.value.contains("wamid.BROKEN") && unknown.payload.value.contains("106540352242922"),
                    unknown.payload.value
                )
                assert(text == testMessage(WhatsAppNotification.Content.Text("hi")))
            case other => fail(s"expected the broken change as one Unknown, then the text, got: $other")
        end match
    }

    "each documented status value decodes to its typed case" in {
        Seq("sent", "delivered", "read", "failed", "deleted").zip(
            Seq(
                WhatsAppNotification.Status.Sent,
                WhatsAppNotification.Status.Delivered,
                WhatsAppNotification.Status.Read,
                WhatsAppNotification.Status.Failed,
                WhatsAppNotification.Status.Deleted
            )
        ).foreach { case (statusStr, expected) =>
            assert(decode(makeStatusChange(statusStr)) == decoded(testStatus(expected)), s"for $statusStr")
        }
    }

    "a status error with only a message takes it as the title, and one with neither has no title" in {
        val json = makeStatusChange(
            "failed",
            """, "errors": [{"code": 131026, "message": "Message undeliverable"}, {"code": 131000}]"""
        )
        assert(decode(json) == decoded(testStatus(
            WhatsAppNotification.Status.Failed,
            Chunk(
                WhatsAppNotification.DeliveryIssue(131026, Present("Message undeliverable")),
                WhatsAppNotification.DeliveryIssue(131000)
            )
        )))
    }

    "a failed status exactly as Meta's webhook reference documents it decodes its delivery errors" in {
        val json =
            """{
              |  "object": "whatsapp_business_account",
              |  "entry": [
              |    {
              |      "id": "102290129340398",
              |      "changes": [
              |        {
              |          "value": {
              |            "messaging_product": "whatsapp",
              |            "metadata": {
              |              "display_phone_number": "15550783881",
              |              "phone_number_id": "106540352242922"
              |            },
              |            "statuses": [
              |              {
              |                "id": "wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI0QUQ2MjA4NEYyRkExNjMyREUA",
              |                "status": "failed",
              |                "timestamp": "1751142888",
              |                "recipient_id": "16505551234",
              |                "errors": [
              |                  {
              |                    "code": 131049,
              |                    "title": "This message was not delivered to maintain healthy ecosystem engagement.",
              |                    "message": "This message was not delivered to maintain healthy ecosystem engagement.",
              |                    "error_data": {
              |                      "details": "In order to maintain a healthy ecosystem engagement, the message failed to be delivered."
              |                    },
              |                    "href": "/documentation/business-messaging/whatsapp/support/error-codes"
              |                  }
              |                ]
              |              }
              |            ]
              |          },
              |          "field": "messages"
              |        }
              |      ]
              |    }
              |  ]
              |}""".stripMargin
        assert(decode(json) == decoded(
            WhatsAppNotification.StatusUpdate(
                metadata = expectedMeta,
                id = WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI0QUQ2MjA4NEYyRkExNjMyREUA"),
                status = WhatsAppNotification.Status.Failed,
                timestamp = epoch(1751142888L),
                recipientId = WhatsAppId.WaId("16505551234"),
                errors = Chunk(WhatsAppNotification.DeliveryIssue(
                    131049,
                    Present("This message was not delivered to maintain healthy ecosystem engagement."),
                    Present("In order to maintain a healthy ecosystem engagement, the message failed to be delivered."),
                    Present("/documentation/business-messaging/whatsapp/support/error-codes")
                ))
            )
        ))
    }

    "an inbound audio message with voice:true decodes voice field" in {
        val json = makeChange(
            "audio",
            """"audio": {"id": "AUDIO1", "mime_type": "audio/ogg", "sha256": "abc", "voice": true}"""
        )
        assert(decode(json) == decoded(testMessage(WhatsAppNotification.Content.Media(
            WhatsAppNotification.Content.Media.Kind.Audio,
            WhatsAppId.MediaId("AUDIO1"),
            "audio/ogg",
            "abc",
            Absent,
            Absent,
            Present(true)
        ))))
    }

    "an unknown message type decodes to Content.Unknown holding the whole message, its unmodelled object included" in {
        val json = makeChange("future_unknown_type", """"future_unknown_type": {"some_field": "value"}""")
        assert(decode(json) == decoded(testMessage(WhatsAppNotification.Content.Unknown(
            "future_unknown_type",
            WhatsAppRawJson(
                """{"from":"16505551234","id":"wamid.TEST","timestamp":"1749416383","type":"future_unknown_type","future_unknown_type":{"some_field":"value"}}"""
            )
        ))))
    }

    "a notification has no Schema, with the module's reason" in {
        typeCheckFailure("summon[kyo.Schema[kyo.WhatsAppNotification.InboundMessage]]")(
            "WhatsAppNotification has no Schema: kyo-whatsapp decodes Meta's payloads itself"
        )
    }

    "an unknown status decodes to Status.Other, NOT an Abort" in {
        assert(decode(makeStatusChange("future_status")) == decoded(testStatus(WhatsAppNotification.Status.Other("future_status"))))
    }

end WhatsAppNotificationTest
