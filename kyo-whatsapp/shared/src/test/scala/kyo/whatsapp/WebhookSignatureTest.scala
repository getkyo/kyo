package kyo.whatsapp

import kyo.*
import kyo.whatsapp.internal.Hmac

class WebhookSignatureTest extends BaseWhatsAppTest:

    val appSecret: String      = "Jefe"
    val bodyBytes: Array[Byte] = "what do ya want for nothing?".getBytes("UTF-8")
    // RFC-4231 test case 2 digest: HMAC-SHA256(key="Jefe", data="what do ya want for nothing?")
    val knownGoodHex: String    = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
    val knownGoodHeader: String = s"sha256=$knownGoodHex"

    "a known-good sha256= header verifies" in {
        val result = Webhook.verifySignature(appSecret, Present(knownGoodHeader), Span.from(bodyBytes))
        assert(result == Result.unit)
    }

    "a missing header yields Failure(Missing)" in {
        val result = Webhook.verifySignature(appSecret, Absent, Span.from(bodyBytes))
        assert(result == Result.fail(WhatsAppError.SignatureError.Missing))
    }

    "a header without the sha256= prefix yields Failure(Malformed)" in {
        val result = Webhook.verifySignature(appSecret, Present("abc123"), Span.from(bodyBytes))
        assert(result == Result.fail(WhatsAppError.SignatureError.Malformed))
    }

    "a non-hex remainder yields Failure(Malformed)" in {
        val result = Webhook.verifySignature(appSecret, Present("sha256=zzzz"), Span.from(bodyBytes))
        assert(result == Result.fail(WhatsAppError.SignatureError.Malformed))
    }

    "a wrong digest yields Failure(Mismatch)" in {
        val wrongHex    = "0" * 64
        val wrongHeader = s"sha256=$wrongHex"
        val result      = Webhook.verifySignature(appSecret, Present(wrongHeader), Span.from(bodyBytes))
        assert(result == Result.fail(WhatsAppError.SignatureError.Mismatch))
    }

    "a one-byte-tampered body yields Failure(Mismatch)" in {
        val tampered = bodyBytes.clone()
        tampered(0) = (tampered(0) ^ 0xff.toByte).toByte
        val result = Webhook.verifySignature(appSecret, Present(knownGoodHeader), Span.from(tampered))
        assert(result == Result.fail(WhatsAppError.SignatureError.Mismatch))
    }

    "an uppercase-hex header still matches" in {
        val upperHeader = s"sha256=${knownGoodHex.toUpperCase}"
        val result      = Webhook.verifySignature(appSecret, Present(upperHeader), Span.from(bodyBytes))
        assert(result == Result.unit)
    }

    "verifySignature is pure and never throws" in {
        val r1 = Webhook.verifySignature(appSecret, Absent, Span.empty)
        val r2 = Webhook.verifySignature(appSecret, Present("no-prefix"), Span.empty)
        val r3 = Webhook.verifySignature(appSecret, Present("sha256=abc"), Span.empty)
        assert(r1 == Result.fail(WhatsAppError.SignatureError.Missing))
        assert(r2 == Result.fail(WhatsAppError.SignatureError.Malformed))
        assert(r3 == Result.fail(WhatsAppError.SignatureError.Malformed))
        // verify the return type carries no effect row
        val _: Result[WhatsAppError.SignatureError, Unit] = r1
    }

    "verifySignature accepts the full webhook body unchanged" in {
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
        val secret    = "appsecret"
        val bytes     = webhookBodyJson.getBytes("UTF-8")
        val computed  = Hmac.hmacSha256(secret.getBytes("UTF-8"), bytes)
        val hexDigest = Hmac.hexLower(computed)
        val header    = s"sha256=$hexDigest"
        val result    = Webhook.verifySignature(secret, Present(header), Span.from(bytes))
        assert(result == Result.unit)
    }

end WebhookSignatureTest
