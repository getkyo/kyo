package kyo.whatsapp

import kyo.*

class WebhookDecodeTest extends BaseWhatsAppTest:

    val multiEntryJson: String =
        """{
          |  "object": "whatsapp_business_account",
          |  "entry": [
          |    {
          |      "id": "WABA1",
          |      "changes": [
          |        {
          |          "value": {
          |            "messaging_product": "whatsapp",
          |            "metadata": { "display_phone_number": "15550783881", "phone_number_id": "111" },
          |            "messages": [{"from": "16505551234", "id": "wamid.A", "timestamp": "1749000001", "type": "text", "text": {"body": "Hello"}}]
          |          },
          |          "field": "messages"
          |        },
          |        {
          |          "value": {
          |            "messaging_product": "whatsapp",
          |            "metadata": { "display_phone_number": "15550783881", "phone_number_id": "111" },
          |            "statuses": [{"id": "wamid.B", "status": "delivered", "timestamp": "1749000002", "recipient_id": "16505551234"}]
          |          },
          |          "field": "messages"
          |        }
          |      ]
          |    },
          |    {
          |      "id": "WABA2",
          |      "changes": [
          |        {
          |          "value": {
          |            "messaging_product": "whatsapp",
          |            "metadata": { "display_phone_number": "15550783882", "phone_number_id": "222" },
          |            "messages": [{"from": "16505551235", "id": "wamid.C", "timestamp": "1749000003", "type": "text", "text": {"body": "World"}}]
          |          },
          |          "field": "messages"
          |        },
          |        {
          |          "value": {
          |            "messaging_product": "whatsapp",
          |            "metadata": { "display_phone_number": "15550783882", "phone_number_id": "222" },
          |            "statuses": [{"id": "wamid.D", "status": "read", "timestamp": "1749000004", "recipient_id": "16505551235"}]
          |          },
          |          "field": "messages"
          |        }
          |      ]
          |    }
          |  ]
          |}""".stripMargin

    val unknownChangeJson: String =
        """{
          |  "object": "whatsapp_business_account",
          |  "entry": [{
          |    "id": "1",
          |    "changes": [{
          |      "value": {
          |        "messaging_product": "whatsapp",
          |        "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" }
          |      },
          |      "field": "account_update"
          |    }]
          |  }]
          |}""".stripMargin

    val truncatedJson: String = """{"object":"whatsapp_business_account","entry":"""

    val emptyEntryJson: String = """{"object":"whatsapp_business_account","entry":[]}"""

    "multiple entry[] and changes[] in one POST all decode" in {
        Abort.run[WhatsAppError.DecodeError](
            Webhook.decode(Span.from(multiEntryJson.getBytes("UTF-8")))
        ).map { result =>
            result match
                case Result.Success(notifications) =>
                    assert(notifications.size == 4)
                    assert(notifications(0).isInstanceOf[Notification.InboundMessage])
                    assert(notifications(1).isInstanceOf[Notification.StatusUpdate])
                    assert(notifications(2).isInstanceOf[Notification.InboundMessage])
                    assert(notifications(3).isInstanceOf[Notification.StatusUpdate])
                    val msg0 = notifications(0).asInstanceOf[Notification.InboundMessage]
                    assert(msg0.content == Notification.Content.Text("Hello"))
                    val su1 = notifications(1).asInstanceOf[Notification.StatusUpdate]
                    assert(su1.status == Notification.Status.Delivered)
                case other =>
                    assert(false, s"expected Success with 4 notifications, got: $other")
        }
    }

    "an unknown change field decodes to Unsupported, NOT an Abort" in {
        Abort.run[WhatsAppError.DecodeError](
            Webhook.decode(Span.from(unknownChangeJson.getBytes("UTF-8")))
        ).map { result =>
            result match
                case Result.Success(chunk) =>
                    assert(chunk.size == 1)
                    chunk.head match
                        case Notification.Unsupported(_, "account_update", raw) =>
                            assert(raw.nonEmpty)
                        case other => assert(false, s"expected Unsupported, got: $other")
                    end match
                case other =>
                    assert(false, s"expected Success (no Abort), got: $other")
        }
    }

    "a structurally-unparseable envelope aborts with DecodeError" in {
        Abort.run[WhatsAppError.DecodeError](
            Webhook.decode(Span.from(truncatedJson.getBytes("UTF-8")))
        ).map { result =>
            result match
                case Result.Failure(_: WhatsAppError.DecodeError) => assert(true)
                case other                                        => assert(false, s"expected DecodeError, got: $other")
        }
    }

    "a valid-but-empty entry array decodes to an empty Chunk" in {
        Abort.run[WhatsAppError.DecodeError](
            Webhook.decode(Span.from(emptyEntryJson.getBytes("UTF-8")))
        ).map { result =>
            result match
                case Result.Success(chunk) => assert(chunk.isEmpty)
                case other                 => assert(false, s"expected Success(empty), got: $other")
        }
    }

    "decode is Abort[DecodeError] only, never Async" in {
        typeCheck("""
            import kyo.*
            import kyo.whatsapp.*
            val body: Span[Byte] = Span.empty
            val _: Chunk[Notification] < Abort[WhatsAppError.DecodeError] = Webhook.decode(body)
        """)
    }

end WebhookDecodeTest
