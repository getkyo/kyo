package kyo.whatsapp

import kyo.*
import kyo.whatsapp.Id.*

class IdTest extends BaseWhatsAppTest:

    "WabaId round-trips through apply/value" in {
        val id = Id.WabaId("102290129340398")
        assert(id.value == "102290129340398")
    }

    "each of the five id types constructs and reads its value" in {
        val w   = Id.WabaId("w")
        val p   = Id.PhoneNumberId("p")
        val m   = Id.MediaId("m")
        val msg = Id.MessageId("msg")
        val wa  = Id.WhatsAppId("wa")
        assert(w.value == "w")
        assert(p.value == "p")
        assert(m.value == "m")
        assert(msg.value == "msg")
        assert(wa.value == "wa")
    }

    "ids are mutually non-interchangeable at the type level" in {
        def f(id: Id.MessageId): String = id.value
        val w                           = Id.WabaId("x")
        typeCheckFailure("f(w)")
    }

    "same-type ids compare by value via the derived CanEqual" in {
        assert(Id.MessageId("wamid.A") == Id.MessageId("wamid.A"))
        assert(Id.MessageId("wamid.A") != Id.MessageId("wamid.B"))
    }

    "a MediaId Schema round-trips through Json" in {
        val id      = Id.MediaId("MEDIA-123")
        val encoded = Json.encode(id)
        val decoded = Json.decode[Id.MediaId](encoded).getOrThrow
        assert(decoded.value == "MEDIA-123")
    }

    "a WhatsAppId encodes as a bare JSON string, not an object" in {
        val id      = Id.WhatsAppId("16505551234")
        val encoded = Json.encode(id)
        assert(encoded == "\"16505551234\"")
    }

end IdTest
