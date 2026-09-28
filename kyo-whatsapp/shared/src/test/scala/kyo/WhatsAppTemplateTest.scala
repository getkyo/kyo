package kyo

import kyo.WhatsAppTemplate.*

class WhatsAppTemplateTest extends BaseWhatsAppTest:

    "template with header image + body + button encodes correctly" in {
        val to   = WhatsAppId.WaId("PHONE_NUMBER")
        val tmpl = WhatsAppTemplate(
            "order_confirmation",
            "en_US",
            Chunk(
                WhatsAppTemplate.Component.Header(
                    Chunk(WhatsAppTemplate.Parameter.Image(WhatsAppMedia.Source.ByLink(url("https://example.com/images/order-banner.jpg"))))
                ),
                WhatsAppTemplate.Component.Body(Chunk(
                    WhatsAppTemplate.Parameter.Text("Pablo"),
                    WhatsAppTemplate.Parameter.Text("#9128312831"),
                    WhatsAppTemplate.Parameter.Currency("$100.99", "USD", 100990L),
                    WhatsAppTemplate.Parameter.DateTime("October 25, 2020")
                )),
                WhatsAppTemplate.Component.Button(
                    WhatsAppTemplate.ButtonSubType.QuickReply,
                    0,
                    Chunk(WhatsAppTemplate.Parameter.Payload("track-order-9128312831"))
                )
            )
        )
        val json = new String(kyo.internal.whatsapp.Codec.encodeTemplate(to, tmpl, Absent).toArray, "UTF-8")
        assert(json.contains("\"name\":\"order_confirmation\""))
        assert(json.contains("\"code\":\"en_US\""))
        assert(json.contains("\"type\":\"header\""))
        assert(json.contains("\"type\":\"image\""))
        assert(json.contains("\"link\":\"https://example.com/images/order-banner.jpg\""))
        assert(json.contains("\"type\":\"currency\""))
        assert(json.contains("\"fallback_value\":\"$100.99\""))
        assert(json.contains("\"amount_1000\":100990"))
        assert(json.contains("\"type\":\"date_time\""))
        assert(json.contains("\"fallback_value\":\"October 25, 2020\""))
        assert(json.contains("\"sub_type\":\"quick_reply\""))
        assert(json.contains("\"index\":0"))
        assert(json.contains("\"payload\":\"track-order-9128312831\""))
    }

    "currency parameter encodes amount_1000 and fallback_value" in {
        val to   = WhatsAppId.WaId("P")
        val tmpl = WhatsAppTemplate(
            "t",
            "en_US",
            Chunk(WhatsAppTemplate.Component.Body(Chunk(WhatsAppTemplate.Parameter.Currency("$100.99", "USD", 100990L))))
        )
        val json = new String(kyo.internal.whatsapp.Codec.encodeTemplate(to, tmpl, Absent).toArray, "UTF-8")
        assert(
            json.contains("{\"type\":\"currency\",\"currency\":{\"fallback_value\":\"$100.99\",\"code\":\"USD\",\"amount_1000\":100990}}")
        )
    }

    "button url-suffix component encodes sub_type:url and text param" in {
        val to   = WhatsAppId.WaId("P")
        val tmpl = WhatsAppTemplate(
            "t",
            "en_US",
            Chunk(WhatsAppTemplate.Component.Button(
                WhatsAppTemplate.ButtonSubType.Url,
                0,
                Chunk(WhatsAppTemplate.Parameter.Text("promo-code-123"))
            ))
        )
        val json = new String(kyo.internal.whatsapp.Codec.encodeTemplate(to, tmpl, Absent).toArray, "UTF-8")
        assert(json.contains("\"type\":\"button\""))
        assert(json.contains("\"sub_type\":\"url\""))
        assert(json.contains("\"index\":0"))
        assert(json.contains("{\"type\":\"text\",\"text\":\"promo-code-123\"}"))
    }

    "template with no components omits components key; language has only code" in {
        val to   = WhatsAppId.WaId("P")
        val tmpl = WhatsAppTemplate("hello_world", "en_US")
        val json = new String(kyo.internal.whatsapp.Codec.encodeTemplate(to, tmpl, Absent).toArray, "UTF-8")
        assert(json.contains("\"language\":{\"code\":\"en_US\"}"))
        assert(!json.contains("\"components\""))
        assert(!json.contains("\"policy\""))
    }

end WhatsAppTemplateTest
