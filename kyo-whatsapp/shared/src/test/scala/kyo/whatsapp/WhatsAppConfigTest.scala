package kyo.whatsapp

import kyo.*
import kyo.whatsapp.Id.*

class WhatsAppConfigTest extends BaseWhatsAppTest:

    "SendResult and its Status enum construct" in {
        val result = WhatsApp.SendResult(
            Id.MessageId("wamid.A"),
            Present(Id.WhatsAppId("16505551234")),
            Present(WhatsApp.SendResult.Status.Accepted)
        )
        assert(result.messageId == Id.MessageId("wamid.A"))
        assert(result.contactWaId == Present(Id.WhatsAppId("16505551234")))
        assert(result.status == Present(WhatsApp.SendResult.Status.Accepted))
        assert(WhatsApp.SendResult.Status.Accepted != WhatsApp.SendResult.Status.HeldForQualityAssessment)
        assert(WhatsApp.SendResult.Status.HeldForQualityAssessment != WhatsApp.SendResult.Status.Paused)
        assert(WhatsApp.SendResult.Status.Accepted != WhatsApp.SendResult.Status.Paused)
    }

    "Config carries the four fields with documented defaults" in {
        val cfg = WhatsApp.Config("TOKEN", Id.PhoneNumberId("106540352242922"))
        assert(cfg.token == "TOKEN")
        assert(cfg.phoneNumberId == Id.PhoneNumberId("106540352242922"))
        assert(cfg.apiVersion == "v25.0")
        assert(cfg.baseUrl == "https://graph.facebook.com")
    }

    "token fluent setter returns an updated copy" in {
        val cfg     = WhatsApp.Config("OLD", Id.PhoneNumberId("p"))
        val updated = cfg.token("NEW")
        assert(updated.token == "NEW")
        assert(cfg.token == "OLD")
    }

    "phoneNumberId fluent setter returns an updated copy" in {
        val cfg     = WhatsApp.Config("T", Id.PhoneNumberId("111"))
        val updated = cfg.phoneNumberId(Id.PhoneNumberId("999"))
        assert(updated.phoneNumberId.value == "999")
        assert(cfg.phoneNumberId.value == "111")
    }

    "apiVersion fluent setter returns an updated copy" in {
        val cfg     = WhatsApp.Config("T", Id.PhoneNumberId("p"))
        val updated = cfg.apiVersion("v26.0")
        assert(updated.apiVersion == "v26.0")
    }

    "baseUrl fluent setter returns an updated copy" in {
        val cfg     = WhatsApp.Config("T", Id.PhoneNumberId("p"))
        val updated = cfg.baseUrl("https://example.test")
        assert(updated.baseUrl == "https://example.test")
    }

    "use without a bound Config panics with a clear message" in {
        val result = Abort.run[WhatsAppError](
            WhatsApp.use(c => c.token)
        )
        result.map { r =>
            r match
                case Result.Panic(ex: IllegalStateException) =>
                    assert(ex.getMessage.contains("no WhatsApp.Config bound"))
                case other =>
                    assert(false, s"expected Panic with IllegalStateException, got: $other")
        }
    }

end WhatsAppConfigTest
