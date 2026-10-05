package kyo

import kyo.internal.whatsapp.Codec
import kyo.internal.whatsapp.Methods

/** Each message type against the request body Meta documents for it (developers.facebook.com/docs/whatsapp/cloud-api/messages, one page per type):
  * the send encodes to exactly that JSON, and that JSON decodes to the same value.
  */
class WhatsAppMessageTest extends BaseWhatsAppTest:

    val to = WhatsAppId.WaId("+16505551234")

    def sent(message: WhatsAppMessage, replyTo: Maybe[WhatsAppId.MessageId] = Absent)(using Frame): Structure.Value =
        unordered(textOf(Codec.encodeSend(to, message, replyTo)))

    def roundTrips(json: String, message: WhatsAppMessage, replyTo: Maybe[WhatsAppId.MessageId] = Absent)(using
        Frame,
        kyo.test.AssertScope
    ): Unit =
        assert(sent(message, replyTo) == unordered(json))
        assert(Json.decode[Methods.Send](json) == Result.succeed[DecodeException, Methods.Send](Methods.Send(to, message, replyTo)))
    end roundTrips

    "a text message is Meta's text example" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"text",
              |"text":{"preview_url":true,"body":"As requested, here's the link to our latest product: https://www.meta.com/quest/quest-3/"}}"""
                .stripMargin,
            WhatsAppMessage.Text(
                "As requested, here's the link to our latest product: https://www.meta.com/quest/quest-3/",
                previewUrl = true
            )
        )
    }

    "a text message without a link preview leaves preview_url out" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"text","text":{"body":"hi"}}""",
            WhatsAppMessage.Text("hi")
        )
    }

    "an image by id is Meta's image example" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"image",
              |"image":{"id":"1479537139650973","caption":"The best succulent ever?"}}""".stripMargin,
            WhatsAppMessage.Image(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("1479537139650973")), Present("The best succulent ever?"))
        )
    }

    "an image by link puts the link where the id would be" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"image",
              |"image":{"link":"https://www.example.com/path/to/image.jpg"}}""".stripMargin,
            WhatsAppMessage.Image(WhatsAppMedia.Source.ByLink(url("https://www.example.com/path/to/image.jpg")))
        )
    }

    "a document is Meta's document example" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"document",
              |"document":{"id":"1376223850470843","filename":"order_abc123.pdf","caption":"Your order confirmation (PDF)"}}""".stripMargin,
            WhatsAppMessage.Document(
                WhatsAppMedia.Source.ById(WhatsAppId.MediaId("1376223850470843")),
                Present("Your order confirmation (PDF)"),
                Present("order_abc123.pdf")
            )
        )
    }

    "a voice note is Meta's audio example" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"audio",
              |"audio":{"id":"1013859600285441","voice":true}}""".stripMargin,
            WhatsAppMessage.Audio(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("1013859600285441")), voice = true)
        )
    }

    "an audio file that is not a voice note leaves voice out" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"audio","audio":{"id":"A"}}""",
            WhatsAppMessage.Audio(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("A")))
        )
    }

    "a video and a sticker hold their media objects under their type" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"video",
              |"video":{"link":"https://example.com/v.mp4","caption":"c"}}""".stripMargin,
            WhatsAppMessage.Video(WhatsAppMedia.Source.ByLink(url("https://example.com/v.mp4")), Present("c"))
        )
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"sticker","sticker":{"id":"S"}}""",
            WhatsAppMessage.Sticker(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("S")))
        )
    }

    "a location is written with numeric coordinates, and Meta's example, which writes them as strings, decodes to it" in {
        val location = WhatsAppMessage.Location(
            37.44216251868683,
            -122.16153582049394,
            Present("Philz Coffee"),
            Present(
                "101 Forest Ave, Palo Alto, CA 94301"
            )
        )
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"location",
              |"location":{"latitude":37.44216251868683,"longitude":-122.16153582049394,"name":"Philz Coffee",
              |"address":"101 Forest Ave, Palo Alto, CA 94301"}}""".stripMargin,
            location
        )
        val metaExample =
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"location",
              |"location":{"latitude":"37.44216251868683","longitude":"-122.16153582049394","name":"Philz Coffee",
              |"address":"101 Forest Ave, Palo Alto, CA 94301"}}""".stripMargin
        assert(Json.decode[Methods.Send](metaExample) == Result.succeed[DecodeException, Methods.Send](Methods.Send(to, location, Absent)))
    }

    "a coordinate that is not a number is refused at its path" in {
        Json.decode[WhatsAppMessage.Location.Body]("""{"latitude":"north","longitude":1}""") match
            case Result.Failure(e: ConstructorRejectedException) => assert(e.path == Chunk("latitude"), e.path.toString)
            case other                                           => fail(s"expected a ConstructorRejectedException, got $other")
    }

    "a reaction is Meta's reaction example" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"reaction",
              |"reaction":{"message_id":"wamid.HBgLMTY0NjcwNDM1OTUVAgASGBQzQUZCMTY0MDc2MUYwNzBDNTY5MAA=","emoji":"😀"}}"""
                .stripMargin,
            WhatsAppMessage.Reaction(WhatsAppId.MessageId("wamid.HBgLMTY0NjcwNDM1OTUVAgASGBQzQUZCMTY0MDc2MUYwNzBDNTY5MAA="), "😀")
        )
    }

    "a contacts message holds the cards under contacts" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"contacts",
              |"contacts":[{"name":{"formatted_name":"Pablo Morales","first_name":"Pablo"},
              |"phones":[{"phone":"+1 (650) 555-1234","type":"WORK","wa_id":"16505551234"}]}]}""".stripMargin,
            WhatsAppMessage.Contacts(Chunk(WhatsAppContact(
                WhatsAppContact.Name("Pablo Morales", first = Present("Pablo")),
                phones =
                    Chunk(WhatsAppContact.Phone(Present("+1 (650) 555-1234"), Present("WORK"), Present(WhatsAppId.WaId("16505551234"))))
            )))
        )
    }

    "a reply carries the replied-to message as the top-level context" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"text",
              |"context":{"message_id":"wamid.PREV"},"text":{"body":"reply"}}""".stripMargin,
            WhatsAppMessage.Text("reply"),
            Present(WhatsAppId.MessageId("wamid.PREV"))
        )
    }

    "an interactive list is Meta's list example" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"interactive",
              |"interactive":{"type":"list","header":{"type":"text","text":"Choose Shipping Option"},
              |"body":{"text":"Which shipping option do you prefer?"},"footer":{"text":"Lucky Shrub: Your gateway to succulents™"},
              |"action":{"button":"Shipping Options","sections":[
              |{"title":"I want it ASAP!","rows":[
              |{"id":"priority_express","title":"Priority Mail Express","description":"Next Day to 2 Days"},
              |{"id":"priority_mail","title":"Priority Mail","description":"1–3 Days"}]},
              |{"title":"I can wait a bit","rows":[
              |{"id":"usps_ground_advantage","title":"USPS Ground Advantage","description":"2–5 Days"},
              |{"id":"media_mail","title":"Media Mail","description":"2–8 Days"}]}]}}}""".stripMargin,
            WhatsAppMessage.OfInteractive(WhatsAppInteractive.ListMenu(
                "Shipping Options",
                Chunk(
                    WhatsAppInteractive.Section(
                        "I want it ASAP!",
                        Chunk(
                            WhatsAppInteractive.Row("priority_express", "Priority Mail Express", Present("Next Day to 2 Days")),
                            WhatsAppInteractive.Row("priority_mail", "Priority Mail", Present("1–3 Days"))
                        )
                    ),
                    WhatsAppInteractive.Section(
                        "I can wait a bit",
                        Chunk(
                            WhatsAppInteractive.Row("usps_ground_advantage", "USPS Ground Advantage", Present("2–5 Days")),
                            WhatsAppInteractive.Row("media_mail", "Media Mail", Present("2–8 Days"))
                        )
                    )
                ),
                body = Present("Which shipping option do you prefer?"),
                header = Present(WhatsAppInteractive.Header.Text("Choose Shipping Option")),
                footer = Present("Lucky Shrub: Your gateway to succulents™")
            ))
        )
    }

    "interactive reply buttons are Meta's reply-buttons example" in {
        roundTrips(
            """{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"interactive",
              |"interactive":{"type":"button","header":{"type":"image","image":{"id":"2762702990552401"}},
              |"body":{"text":"Hi Pablo! Your gardening workshop is scheduled for 9am tomorrow. Use the buttons if you need to reschedule. Thank you!"},
              |"footer":{"text":"Lucky Shrub: Your gateway to succulents!™"},
              |"action":{"buttons":[{"type":"reply","reply":{"id":"change-button","title":"Change"}},
              |{"type":"reply","reply":{"id":"cancel-button","title":"Cancel"}}]}}}""".stripMargin,
            WhatsAppMessage.OfInteractive(WhatsAppInteractive.Buttons(
                Chunk(
                    WhatsAppInteractive.ReplyButton("change-button", "Change"),
                    WhatsAppInteractive.ReplyButton("cancel-button", "Cancel")
                ),
                body = Present(
                    "Hi Pablo! Your gardening workshop is scheduled for 9am tomorrow. Use the buttons if you need to reschedule. Thank you!"
                ),
                header = Present(WhatsAppInteractive.Header.Image(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("2762702990552401")))),
                footer = Present("Lucky Shrub: Your gateway to succulents!™")
            ))
        )
    }

    "an interactive call-to-action url is Meta's cta_url example, its url written with the root path HttpUrl gives it" in {
        def example(ctaUrl: String) =
            s"""{"messaging_product":"whatsapp","recipient_type":"individual","to":"+16505551234","type":"interactive",
               |"interactive":{"type":"cta_url",
               |"header":{"type":"image","image":{"link":"https://www.luckyshrub.com/assets/lucky-shrub-banner-logo-v1.png"}},
               |"body":{"text":"Tap the button below to see available dates."},
               |"action":{"name":"cta_url","parameters":{"display_text":"See Dates","url":"$ctaUrl"}},
               |"footer":{"text":"Dates subject to change."}}}""".stripMargin
        val click   = "?clickID=kqDGWd24Q5TRwoEQTICY7W1JKoXvaZOXWAS7h1P76s0R7Paec4"
        val message = WhatsAppMessage.OfInteractive(WhatsAppInteractive.CtaUrl(
            "See Dates",
            url(s"https://www.luckyshrub.com$click"),
            body = Present("Tap the button below to see available dates."),
            header = Present(WhatsAppInteractive.Header.Image(
                WhatsAppMedia.Source.ByLink(url("https://www.luckyshrub.com/assets/lucky-shrub-banner-logo-v1.png"))
            )),
            footer = Present("Dates subject to change.")
        ))
        assert(Json.decode[Methods.Send](example(s"https://www.luckyshrub.com$click")) ==
            Result.succeed[DecodeException, Methods.Send](Methods.Send(to, message, Absent)))
        roundTrips(example(s"https://www.luckyshrub.com/$click"), message)
    }

    "the companion apply builds each case from its fields, and methods read them" in {
        val text = WhatsAppMessage.Text("link here", previewUrl = true)
        assert(text == WhatsAppMessage.Text(WhatsAppMessage.Text.Body("link here", previewUrl = true)))
        assert((text.body, text.previewUrl) == ("link here", true))
        val document = WhatsAppMessage.Document(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("D")), Absent, Present("f.pdf"))
        assert((document.source, document.caption, document.filename) ==
            (WhatsAppMedia.Source.ById(WhatsAppId.MediaId("D")), Absent, Present("f.pdf")))
        val reaction = WhatsAppMessage.Reaction(WhatsAppId.MessageId("wamid.X"), "")
        assert((reaction.messageId, reaction.emoji) == (WhatsAppId.MessageId("wamid.X"), ""))
    }

end WhatsAppMessageTest
