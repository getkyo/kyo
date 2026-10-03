package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramWebhookOptionsTest extends kyo.test.Test[Any]:

    "max connections of 1 and of 100 are accepted, and absent keeps Telegram's default" in {
        assert(Chunk(1, 100).map(n => Telegram.WebhookOptions.init(maxConnections = Present(n)).map(_.maxConnections)) ==
            Chunk(Result.succeed(Present(1)), Result.succeed(Present(100))))
        assert(Telegram.WebhookOptions.default.maxConnections == Absent)
        assert(Telegram.WebhookOptions.init() == Result.succeed(Telegram.WebhookOptions.default))
    }

    "max connections outside 1 to 100 fail with the value" in {
        import TelegramInvalidWebhookOptionsException.Problem
        assert(Chunk(0, 101, -1).map(n => Telegram.WebhookOptions.init(maxConnections = Present(n))) ==
            Chunk(0, 101, -1).map(n => Result.fail(TelegramInvalidWebhookOptionsException(Problem.MaxConnections(n)))))
    }

    "the options are setWebhook's fields, and max_connections outside 1 to 100 fails the decode" in {
        val options = Telegram.WebhookOptions.init(
            allowedUpdates = Present(Chunk(Telegram.Update.Type.Message)),
            maxConnections = Present(10),
            dropPendingUpdates = true,
            ipAddress = Present("203.0.113.7")
        ).getOrThrow
        val json = """{"allowed_updates":["message"],"max_connections":10,"drop_pending_updates":true,"ip_address":"203.0.113.7"}"""
        assert(wire(options) == wire(json))
        assert(Json.decode[Telegram.WebhookOptions](json) == Result.succeed(options))
        assert(Json.decode[Telegram.WebhookOptions]("""{"max_connections":0,"drop_pending_updates":false}""").isFailure)
    }

end TelegramWebhookOptionsTest
