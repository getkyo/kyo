package kyo

class WhatsAppSendResultTest extends BaseWhatsAppTest:

    "SendResult and its Status enum construct" in {
        val result = WhatsAppSendResult(
            WhatsAppId.MessageId("wamid.A"),
            Present(WhatsAppId.WaId("16505551234")),
            Present(WhatsAppSendResult.Status.Accepted)
        )
        assert(result.messageId == WhatsAppId.MessageId("wamid.A"))
        assert(result.contactWaId == Present(WhatsAppId.WaId("16505551234")))
        assert(result.status == Present(WhatsAppSendResult.Status.Accepted))
        assert(WhatsAppSendResult.Status.Accepted != WhatsAppSendResult.Status.HeldForQualityAssessment)
        assert(WhatsAppSendResult.Status.HeldForQualityAssessment != WhatsAppSendResult.Status.Paused)
        assert(WhatsAppSendResult.Status.Accepted != WhatsAppSendResult.Status.Paused)
    }

    "Status.Other absorbs an unrecognized message_status" in {
        val other = WhatsAppSendResult.Status.Other("queued")
        assert(other != WhatsAppSendResult.Status.Accepted)
        assert(other == WhatsAppSendResult.Status.Other("queued"))
    }

    "defaults leave contactWaId and status Absent" in {
        val result = WhatsAppSendResult(WhatsAppId.MessageId("wamid.B"))
        assert(result.contactWaId == Absent)
        assert(result.status == Absent)
    }

end WhatsAppSendResultTest
