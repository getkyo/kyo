package kyo

class WhatsAppWebhookDecodeTest extends BaseWhatsAppTest:

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
    val malformedJson: String = truncatedJson + "}"

    val emptyEntryJson: String = """{"object":"whatsapp_business_account","entry":[]}"""

    def decode(json: String)(using Frame): Result[WhatsAppWebhookDecodeFailure, Chunk[WhatsAppNotification]] < Any =
        Abort.run(WhatsApp.Webhook.decode(utf8(json)))

    def notificationFailure(failure: WhatsAppDecodeException.Failure, path: Chunk[String], position: Maybe[Int] = Absent)(using
        Frame
    ): Result[WhatsAppWebhookDecodeFailure, Nothing] =
        Result.fail(WhatsAppDecodeException("webhook", WhatsAppDecodeException.Part.Notification, failure, path, position))

    def meta(display: String, phoneNumberId: String): WhatsAppNotification.Metadata =
        WhatsAppNotification.Metadata(display, WhatsAppId.PhoneNumberId(phoneNumberId))

    def text(metadata: WhatsAppNotification.Metadata, from: String, id: String, timestamp: Long, body: String): WhatsAppNotification =
        WhatsAppNotification.Message(
            metadata,
            Absent,
            WhatsAppInboundMessage.Text(
                WhatsAppId.WaId(from),
                WhatsAppId.MessageId(id),
                epoch(timestamp),
                Absent,
                WhatsAppInboundMessage.Text.Body(body)
            )
        )

    def status(
        metadata: WhatsAppNotification.Metadata,
        id: String,
        kind: WhatsAppStatus.Kind,
        timestamp: Long,
        recipient: String
    ): WhatsAppNotification =
        WhatsAppNotification.Status(metadata, WhatsAppStatus(WhatsAppId.MessageId(id), kind, epoch(timestamp), WhatsAppId.WaId(recipient)))

    "multiple entry[] and changes[] in one POST all decode" in {
        val m1 = meta("15550783881", "111")
        val m2 = meta("15550783882", "222")
        decode(multiEntryJson).map { result =>
            assert(result == Result.succeed(Chunk[WhatsAppNotification](
                text(m1, "16505551234", "wamid.A", 1749000001L, "Hello"),
                status(m1, "wamid.B", WhatsAppStatus.Kind.Delivered, 1749000002L, "16505551234"),
                text(m2, "16505551235", "wamid.C", 1749000003L, "World"),
                status(m2, "wamid.D", WhatsAppStatus.Kind.Read, 1749000004L, "16505551235")
            )))
        }
    }

    "an unknown change field decodes to Unknown, NOT an Abort" in {
        decode(unknownChangeJson).map { result =>
            assert(result == Result.succeed(Chunk[WhatsAppNotification](
                WhatsAppNotification.Unknown(
                    "account_update",
                    WhatsAppRawJsonTest.of(
                        """{"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550783881","phone_number_id":"106540352242922"}}"""
                    ),
                    Present(meta("15550783881", "106540352242922"))
                )
            )))
        }
    }

    val noMetadataMessagesJson: String =
        """{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"value":{"messaging_product":"whatsapp","messages":[{"from":"16505551234","id":"wamid.A","timestamp":"1749000001","type":"text","text":{"body":"Hello"}}]},"field":"messages"}]}]}"""

    val noMetadataTemplateJson: String =
        """{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"value":{"event":"APPROVED","message_template_id":7},"field":"message_template_status_update"}]}]}"""

    /** The field and metadata of each decoded notification, `Absent` for one that is not `Unknown`. */
    def unknownShape(result: Result[WhatsAppWebhookDecodeFailure, Chunk[WhatsAppNotification]])
        : Result[WhatsAppWebhookDecodeFailure, Chunk[Maybe[(String, Maybe[WhatsAppNotification.Metadata])]]] =
        result.map(_.map {
            case u: WhatsAppNotification.Unknown => Present((u.`type`, u.metadata))
            case _                               => Absent
        })

    "a messages change without metadata is Unknown with no metadata, not a message with an empty phone number id" in {
        decode(noMetadataMessagesJson).map { result =>
            assert(unknownShape(result) == Result.succeed(Chunk(Present(("messages", Absent)))))
        }
    }

    "a change field that carries no metadata is Unknown with no metadata" in {
        decode(noMetadataTemplateJson).map { result =>
            assert(unknownShape(result) == Result.succeed(Chunk(Present(("message_template_status_update", Absent)))))
        }
    }

    "an Unknown change holds its whole value, the keys the module does not model included" in {
        decode(noMetadataTemplateJson).map { result =>
            assert(result == Result.succeed(Chunk[WhatsAppNotification](WhatsAppNotification.Unknown(
                "message_template_status_update",
                WhatsAppRawJsonTest.of("""{"event":"APPROVED","message_template_id":7}""")
            ))))
        }
    }

    "an Unknown change and an unknown message render the size of their raw value, never its content" in {
        val planted = "PLANTED-CHANGE-TEXT"
        val raw     = WhatsAppRawJsonTest.of(s"""{"note":"$planted"}""")
        assert(!WhatsAppNotification.Unknown("account_update", raw).toString.contains(planted))
        assert(!WhatsAppInboundMessage.Unknown("poll", raw).toString.contains(planted))
    }

    "a structurally-unparseable envelope aborts with a Parse decode failure of the notification" in {
        decode(malformedJson).map { result =>
            assert(result == notificationFailure(WhatsAppDecodeException.Failure.Parse, Chunk("entry"), Present(46)))
        }
    }

    "an envelope cut off before its end aborts with a TruncatedInput decode failure of the notification" in {
        decode(truncatedJson).map { result =>
            assert(result == notificationFailure(WhatsAppDecodeException.Failure.TruncatedInput, Chunk.empty))
        }
    }

    "a timestamp with a sign, non-ASCII digits, or past the latest time a Duration holds makes its item Unknown" in {
        val stamps = Chunk("+1749000001", "-1", "١٧٤٩", "10000000000", "99999999999999999")
        Kyo.foreach(stamps) { stamp =>
            val message = s"""{"from":"16505551234","id":"wamid.X","timestamp":"$stamp","type":"text","text":{"body":"Hi"}}"""
            decode(
                s"""{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"value":{"messaging_product":"whatsapp",""" +
                    s""""metadata":{"display_phone_number":"15550783881","phone_number_id":"111"},"messages":[$message]},"field":"messages"}]}]}"""
            ).map(result => (stamp, message, result))
        }.map { results =>
            results.foreach { case (stamp, message, result) =>
                assert(
                    result == Result.succeed(Chunk[WhatsAppNotification](
                        WhatsAppNotification.Unknown("messages", WhatsAppRawJsonTest.of(message), Present(meta("15550783881", "111")))
                    )),
                    s"for $stamp"
                )
            }
            succeed
        }
    }

    "a message or status whose timestamp does not parse is its change's Unknown with its own raw value; its siblings decode" in {
        val badMessage  = """{"from":"16505551234","id":"wamid.X","timestamp":"soon","type":"text","text":{"body":"Hi"}}"""
        val goodMessage = """{"from":"16505551234","id":"wamid.A","timestamp":"1749000001","type":"text","text":{"body":"Hello"}}"""
        val badStatus   = """{"id":"wamid.B","status":"delivered","timestamp":"","recipient_id":"16505551234"}"""
        val json        =
            s"""{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"value":{"messaging_product":"whatsapp",""" +
                s""""metadata":{"display_phone_number":"15550783881","phone_number_id":"111"},"messages":[$badMessage,$goodMessage],""" +
                s""""statuses":[$badStatus]},"field":"messages"}]}]}"""
        val m = meta("15550783881", "111")
        decode(json).map { result =>
            assert(result == Result.succeed(Chunk[WhatsAppNotification](
                WhatsAppNotification.Unknown("messages", WhatsAppRawJsonTest.of(badMessage), Present(m)),
                text(m, "16505551234", "wamid.A", 1749000001L, "Hello"),
                WhatsAppNotification.Unknown("messages", WhatsAppRawJsonTest.of(badStatus), Present(m))
            )))
        }
    }

    "a body without entry, or an entry without changes, is a decode failure, not an empty Chunk" in {
        decode("""{"object":"whatsapp_business_account"}""").map { noEntry =>
            decode("""{"object":"whatsapp_business_account","entry":[{"id":"1"}]}""").map { noChanges =>
                assert(noEntry == notificationFailure(WhatsAppDecodeException.Failure.MissingField, Chunk.empty))
                assert(noChanges == notificationFailure(WhatsAppDecodeException.Failure.MissingField, Chunk("entry", "0")))
            }
        }
    }

    "a valid-but-empty entry array decodes to an empty Chunk" in {
        decode(emptyEntryJson).map { result =>
            assert(result == Result.succeed(Chunk.empty[WhatsAppNotification]))
        }
    }

end WhatsAppWebhookDecodeTest
