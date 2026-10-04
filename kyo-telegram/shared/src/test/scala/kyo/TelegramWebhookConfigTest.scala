package kyo

class TelegramWebhookConfigTest extends kyo.test.Test[Any]:

    private val secret = Telegram.SecretToken.init("hook_Secret-1").getOrThrow

    "a path with or without a leading slash is accepted, since the route splits on slashes" in {
        assert(TelegramWebhookConfig.init(secret, "/hooks/telegram").map(_.path) == Result.succeed("/hooks/telegram"))
        assert(TelegramWebhookConfig.init(secret, "hooks/telegram").map(_.path) == Result.succeed("hooks/telegram"))
    }

    "a path holding a character no request path can hold fails, naming its position" in {
        import TelegramInvalidWebhookConfigException.Problem
        val bad = Chunk("hooks?x" -> 5, "hooks#x" -> 5, "a b" -> 1, "a\u0001" -> 1, "café" -> 3)
        assert(bad.map((path, _) => TelegramWebhookConfig.init(secret, path)) ==
            bad.map((_, at) => Result.fail(TelegramInvalidWebhookConfigException(Problem.PathCharacter(at)))))
    }

    "the rendered config does not contain the secret" in {
        assert(!TelegramWebhookConfig.init(secret, "hooks/telegram").getOrThrow.toString.contains("hook_Secret-1"))
    }

end TelegramWebhookConfigTest
