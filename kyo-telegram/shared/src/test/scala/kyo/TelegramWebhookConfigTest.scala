package kyo

class TelegramWebhookConfigTest extends kyo.test.Test[Any]:

    private val secret = TelegramSecretToken("hook_Secret-1")

    "a path with or without a leading slash is accepted, since the route splits on slashes" in {
        assert(TelegramWebhookConfig(secret, "/hooks/telegram").path == "/hooks/telegram")
    }

    "a path holding a character no request path can hold panics at construction, naming its position" in {
        import TelegramInvalidWebhookConfigException.Problem
        val bad = Chunk("hooks?x" -> 5, "hooks#x" -> 5, "a b" -> 1, "a\u0001" -> 1, "café" -> 3)
        assert(bad.map((path, _) => intercept[TelegramInvalidWebhookConfigException](TelegramWebhookConfig(secret, path))) ==
            bad.map((_, at) => TelegramInvalidWebhookConfigException(Problem.PathCharacter(at))))
    }

    "the rendered config does not contain the secret" in {
        assert(!TelegramWebhookConfig(secret, "hooks/telegram").toString.contains("hook_Secret-1"))
    }

end TelegramWebhookConfigTest
