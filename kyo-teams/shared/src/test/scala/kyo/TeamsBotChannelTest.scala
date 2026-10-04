package kyo

class TeamsBotChannelTest extends kyo.test.Test[Any]:

    "msteams reads as MsTeams and writes back as msteams" in {
        assert(Json.decode[Teams.BotChannel]("\"msteams\"") == Result.succeed(Teams.BotChannel.MsTeams))
        assert(Json.encode[Teams.BotChannel](Teams.BotChannel.MsTeams) == "\"msteams\"")
    }

    "another channel's name is kept and written back as it came" in {
        assert(Json.decode[Teams.BotChannel]("\"webchat\"") == Result.succeed(Teams.BotChannel.Other("webchat")))
        assert(Json.encode[Teams.BotChannel](Teams.BotChannel.Other("directline")) == "\"directline\"")
    }

    "a channel that is not a string does not decode" in {
        assert(Json.decode[Teams.BotChannel]("{\"name\":\"msteams\"}").isFailure)
    }

end TeamsBotChannelTest
