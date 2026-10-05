package kyo

import kyo.internal.whatsapp.Codec
import kyo.internal.whatsapp.Methods

/** The template send against Meta's documented request body (documentation/business-messaging/whatsapp/templates/utility-templates):
  * the send encodes to exactly that JSON, and that JSON decodes to the same value.
  */
class WhatsAppTemplateTest extends BaseWhatsAppTest:

    val to = WhatsAppId.WaId("16505551234")

    def roundTrips(json: String, template: WhatsAppTemplate)(using Frame, kyo.test.AssertScope): Unit =
        assert(unordered(textOf(Codec.encodeTemplate(to, template, Absent))) == unordered(json))
        assert(Json.decode[Methods.SendTemplate](json) ==
            Result.succeed[DecodeException, Methods.SendTemplate](Methods.SendTemplate(to, template, Absent)))
    end roundTrips

    def send(template: String): String =
        s"""{"messaging_product":"whatsapp","recipient_type":"individual","to":"16505551234","type":"template","template":$template}"""

    "Meta's template example: an image header and named text parameters" in {
        roundTrips(
            send(
                """{"name":"reservation_confirmation","language":{"code":"en_US"},"components":[
                  |{"type":"header","parameters":[{"type":"image","image":{"id":"2871834006348767"}}]},
                  |{"type":"body","parameters":[
                  |{"type":"text","parameter_name":"number_of_guests","text":"4"},
                  |{"type":"text","parameter_name":"day","text":"Saturday"},
                  |{"type":"text","parameter_name":"date","text":"August 30th, 2025"},
                  |{"type":"text","parameter_name":"time","text":"7:30 pm"}]}]}""".stripMargin
            ),
            WhatsAppTemplate(
                "reservation_confirmation",
                "en_US",
                Chunk(
                    WhatsAppTemplate.Component.Header(
                        Chunk(WhatsAppTemplate.Parameter.Image(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("2871834006348767"))))
                    ),
                    WhatsAppTemplate.Component.Body(Chunk(
                        WhatsAppTemplate.Parameter.Text("4", Present("number_of_guests")),
                        WhatsAppTemplate.Parameter.Text("Saturday", Present("day")),
                        WhatsAppTemplate.Parameter.Text("August 30th, 2025", Present("date")),
                        WhatsAppTemplate.Parameter.Text("7:30 pm", Present("time"))
                    ))
                )
            )
        )
    }

    "currency and date_time parameters hold Meta's objects, and a button names its sub_type and index" in {
        roundTrips(
            send(
                """{"name":"order_confirmation","language":{"code":"en_US"},"components":[
                  |{"type":"body","parameters":[
                  |{"type":"currency","currency":{"fallback_value":"$100.99","code":"USD","amount_1000":100990}},
                  |{"type":"date_time","date_time":{"fallback_value":"October 25, 2020"}}]},
                  |{"type":"button","sub_type":"quick_reply","index":0,"parameters":[{"type":"payload","payload":"track-order-9128312831"}]},
                  |{"type":"button","sub_type":"url","index":1,"parameters":[{"type":"text","text":"promo-code-123"}]}]}""".stripMargin
            ),
            WhatsAppTemplate(
                "order_confirmation",
                "en_US",
                Chunk(
                    WhatsAppTemplate.Component.Body(Chunk(
                        WhatsAppTemplate.Parameter.Currency("$100.99", "USD", 100990L),
                        WhatsAppTemplate.Parameter.DateTime("October 25, 2020")
                    )),
                    WhatsAppTemplate.Component.Button(
                        WhatsAppTemplate.ButtonSubType.QuickReply,
                        0,
                        Chunk(WhatsAppTemplate.Parameter.Payload("track-order-9128312831"))
                    ),
                    WhatsAppTemplate.Component.Button(
                        WhatsAppTemplate.ButtonSubType.Url,
                        1,
                        Chunk(WhatsAppTemplate.Parameter.Text("promo-code-123"))
                    )
                )
            )
        )
    }

    "a document and a video parameter hold their media objects" in {
        roundTrips(
            send(
                """{"name":"t","language":{"code":"en_US"},"components":[{"type":"header","parameters":[
                  |{"type":"document","document":{"link":"https://example.com/r.pdf","filename":"receipt.pdf"}}]},
                  |{"type":"header","parameters":[{"type":"video","video":{"id":"V"}}]}]}""".stripMargin
            ),
            WhatsAppTemplate(
                "t",
                "en_US",
                Chunk(
                    WhatsAppTemplate.Component.Header(Chunk(
                        WhatsAppTemplate.Parameter.Document(
                            WhatsAppMedia.Source.ByLink(url("https://example.com/r.pdf")),
                            Present("receipt.pdf")
                        )
                    )),
                    WhatsAppTemplate.Component.Header(
                        Chunk(WhatsAppTemplate.Parameter.Video(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("V"))))
                    )
                )
            )
        )
    }

    "a template without components leaves components out, and its language holds only the code" in {
        roundTrips(send("""{"name":"hello_world","language":{"code":"en_US"}}"""), WhatsAppTemplate("hello_world", "en_US"))
    }

end WhatsAppTemplateTest
