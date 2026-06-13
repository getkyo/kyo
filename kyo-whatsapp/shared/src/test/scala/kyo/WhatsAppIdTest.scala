package kyo

import kyo.WhatsAppId.*

class WhatsAppIdTest extends BaseWhatsAppTest:

    "WabaId round-trips through apply/value" in {
        val id = WhatsAppId.WabaId("102290129340398")
        assert(id.value == "102290129340398")
    }

    "each of the five id types constructs and reads its value" in {
        val w   = WhatsAppId.WabaId("w")
        val p   = WhatsAppId.PhoneNumberId("p")
        val m   = WhatsAppId.MediaId("m")
        val msg = WhatsAppId.MessageId("msg")
        val wa  = WhatsAppId.WaId("wa")
        assert(w.value == "w")
        assert(p.value == "p")
        assert(m.value == "m")
        assert(msg.value == "msg")
        assert(wa.value == "wa")
    }

    "ids are mutually non-interchangeable at the type level" in {
        def f(id: WhatsAppId.MessageId): String = id.value
        val w                                   = WhatsAppId.WabaId("x")
        typeCheckFailure("f(w)")
    }

    "same-type ids compare by value via the derived CanEqual" in {
        assert(WhatsAppId.MessageId("wamid.A") == WhatsAppId.MessageId("wamid.A"))
        assert(WhatsAppId.MessageId("wamid.A") != WhatsAppId.MessageId("wamid.B"))
    }

    "a MediaId Schema round-trips through Json" in {
        val id      = WhatsAppId.MediaId("MEDIA-123")
        val encoded = Json.encode(id)
        val decoded = Json.decode[WhatsAppId.MediaId](encoded).getOrThrow
        assert(decoded.value == "MEDIA-123")
    }

    "a WhatsAppId encodes as a bare JSON string, not an object" in {
        val id      = WhatsAppId.WaId("16505551234")
        val encoded = Json.encode(id)
        assert(encoded == "\"16505551234\"")
    }

end WhatsAppIdTest
