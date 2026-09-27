package kyo

class TelegramWebhookOptionsTest extends kyo.test.Test[Any]:

    "max connections of 1 and of 100 are accepted, and absent keeps Telegram's default" in {
        assert(Chunk(1, 100).map(n => TelegramWebhookOptions(maxConnections = Present(n)).maxConnections) ==
            Chunk(Present(1), Present(100)))
        assert(TelegramWebhookOptions.default.maxConnections == Absent)
    }

    "max connections outside 1 to 100 panic with the value" in {
        import TelegramInvalidWebhookOptionsException.Problem
        val found = Chunk(0, 101, -1).map(n =>
            intercept[TelegramInvalidWebhookOptionsException](TelegramWebhookOptions(maxConnections = Present(n)))
        )
        assert(found == Chunk(0, 101, -1).map(n => TelegramInvalidWebhookOptionsException(Problem.MaxConnections(n))))
    }

end TelegramWebhookOptionsTest
