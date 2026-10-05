package kyo

import WhatsAppInvalidWebhookConfigException.Problem

class WhatsAppWebhookConfigTest extends BaseWhatsAppTest:

    val appSecret   = WhatsAppAppSecret.init("appsecret").getOrThrow
    val verifyToken = WhatsAppVerifyToken.init("verify").getOrThrow

    "a path with or without a leading slash is accepted, since the route splits on slashes" in {
        assert(WhatsAppWebhookConfig.init(appSecret, verifyToken, "/hooks/whatsapp").map(_.path) == Result.succeed("/hooks/whatsapp"))
        assert(WhatsAppWebhookConfig.init(appSecret, verifyToken, "hooks").map(_.path) == Result.succeed("hooks"))
    }

    "a path holding a character no request path can hold is refused, naming its position" in {
        val bad = Chunk("hooks?x" -> 5, "hooks#x" -> 5, "a b" -> 1, "a\u0001" -> 1, "café" -> 3)
        assert(bad.map((path, _) => WhatsAppWebhookConfig.init(appSecret, verifyToken, path)) ==
            bad.map((_, at) => Result.fail(WhatsAppInvalidWebhookConfigException(Problem.PathCharacter(at)))))
    }

end WhatsAppWebhookConfigTest
