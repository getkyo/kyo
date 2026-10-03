package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramUserTest extends kyo.test.Test[Any]:

    "a User is Telegram's JSON, both ways" in {
        val json = """{"id":123456789,"is_bot":false,"first_name":"Ann","last_name":"Lee","username":"ann","language_code":"en"}"""
        val user = Telegram.User(Telegram.UserId(123456789L), false, "Ann", Present("Lee"), Present("ann"), Present("en"))
        assert(Json.decode[Telegram.User](json) == Result.succeed(user))
        assert(wire(user) == wire(json))
    }

    "a User with only the fields Telegram always sends" in {
        val json = """{"id":42,"is_bot":true,"first_name":"Standup"}"""
        val user = Telegram.User(Telegram.UserId(42L), true, "Standup")
        assert(Json.decode[Telegram.User](json) == Result.succeed(user))
        assert(wire(user) == wire(json))
    }

    "the largest id the Bot API allows, 2^52 - 1, survives both ways" in {
        val json = """{"id":4503599627370495,"is_bot":false,"first_name":"Max"}"""
        val user = Telegram.User(Telegram.UserId(4503599627370495L), false, "Max")
        assert(Json.decode[Telegram.User](json) == Result.succeed(user))
        assert(wire(user) == wire(json))
    }

    "getMe's capability flags and is_premium are dropped" in {
        import TelegramTest.Fixtures.{bot, botJson}
        val json = """{"id":7000000001,"is_bot":true,"first_name":"Kyo Bot","username":"kyo_bot","can_join_groups":true,""" +
            """"can_read_all_group_messages":false,"supports_inline_queries":false,"can_connect_to_business":false,""" +
            """"has_main_web_app":false,"is_premium":true}"""
        assert(Json.decode[Telegram.User](json) == Result.succeed(bot))
        assert(wire(bot) == wire(botJson))
    }

    "fields the model does not read are skipped" in {
        assert(Json.decode[Telegram.User]("""{"id":1,"is_bot":false,"first_name":"A","is_premium":true}""") ==
            Result.succeed(Telegram.User(Telegram.UserId(1L), false, "A")))
    }

end TelegramUserTest
