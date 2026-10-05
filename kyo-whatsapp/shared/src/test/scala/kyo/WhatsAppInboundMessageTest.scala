package kyo

/** Each inbound message type against Meta's message object for it, as the webhook references document it: the object decodes to the
  * value, and the value encodes back to the same object.
  */
class WhatsAppInboundMessageTest extends BaseWhatsAppTest:

    val from = WhatsAppId.WaId("16505551234")
    val id   = WhatsAppId.MessageId("wamid.TEST")
    val at   = epoch(1749416383L)

    def message(tpe: String, content: String, extra: String = ""): String =
        s"""{"from":"16505551234","id":"wamid.TEST","timestamp":"1749416383","type":"$tpe"$extra,$content}"""

    def roundTrips(json: String, expected: WhatsAppInboundMessage)(using Frame, kyo.test.AssertScope): Unit =
        assert(Json.decode[WhatsAppInboundMessage](json) == Result.succeed(expected))
        assert(unordered(Json.encode[WhatsAppInboundMessage](expected)) == unordered(json))

    "a text message is Meta's text message object" in {
        roundTrips(
            """{"from":"16505551234","id":"wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA=","timestamp":"1749416383",
              |"type":"text","text":{"body":"Does it come in another color?"}}""".stripMargin,
            WhatsAppInboundMessage.Text(
                from,
                WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA="),
                at,
                Absent,
                WhatsAppInboundMessage.Text.Body("Does it come in another color?")
            )
        )
    }

    "each media message holds Meta's media object under its type" in {
        val media = WhatsAppInboundMessage.Media(WhatsAppId.MediaId("M"), "image/jpeg", "h", Present("nice photo"))
        val body  = """{"id":"M","mime_type":"image/jpeg","sha256":"h","caption":"nice photo"}"""
        roundTrips(message("image", s""""image":$body"""), WhatsAppInboundMessage.Image(from, id, at, Absent, media))
        roundTrips(message("video", s""""video":$body"""), WhatsAppInboundMessage.Video(from, id, at, Absent, media))
        roundTrips(message("document", s""""document":$body"""), WhatsAppInboundMessage.Document(from, id, at, Absent, media))
        roundTrips(message("sticker", s""""sticker":$body"""), WhatsAppInboundMessage.Sticker(from, id, at, Absent, media))
    }

    "a voice note is an audio message whose media object has voice" in {
        roundTrips(
            message("audio", """"audio":{"id":"AUDIO1","mime_type":"audio/ogg","sha256":"abc","voice":true}"""),
            WhatsAppInboundMessage.Audio(
                from,
                id,
                at,
                Absent,
                WhatsAppInboundMessage.Media(WhatsAppId.MediaId("AUDIO1"), "audio/ogg", "abc", voice = Present(true))
            )
        )
    }

    "a location, a reaction, a button tap and a system message hold Meta's object for their type" in {
        roundTrips(
            message(
                "location",
                """"location":{"latitude":37.483307,"longitude":122.148981,"name":"Pablo Morales Residential Park","address":"Menlo Park, CA"}"""
            ),
            WhatsAppInboundMessage.Location(
                from,
                id,
                at,
                Absent,
                WhatsAppInboundMessage.Location.Body(
                    37.483307,
                    122.148981,
                    Present("Pablo Morales Residential Park"),
                    Present("Menlo Park, CA")
                )
            )
        )
        roundTrips(
            message("reaction", """"reaction":{"message_id":"wamid.ORIG","emoji":"😀"}"""),
            WhatsAppInboundMessage.Reaction(
                from,
                id,
                at,
                Absent,
                WhatsAppInboundMessage.Reaction.Body(WhatsAppId.MessageId("wamid.ORIG"), "😀")
            )
        )
        roundTrips(
            message("button", """"button":{"payload":"No-Button-Payload","text":"No"}"""),
            WhatsAppInboundMessage.Button(from, id, at, Absent, WhatsAppInboundMessage.Button.Body("No-Button-Payload", "No"))
        )
        roundTrips(
            message("system", """"system":{"body":"User changed their number"}"""),
            WhatsAppInboundMessage.System(from, id, at, Absent, WhatsAppInboundMessage.System.Body("User changed their number"))
        )
    }

    "contacts and an order hold Meta's objects" in {
        roundTrips(
            message(
                "contacts",
                """"contacts":[{"name":{"formatted_name":"Jane Smith"},"phones":[{"phone":"+1234567890","type":"CELL"}]}]"""
            ),
            WhatsAppInboundMessage.Contacts(
                from,
                id,
                at,
                Absent,
                Chunk(WhatsAppContact(
                    WhatsAppContact.Name("Jane Smith"),
                    phones = Chunk(WhatsAppContact.Phone(Present("+1234567890"), Present("CELL")))
                ))
            )
        )
        roundTrips(
            message(
                "order",
                """"order":{"catalog_id":"CATALOG_ID","text":"TEXT","product_items":[{"product_retailer_id":"PRODUCT_ID"}]}"""
            ),
            WhatsAppInboundMessage.Order(
                from,
                id,
                at,
                Absent,
                WhatsAppInboundMessage.Order.Body("CATALOG_ID", Present("TEXT"), Chunk(WhatsAppInboundMessage.Order.Item("PRODUCT_ID")))
            )
        )
    }

    "a list reply and a reply-button tap are Meta's interactive replies, with the context of the message they answer" in {
        roundTrips(
            """{"context":{"from":"15550783881","id":"wamid.HBgLMTY0NjcwNDM1OTUVAgARGBIwMjg0RkMxOEMyMkNEQUFFRDgA"},
              |"from":"16505551234","id":"wamid.HBgLMTY0NjcwNDM1OTUVAgASGBQzQTZDMzFGRUFBQjlDMzIzMzlEQwA=","timestamp":"1712595443",
              |"type":"interactive","interactive":{"type":"list_reply",
              |"list_reply":{"id":"priority_express","title":"Priority Mail Express","description":"Next Day to 2 Days"}}}""".stripMargin,
            WhatsAppInboundMessage.Interactive(
                from,
                WhatsAppId.MessageId("wamid.HBgLMTY0NjcwNDM1OTUVAgASGBQzQTZDMzFGRUFBQjlDMzIzMzlEQwA="),
                epoch(1712595443L),
                Present(WhatsAppInboundMessage.Context(
                    WhatsAppId.WaId("15550783881"),
                    WhatsAppId.MessageId("wamid.HBgLMTY0NjcwNDM1OTUVAgARGBIwMjg0RkMxOEMyMkNEQUFFRDgA")
                )),
                WhatsAppInboundMessage.Interactive.Reply.ListReply(
                    WhatsAppInboundMessage.Interactive.Reply.ListReply.Body(
                        "priority_express",
                        "Priority Mail Express",
                        Present("Next Day to 2 Days")
                    )
                )
            )
        )
        roundTrips(
            message("interactive", """"interactive":{"type":"button_reply","button_reply":{"id":"change-button","title":"Change"}}"""),
            WhatsAppInboundMessage.Interactive(
                from,
                id,
                at,
                Absent,
                WhatsAppInboundMessage.Interactive.Reply.ButtonReply(
                    WhatsAppInboundMessage.Interactive.Reply.ButtonReply.Body("change-button", "Change")
                )
            )
        )
    }

    "an interactive reply of a type the module does not enumerate is Reply.Other holding it" in {
        val reply = """{"type":"nfm_reply","nfm_reply":{"name":"flow","response_json":"{}"}}"""
        roundTrips(
            message("interactive", s""""interactive":$reply"""),
            WhatsAppInboundMessage.Interactive(
                from,
                id,
                at,
                Absent,
                WhatsAppInboundMessage.Interactive.Reply.Other("nfm_reply", WhatsAppRawJsonTest.of(reply))
            )
        )
    }

    "a message type the module does not enumerate is Unknown holding the whole message, and writes it back" in {
        val json = message("future_unknown_type", """"future_unknown_type":{"some_field":"value"}""")
        roundTrips(json, WhatsAppInboundMessage.Unknown("future_unknown_type", WhatsAppRawJsonTest.of(json)))
    }

    "a typed message reads its sender, id, time and context through Common" in {
        val ctx                                = WhatsAppInboundMessage.Context(from, WhatsAppId.MessageId("wamid.ORIG"))
        val msg: WhatsAppInboundMessage.Common =
            WhatsAppInboundMessage.Text(from, id, at, Present(ctx), WhatsAppInboundMessage.Text.Body("reply"))
        assert((msg.from, msg.id, msg.timestamp, msg.context) == (from, id, at, Present(ctx)))
    }

    "a known type missing its content, or with a timestamp that is not epoch seconds, does not decode as a message" in {
        assert(Json.decode[WhatsAppInboundMessage](message("text", """"other":1""")).isFailure)
        Json.decode[WhatsAppInboundMessage]("""{"from":"1","id":"w","timestamp":"-5","type":"text","text":{"body":"x"}}""") match
            case Result.Failure(e: ConstructorRejectedException) => assert(e.path == Chunk("timestamp"), e.path.toString)
            case other => fail(s"expected a ConstructorRejectedException at timestamp, got $other")
    }

    "an entry that does not decode as a message is Undecodable holding its JSON" in {
        val broken = message("text", """"other":1""")
        assert(Json.decode[WhatsAppInboundMessage.Entry](broken) ==
            Result.succeed(WhatsAppInboundMessage.Entry.Undecodable(WhatsAppRawJsonTest.of(broken))))
        assert(Json.decode[WhatsAppInboundMessage.Entry]("7") ==
            Result.succeed(WhatsAppInboundMessage.Entry.Undecodable(WhatsAppRawJsonTest.of("7"))))
    }

end WhatsAppInboundMessageTest
