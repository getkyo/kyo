package kyo

import kyo.WhatsAppId.*

class WhatsAppContactTest extends BaseWhatsAppTest:

    "full contact encodes to the documented snake_case shape" in {
        val to      = WhatsAppId.WaId("PHONE_NUMBER")
        val contact = WhatsAppContact(
            WhatsAppContact.Name("NAME", Present("FIRST_NAME"), Present("LAST_NAME")),
            phones = Chunk(WhatsAppContact.Phone(Present("PHONE_NUMBER"), Present("HOME"), Present(WhatsAppId.WaId("WHATSAPP_ID")))),
            emails = Chunk(WhatsAppContact.Email(Present("EMAIL"), Present("WORK"))),
            addresses = Chunk(WhatsAppContact.Address(
                Present("STREET"),
                Present("CITY"),
                Present("STATE"),
                Present("ZIP"),
                Present("COUNTRY"),
                Present("COUNTRY_CODE"),
                Present("HOME")
            )),
            org = Present(WhatsAppContact.Org(Present("COMPANY"), Present("DEPARTMENT"), Present("TITLE"))),
            urls = Chunk(WhatsAppContact.Url(Present("URL"), Present("WORK"))),
            birthday = Present("2000-01-01")
        )
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, WhatsAppMessage.Contacts(Chunk(contact)), Absent).toArray, "UTF-8")
        assert(json.contains("\"formatted_name\":\"NAME\""))
        assert(json.contains("\"first_name\":\"FIRST_NAME\""))
        assert(json.contains("\"last_name\":\"LAST_NAME\""))
        assert(json.contains("\"country_code\":\"COUNTRY_CODE\""))
        assert(json.contains("\"wa_id\":\"WHATSAPP_ID\""))
        assert(json.contains("\"birthday\":\"2000-01-01\""))
    }

    "contact with only required name omits all optional sub-objects" in {
        val to      = WhatsAppId.WaId("P")
        val contact = WhatsAppContact(WhatsAppContact.Name("NAME"))
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, WhatsAppMessage.Contacts(Chunk(contact)), Absent).toArray, "UTF-8")
        assert(json.contains("\"formatted_name\":\"NAME\""))
        assert(!json.contains("\"phones\""))
        assert(!json.contains("\"emails\""))
        assert(!json.contains("\"addresses\""))
        assert(!json.contains("\"org\""))
        assert(!json.contains("\"urls\""))
        assert(!json.contains("\"birthday\""))
    }

end WhatsAppContactTest
