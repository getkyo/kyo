package kyo

class DiscordGatewayInfoTest extends kyo.test.Test[Any]:

    import Discord.*

    "GET /gateway/bot's answer decodes, reset_after as milliseconds" in {
        val json =
            """{"url":"wss://gateway.discord.gg","shards":9,"session_start_limit":{"total":1000,"remaining":999,"reset_after":14400000,"max_concurrency":1}}"""
        val info = Json.decode[GatewayInfo](json)
        assert(info == Result.succeed(GatewayInfo("wss://gateway.discord.gg", 9, GatewayInfo.SessionStartLimit(1000, 999, 4.hours, 1))))
        assert(info.map(i => Json.encode(i)) == Result.succeed(json))
    }

end DiscordGatewayInfoTest
