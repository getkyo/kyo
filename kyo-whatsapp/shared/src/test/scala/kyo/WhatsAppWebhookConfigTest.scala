package kyo

import WhatsAppInvalidWebhookConfigException.Problem

class WhatsAppWebhookConfigTest extends BaseWhatsAppTest:

    val appSecret   = WhatsAppAppSecret("appsecret")
    val verifyToken = WhatsAppVerifyToken("verify")

    def refused(path: String)(using Frame): Result[WhatsAppInvalidWebhookConfigException, WhatsAppWebhookConfig] =
        Result.catching[WhatsAppInvalidWebhookConfigException](WhatsAppWebhookConfig(appSecret, verifyToken, path))

    "a path with or without a leading slash is accepted, since the route splits on slashes" in {
        assert(WhatsAppWebhookConfig(appSecret, verifyToken, "/hooks/whatsapp").path == "/hooks/whatsapp")
    }

    "a path holding a character no request path can hold panics at construction, naming its position" in {
        val bad = Chunk("hooks?x" -> 5, "hooks#x" -> 5, "a b" -> 1, "a\u0001" -> 1, "café" -> 3)
        assert(bad.map((path, _) => refused(path)) ==
            bad.map((_, at) => Result.fail(WhatsAppInvalidWebhookConfigException(Problem.PathCharacter(at)))))
    }

end WhatsAppWebhookConfigTest
