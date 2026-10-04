package kyo

class WhatsAppWebhookSignatureTest extends BaseWhatsAppTest:

    val webhook               = webhookConfigOf("Jefe", "verify")
    val bodyBytes: Span[Byte] = utf8("what do ya want for nothing?")
    // RFC-4231 test case 2 digest: HMAC-SHA256(key="Jefe", data="what do ya want for nothing?")
    val knownGoodHex: String    = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
    val knownGoodHeader: String = s"sha256=$knownGoodHex"

    "a known-good sha256= header verifies" in {
        val result = WhatsApp.Webhook.verify(webhook, Present(knownGoodHeader), bodyBytes)
        assert(result == Result.unit)
    }

    "a missing header yields WhatsAppSignatureMissingException" in {
        val result = WhatsApp.Webhook.verify(webhook, Absent, bodyBytes)
        assert(result == Result.fail(WhatsAppSignatureMissingException()))
    }

    "a header without the sha256= prefix yields WhatsAppSignatureMalformedException" in {
        val result = WhatsApp.Webhook.verify(webhook, Present("abc123"), bodyBytes)
        assert(result == Result.fail(WhatsAppSignatureMalformedException()))
    }

    "a non-hex remainder yields WhatsAppSignatureMalformedException" in {
        val result = WhatsApp.Webhook.verify(webhook, Present("sha256=zzzz"), bodyBytes)
        assert(result == Result.fail(WhatsAppSignatureMalformedException()))
    }

    "a wrong digest yields WhatsAppSignatureMismatchException" in {
        val wrongHex    = "0" * 64
        val wrongHeader = s"sha256=$wrongHex"
        val result      = WhatsApp.Webhook.verify(webhook, Present(wrongHeader), bodyBytes)
        assert(result == Result.fail(WhatsAppSignatureMismatchException()))
    }

    "a one-byte-tampered body yields WhatsAppSignatureMismatchException" in {
        val tampered = Span.concat(Span((bodyBytes(0) ^ 0xff).toByte), bodyBytes.slice(1, bodyBytes.size))
        val result   = WhatsApp.Webhook.verify(webhook, Present(knownGoodHeader), tampered)
        assert(result == Result.fail(WhatsAppSignatureMismatchException()))
    }

    "an uppercase-hex header still matches" in {
        val upperHeader = s"sha256=${knownGoodHex.toUpperCase}"
        val result      = WhatsApp.Webhook.verify(webhook, Present(upperHeader), bodyBytes)
        assert(result == Result.unit)
    }

    "an empty or odd-length hex remainder yields WhatsAppSignatureMalformedException" in {
        assert(WhatsApp.Webhook.verify(webhook, Present("sha256="), bodyBytes) ==
            Result.fail(WhatsAppSignatureMalformedException()))
        assert(WhatsApp.Webhook.verify(webhook, Present("sha256=abc"), bodyBytes) ==
            Result.fail(WhatsAppSignatureMalformedException()))
    }

    "verify never throws" in {
        val r1 = WhatsApp.Webhook.verify(webhook, Absent, Span.empty)
        val r2 = WhatsApp.Webhook.verify(webhook, Present("no-prefix"), Span.empty)
        val r3 = WhatsApp.Webhook.verify(webhook, Present("sha256=abc"), Span.empty)
        assert(r1 == Result.fail(WhatsAppSignatureMissingException()))
        assert(r2 == Result.fail(WhatsAppSignatureMalformedException()))
        assert(r3 == Result.fail(WhatsAppSignatureMalformedException()))
    }

    "verify accepts the full webhook body unchanged" in {
        val webhookBodyJson =
            """{
              |  "object": "whatsapp_business_account",
              |  "entry": [
              |    {
              |      "id": "102290129340398",
              |      "changes": [
              |        {
              |          "value": {
              |            "messaging_product": "whatsapp",
              |            "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" },
              |            "contacts": [ { "profile": { "name": "Sheena Nelson" }, "wa_id": "16505551234" } ],
              |            "messages": [
              |              {
              |                "from": "16505551234",
              |                "id": "wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA=",
              |                "timestamp": "1749416383",
              |                "type": "text",
              |                "text": { "body": "Does it come in another color?" }
              |              }
              |            ]
              |          },
              |          "field": "messages"
              |        }
              |      ]
              |    }
              |  ]
              |}""".stripMargin
        val secret = "appsecret"
        val bytes  = utf8(webhookBodyJson)
        val result = WhatsApp.Webhook.verify(webhookConfigOf(secret, "verify"), Present(signatureOf(secret, bytes)), bytes)
        assert(result == Result.unit)
    }

    "no signature failure renders the app secret" in {
        val secretText = Seq("SECRET", "APP", "9b4f17").mkString("-")
        val secret     = webhookConfigOf(secretText, "verify")
        val failures   = Chunk(Absent, Present("no-prefix"), Present("sha256=" + "0" * 64)).map { header =>
            failureOf[WhatsAppWebhookVerifyFailure](WhatsApp.Webhook.verify(secret, header, bodyBytes))
        }
        assert(failures == Chunk(
            WhatsAppSignatureMissingException(),
            WhatsAppSignatureMalformedException(),
            WhatsAppSignatureMismatchException()
        ))
        failures.foreach { e =>
            val rendered = BaseWhatsAppTest.renderings(e)
            assert(rendered.nonEmpty)
            rendered.foreach(text => assert(!text.contains(secretText), s"secret rendered by $e"))
        }
        succeed
    }

end WhatsAppWebhookSignatureTest
