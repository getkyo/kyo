package kyo

import kyo.WhatsAppId.*

class WhatsAppIdTest extends BaseWhatsAppTest:

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
