package kyo

class WhatsAppRawJsonTest extends BaseWhatsAppTest:

    "toString renders the length, never the text, and value is the text" in {
        val text = """{"note":"PLANTED-RAW-TEXT"}"""
        val raw  = WhatsAppRawJson(text)
        assert(raw.toString == s"WhatsAppRawJson(${text.length} characters)")
        assert(raw.value == text)
    }

    "equality and hashCode are by text" in {
        assert(WhatsAppRawJson("{}") == WhatsAppRawJson("{}"))
        assert(WhatsAppRawJson("{}") != WhatsAppRawJson("[]"))
        assert(WhatsAppRawJson("{}").hashCode == WhatsAppRawJson("{}").hashCode)
    }

end WhatsAppRawJsonTest
