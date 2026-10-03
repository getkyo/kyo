package kyo

class TeamsWebhookPathTest extends kyo.test.Test[Any]:

    import TeamsInvalidWebhookPathException.Problem

    "a path a request can reach is accepted, with or without a leading slash" in {
        assert(Chunk("api/messages", "/api/messages", "", "bots/teams-1").map(p => Teams.WebhookPath.init(p).map(_.value)) ==
            Chunk(Result.succeed("api/messages"), Result.succeed("/api/messages"), Result.succeed(""), Result.succeed("bots/teams-1")))
    }

    "init refuses the first character no request path carries, at its position" in {
        assert(Chunk("api/messages?x", "api#m", "api messages", "api/\u0001", "api/é").map(p =>
            Teams.WebhookPath.init(p).failure.map(_.problem)
        ) == Chunk(
            Present(Problem.Character(12)),
            Present(Problem.Character(3)),
            Present(Problem.Character(3)),
            Present(Problem.Character(4)),
            Present(Problem.Character(4))
        ))
        assert(Teams.WebhookPath.init("a?").failure.map(_.getMessage).exists(_.contains("the character at position 1")))
    }

end TeamsWebhookPathTest
