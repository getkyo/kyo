package kyo

class TeamsWebhookConfigTest extends kyo.test.Test[Any]:

    import TeamsInvalidWebhookConfigException.Problem

    "a path a request can reach is accepted, with or without a leading slash, and the default is the root" in {
        assert(Chunk("api/messages", "/api/messages", "", "bots/teams-1").map(p => TeamsWebhookConfig.init(p).map(_.path)) ==
            Chunk(Result.succeed("api/messages"), Result.succeed("/api/messages"), Result.succeed(""), Result.succeed("bots/teams-1")))
        assert(TeamsWebhookConfig.init().map(_.path) == Result.succeed(""))
    }

    "init refuses the first character no request path carries, at its position" in {
        assert(Chunk("api/messages?x", "api#m", "api messages", "api/\u0001", "api/é").map(p =>
            TeamsWebhookConfig.init(p).failure.map(_.problem)
        ) == Chunk(
            Present(Problem.PathCharacter(12)),
            Present(Problem.PathCharacter(3)),
            Present(Problem.PathCharacter(3)),
            Present(Problem.PathCharacter(4)),
            Present(Problem.PathCharacter(4))
        ))
        assert(TeamsWebhookConfig.init("a?").failure.map(_.getMessage).exists(_.contains("the path's character at position 1")))
    }

end TeamsWebhookConfigTest
