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

    def decodeFirst(json: String)(using Frame): WhatsAppNotification =
        Codec.decodeNotifications(Span.from(json.getBytes("UTF-8"))) match
            case Result.Success(chunk) => chunk.head
            case other                 => throw new RuntimeException(s"unexpected: $other")

    val expectedMeta = WhatsAppNotification.Metadata("15550783881", WhatsAppId.PhoneNumberId("106540352242922"))

    "verbatim inbound text webhook decodes to InboundMessage with sender profile name" in {
        Codec.decodeNotifications(Span.from(textWebhookJson.getBytes("UTF-8"))) match
            case Result.Success(chunk) =>
                assert(chunk.size == 1)
                chunk.head match
                    case msg: WhatsAppNotification.InboundMessage =>
                        assert(msg.metadata == expectedMeta)
                        assert(msg.from == WhatsAppId.WaId("16505551234"))
                        assert(msg.id == WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA="))
                        assert(msg.timestamp == 1749416383L)
                        assert(msg.content == WhatsAppNotification.Content.Text("Does it come in another color?"))
                        assert(msg.senderProfileName == Present("Sheena Nelson"))
                    case other =>
                        assert(false, s"expected InboundMessage, got: $other")
                end match
            case other =>
                assert(false, s"expected Success, got: $other")
    }

    "an image message decodes to Content.Media with its metadata" in {
        val json =
            makeChange("image", """"image": {"id": "MEDIA123", "mime_type": "image/jpeg", "sha256": "abc123", "caption": "nice photo"}""")
        val n = decodeFirst(json)
        n match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.content == WhatsAppNotification.Content.Media(
                    WhatsAppMedia.MediaType.Other("image"),
                    WhatsAppId.MediaId("MEDIA123"),
                    "image/jpeg",
                    "abc123",
                    Present("nice photo"),
                    Absent
                ))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match
    }

    "a location message decodes to Content.Location" in {
        val json = makeChange(
            "location",
            """"location": {"latitude": 37.483307, "longitude": 122.148981, "name": "Pablo Morales Residential Park", "address": "Menlo Park, CA, United States"}"""
        )
        val n = decodeFirst(json)
        n match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.content == WhatsAppNotification.Content.Location(
                    37.483307,
                    122.148981,
                    Present("Pablo Morales Residential Park"),
                    Present("Menlo Park, CA, United States")
                ))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match
    }

    "a reaction message decodes to Content.Reaction" in {
        val json = makeChange("reaction", """"reaction": {"message_id": "wamid.ORIG", "emoji": "😀"}""")
        val n    = decodeFirst(json)
        n match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.content == WhatsAppNotification.Content.Reaction(WhatsAppId.MessageId("wamid.ORIG"), "😀"))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match
    }

    "button and interactive replies decode to the correct Content cases" in {
        val buttonJson = makeChange("button", """"button": {"payload": "PAYLOAD_VALUE", "text": "Click me"}""")
        val buttonN    = decodeFirst(buttonJson)
        buttonN match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.content == WhatsAppNotification.Content.Button("PAYLOAD_VALUE", "Click me"))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match

        val btnReplyJson =
            makeChange("interactive", """"interactive": {"type": "button_reply", "button_reply": {"id": "BTN1", "title": "Yes"}}""")
        val btnReplyN = decodeFirst(btnReplyJson)
        btnReplyN match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.content == WhatsAppNotification.Content.ButtonReply("BTN1", "Yes"))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match

        val listReplyJson = makeChange(
            "interactive",
            """"interactive": {"type": "list_reply", "list_reply": {"id": "ITEM1", "title": "Option A", "description": "First option"}}"""
        )
        val listReplyN = decodeFirst(listReplyJson)
        listReplyN match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.content == WhatsAppNotification.Content.ListReply("ITEM1", "Option A", Present("First option")))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match
    }

    "contacts, order, and system messages decode to the correct Content cases" in {
        val contactsJson = makeChange(
            "contacts",
            """"contacts": [{"name": {"formatted_name": "Jane Smith"}, "phones": [{"phone": "+1234567890", "type": "CELL"}]}]"""
        )
        val contactsN = decodeFirst(contactsJson)
        contactsN match
            case msg: WhatsAppNotification.InboundMessage =>
                msg.content match
                    case WhatsAppNotification.Content.Contacts(cs) =>
                        assert(cs.size == 1)
                        assert(cs.head.name.formattedName == "Jane Smith")
                    case other => assert(false, s"expected Contacts, got: $other")
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match

        val orderJson = makeChange(
            "order",
            """"order": {"catalog_id": "CAT123", "product_items": [{"product_retailer_id": "PROD1"}, {"product_retailer_id": "PROD2"}]}"""
        )
        val orderN = decodeFirst(orderJson)
        orderN match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.content == WhatsAppNotification.Content.Order("CAT123", Chunk("PROD1", "PROD2")))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match

        val systemJson = makeChange("system", """"system": {"body": "User changed their number"}""")
        val systemN    = decodeFirst(systemJson)
        systemN match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.content == WhatsAppNotification.Content.System("User changed their number"))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match
    }

    "a message with a context decodes the reply context" in {
        val json = makeChange("text", """"text": {"body": "reply"}, "context": {"from": "16505551234", "id": "wamid.ORIG123"}""")
        val n    = decodeFirst(json)
        n match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.context == Present(WhatsAppNotification.Context(
                    WhatsAppId.WaId("16505551234"),
                    WhatsAppId.MessageId("wamid.ORIG123")
                )))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match
    }

    "verbatim status webhook decodes to StatusUpdate" in {
        Codec.decodeNotifications(Span.from(statusWebhookJson.getBytes("UTF-8"))) match
            case Result.Success(chunk) =>
                assert(chunk.size == 1)
                chunk.head match
                    case su: WhatsAppNotification.StatusUpdate =>
                        assert(su.metadata == expectedMeta)
                        assert(su.id == WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI3MTE5MjVBOTE3MDk5QUVFM0YA"))
                        assert(su.status == WhatsAppNotification.Status.Delivered)
                        assert(su.timestamp == 1750263773L)
                        assert(su.recipientId == WhatsAppId.WaId("16505551234"))
                        assert(su.conversation == Present(WhatsAppNotification.Conversation(
                            "6ceb9d929c9bdc4f90e967a32f8639b4",
                            Absent,
                            "service"
                        )))
                        assert(su.pricing == Present(WhatsAppNotification.Pricing(true, "CBP", "service", Absent)))
                        assert(su.errors == Chunk.empty)
                    case other =>
                        assert(false, s"expected StatusUpdate, got: $other")
                end match
            case other =>
                assert(false, s"expected Success, got: $other")
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
            val json = makeStatusChange(statusStr)
            val n    = decodeFirst(json)
            n match
                case su: WhatsAppNotification.StatusUpdate => assert(su.status == expected, s"for $statusStr")
                case other                                 => assert(false, s"expected StatusUpdate for $statusStr, got: $other")
        }
    }

    "a failed status carries the Cloud errors" in {
        val json = makeStatusChange(
            "failed",
            """, "errors": [{"code": 131026, "type": "OAuthException", "message": "WhatsAppMessage undeliverable"}]"""
        )
        val n = decodeFirst(json)
        n match
            case su: WhatsAppNotification.StatusUpdate =>
                assert(su.status == WhatsAppNotification.Status.Failed)
                assert(su.errors.size == 1)
                assert(su.errors.head.code == 131026)
            case other => assert(false, s"expected StatusUpdate, got: $other")
        end match
    }

    "an inbound audio message with voice:true decodes voice field" in {
        val json = makeChange(
            "audio",
            """"audio": {"id": "AUDIO1", "mime_type": "audio/ogg", "sha256": "abc", "voice": true}"""
        )
        val n = decodeFirst(json)
        n match
            case msg: WhatsAppNotification.InboundMessage =>
                assert(msg.content == WhatsAppNotification.Content.Media(
                    WhatsAppMedia.MediaType.Other("audio"),
                    WhatsAppId.MediaId("AUDIO1"),
                    "audio/ogg",
                    "abc",
                    Absent,
                    Absent,
                    Present(true)
                ))
            case other => assert(false, s"expected InboundMessage, got: $other")
        end match
    }

    "an unknown message type decodes to Content.Unknown, NOT an Abort" in {
        val json = makeChange("future_unknown_type", """"future_unknown_type": {"some_field": "value"}""")
        Codec.decodeNotifications(Span.from(json.getBytes("UTF-8"))) match
            case Result.Success(chunk) =>
                assert(chunk.size == 1)
                chunk.head match
                    case msg: WhatsAppNotification.InboundMessage =>
                        msg.content match
                            case WhatsAppNotification.Content.Unknown("future_unknown_type", raw) =>
                                assert(raw.nonEmpty)
                            case other => assert(false, s"expected Unknown, got: $other")
                    case other => assert(false, s"expected InboundMessage, got: $other")
                end match
            case other =>
                assert(false, s"expected Success (no Abort), got: $other")
        end match
    }

    "an unknown status decodes to Status.Other, NOT an Abort" in {
        val json = makeStatusChange("future_status")
        Codec.decodeNotifications(Span.from(json.getBytes("UTF-8"))) match
            case Result.Success(chunk) =>
                assert(chunk.size == 1)
                chunk.head match
                    case su: WhatsAppNotification.StatusUpdate =>
                        assert(su.status == WhatsAppNotification.Status.Other("future_status"))
                    case other => assert(false, s"expected StatusUpdate, got: $other")
                end match
            case other =>
                assert(false, s"expected Success (no Abort), got: $other")
        end match
    }

end WhatsAppNotificationTest
