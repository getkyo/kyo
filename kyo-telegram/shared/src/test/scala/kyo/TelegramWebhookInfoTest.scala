package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramWebhookInfoTest extends kyo.test.Test[Any]:

    "a WebhookInfo with a webhook set is Telegram's object, both ways" in {
        val json =
            """{"url":"https://bot.example.com/telegram","has_custom_certificate":false,"pending_update_count":3,""" +
                """"ip_address":"203.0.113.7","last_error_date":1700000000,"last_error_message":"Connection refused",""" +
                """"max_connections":40,"allowed_updates":["message","callback_query"]}"""
        val info = Telegram.WebhookInfo(
            url = Present(HttpUrl.parse("https://bot.example.com/telegram").getOrThrow),
            hasCustomCertificate = false,
            pendingUpdateCount = 3,
            ipAddress = Present("203.0.113.7"),
            lastDeliveryFailureDate = Present(Instant.of(1700000000L.seconds, Duration.Zero)),
            lastDeliveryFailureMessage = Present("Connection refused"),
            maxConnections = Present(40),
            allowedUpdates = Chunk(Telegram.Update.Type.Message, Telegram.Update.Type.CallbackQuery)
        )
        assert(Json.decode[Telegram.WebhookInfo](json) == Result.succeed(info))
        assert(wire(info) == wire(json))
    }

    "no webhook is the empty url Telegram sends, read as Absent and written back as empty" in {
        val json = """{"url":"","has_custom_certificate":false,"pending_update_count":0}"""
        val info = Telegram.WebhookInfo(url = Absent, hasCustomCertificate = false, pendingUpdateCount = 0)
        assert(Json.decode[Telegram.WebhookInfo](json) == Result.succeed(info))
        assert(wire(info) == wire(json))
    }

    "allowed_updates naming kinds the module does not model are Other, and a missing list is empty and not written" in {
        val json = """{"url":"https://bot.example.com/telegram/hook","has_custom_certificate":true,"pending_update_count":0,""" +
            """"allowed_updates":["message","message_reaction","chat_member","poll","chat_join_request"]}"""
        import Telegram.Update.Type
        val url  = Present(HttpUrl.parse("https://bot.example.com/telegram/hook").getOrThrow)
        val info = Telegram.WebhookInfo(
            url,
            hasCustomCertificate = true,
            pendingUpdateCount = 0,
            allowedUpdates = Chunk(Type.Message, Type.MessageReaction, Type.ChatMember, Type.Other("poll"), Type.Other("chat_join_request"))
        )
        val bare = """{"url":"https://bot.example.com/telegram/hook","has_custom_certificate":false,"pending_update_count":2}"""
        assert(Json.decode[Telegram.WebhookInfo](json) == Result.succeed(info))
        assert(wire(info) == wire(json))
        assert(wire(Telegram.WebhookInfo(url, false, 2)) == wire(bare))
    }

    "last_synchronization_error_date, which the module does not model, is dropped" in {
        val bare = """{"url":"https://bot.example.com/telegram/hook","has_custom_certificate":false,"pending_update_count":2"""
        val info = Telegram.WebhookInfo(Present(HttpUrl.parse("https://bot.example.com/telegram/hook").getOrThrow), false, 2)
        assert(Json.decode[Telegram.WebhookInfo](bare + ""","last_synchronization_error_date":1735690100}""") == Result.succeed(info))
        assert(wire(info) == wire(bare + "}"))
    }

    "a url that does not parse fails the decode" in {
        assert(Json.decode[Telegram.WebhookInfo]("""{"url":"http://","has_custom_certificate":false,"pending_update_count":0}""").isFailure)
    }

end TelegramWebhookInfoTest
