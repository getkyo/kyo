package kyo

class WhatsAppConfigTest extends BaseWhatsAppTest:

    "Config carries the four fields with documented defaults" in {
        val cfg = WhatsAppConfig("TOKEN", WhatsAppId.PhoneNumberId("106540352242922"))
        assert(cfg.token == "TOKEN")
        assert(cfg.phoneNumberId == WhatsAppId.PhoneNumberId("106540352242922"))
        assert(cfg.apiVersion == "v25.0")
        assert(cfg.baseUrl == "https://graph.facebook.com")
    }

    "token fluent setter returns an updated copy" in {
        val cfg     = WhatsAppConfig("OLD", WhatsAppId.PhoneNumberId("p"))
        val updated = cfg.token("NEW")
        assert(updated.token == "NEW")
        assert(cfg.token == "OLD")
    }

    "phoneNumberId fluent setter returns an updated copy" in {
        val cfg     = WhatsAppConfig("T", WhatsAppId.PhoneNumberId("111"))
        val updated = cfg.phoneNumberId(WhatsAppId.PhoneNumberId("999"))
        assert(updated.phoneNumberId.value == "999")
        assert(cfg.phoneNumberId.value == "111")
    }

    "apiVersion fluent setter returns an updated copy" in {
        val cfg     = WhatsAppConfig("T", WhatsAppId.PhoneNumberId("p"))
        val updated = cfg.apiVersion("v26.0")
        assert(updated.apiVersion == "v26.0")
    }

    "baseUrl fluent setter returns an updated copy" in {
        val cfg     = WhatsAppConfig("T", WhatsAppId.PhoneNumberId("p"))
        val updated = cfg.baseUrl("https://example.test")
        assert(updated.baseUrl == "https://example.test")
    }

    "toString masks the bearer token but renders the other fields" in {
        val cfg = WhatsAppConfig("super-secret-token", WhatsAppId.PhoneNumberId("106540352242922"))
        val s   = cfg.toString
        assert(!s.contains("super-secret-token"))
        assert(s.contains("token=***"))
        assert(s.contains("106540352242922"))
        assert(s.contains("v25.0"))
        assert(s.contains("https://graph.facebook.com"))
    }

    "use without a bound Config falls back to the StaticFlag default Config" in {
        WhatsApp.use(c => c).map { c =>
            assert(c.token == "")
            assert(c.phoneNumberId.value == "")
            assert(c.apiVersion == "v25.0")
            assert(c.baseUrl == "https://graph.facebook.com")
        }
    }

end WhatsAppConfigTest
