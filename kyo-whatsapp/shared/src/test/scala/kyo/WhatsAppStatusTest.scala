package kyo

/** The status object against Meta's statuses as the webhook reference documents them: each decodes to the value and encodes back to
  * the same object.
  */
class WhatsAppStatusTest extends BaseWhatsAppTest:

    def roundTrips(json: String, expected: WhatsAppStatus)(using Frame, kyo.test.AssertScope): Unit =
        assert(Json.decode[WhatsAppStatus](json) == Result.succeed(expected))
        assert(unordered(Json.encode(expected)) == unordered(json))

    def status(kind: WhatsAppStatus.Kind): WhatsAppStatus =
        WhatsAppStatus(WhatsAppId.MessageId("wamid.STATUS"), kind, epoch(1749416383L), WhatsAppId.WaId("16505551234"))

    "a delivered status is Meta's delivered example: a conversation without an expiration, and its pricing" in {
        roundTrips(
            """{"id":"wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI3MTE5MjVBOTE3MDk5QUVFM0YA","status":"delivered","timestamp":"1750263773",
              |"recipient_id":"16505551234","conversation":{"id":"6ceb9d929c9bdc4f90e967a32f8639b4","origin":{"type":"service"}},
              |"pricing":{"billable":true,"pricing_model":"CBP","category":"service"}}""".stripMargin,
            WhatsAppStatus(
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
            )
        )
    }

    "a sent status's conversation expiration_timestamp is epoch seconds as a string" in {
        roundTrips(
            """{"id":"wamid.STATUS","status":"sent","timestamp":"1749416383","recipient_id":"16505551234",
              |"conversation":{"id":"CONVERSATION_ID","expiration_timestamp":"1750116480","origin":{"type":"utility"}}}""".stripMargin,
            status(WhatsAppStatus.Kind.Sent).copy(conversation =
                Present(WhatsAppStatus.Conversation(
                    "CONVERSATION_ID",
                    Present(epoch(1750116480L)),
                    WhatsAppStatus.Conversation.Origin("utility")
                ))
            )
        )
    }

    "an expiration_timestamp that is not epoch seconds is refused at its path" in {
        val json = """{"id":"w","status":"sent","timestamp":"1","recipient_id":"1",
                     |"conversation":{"id":"C","expiration_timestamp":"-1","origin":{"type":"utility"}}}""".stripMargin
        Json.decode[WhatsAppStatus](json) match
            case Result.Failure(e: ConstructorRejectedException) =>
                assert(e.path == Chunk("conversation", "expiration_timestamp"), e.path.toString)
            case other => fail(s"expected a ConstructorRejectedException at the expiration, got $other")
        end match
    }

    "a failed status is Meta's failed example; its error's message, a copy of the title, is read and not written back" in {
        val metaExample =
            """{"id":"wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI0QUQ2MjA4NEYyRkExNjMyREUA","status":"failed","timestamp":"1751142888",
              |"recipient_id":"16505551234","errors":[{"code":131049,
              |"title":"This message was not delivered to maintain healthy ecosystem engagement.",
              |"message":"This message was not delivered to maintain healthy ecosystem engagement.",
              |"error_data":{"details":"In order to maintain a healthy ecosystem engagement, the message failed to be delivered."},
              |"href":"/documentation/business-messaging/whatsapp/support/error-codes"}]}""".stripMargin
        val expected =
            WhatsAppStatus(
                WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI0QUQ2MjA4NEYyRkExNjMyREUA"),
                WhatsAppStatus.Kind.Failed,
                epoch(1751142888L),
                WhatsAppId.WaId("16505551234"),
                errors = Chunk(WhatsAppStatus.DeliveryIssue(
                    131049,
                    Present("This message was not delivered to maintain healthy ecosystem engagement."),
                    Present(WhatsAppStatus.DeliveryIssue.ErrorData(
                        Present("In order to maintain a healthy ecosystem engagement, the message failed to be delivered.")
                    )),
                    Present("/documentation/business-messaging/whatsapp/support/error-codes")
                ))
            )
        assert(Json.decode[WhatsAppStatus](metaExample) == Result.succeed(expected))
        assert(unordered(Json.encode(expected)) ==
            unordered(metaExample.replace(""""message":"This message was not delivered to maintain healthy ecosystem engagement.",""", "")))
    }

    "a delivery issue's title falls back to Meta's message" in {
        assert(Json.decode[WhatsAppStatus.DeliveryIssue]("""{"code":131026,"message":"Undeliverable"}""") ==
            Result.succeed(WhatsAppStatus.DeliveryIssue(131026, Present("Undeliverable"))))
        assert(WhatsAppStatus.DeliveryIssue(131000).details == Absent)
    }

    "each documented status value is its kind, and any other is Other holding it" in {
        Seq(
            "sent"          -> WhatsAppStatus.Kind.Sent,
            "delivered"     -> WhatsAppStatus.Kind.Delivered,
            "read"          -> WhatsAppStatus.Kind.Read,
            "failed"        -> WhatsAppStatus.Kind.Failed,
            "deleted"       -> WhatsAppStatus.Kind.Deleted,
            "future_status" -> WhatsAppStatus.Kind.Other("future_status")
        ).foreach { (wire, kind) =>
            roundTrips(
                s"""{"id":"wamid.STATUS","status":"$wire","timestamp":"1749416383","recipient_id":"16505551234"}""",
                status(kind)
            )
        }
        succeed
    }

    "pricing reads Meta's type as kind" in {
        val pricing = WhatsAppStatus.Pricing(true, "PMP", "utility", Present("regular"))
        assert(Json.decode[WhatsAppStatus.Pricing]("""{"billable":true,"pricing_model":"PMP","category":"utility","type":"regular"}""") ==
            Result.succeed(pricing))
        assert(unordered(Json.encode(pricing)) ==
            unordered("""{"billable":true,"pricing_model":"PMP","category":"utility","type":"regular"}"""))
    }

    "an entry that does not decode as a status is Undecodable holding its JSON" in {
        val broken = """{"id":"wamid.X","status":"sent"}"""
        assert(Json.decode[WhatsAppStatus.Entry](broken) ==
            Result.succeed(WhatsAppStatus.Entry.Undecodable(WhatsAppRawJsonTest.of(broken))))
    }

end WhatsAppStatusTest
