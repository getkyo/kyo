package kyo

class DiscordWebhookConfigTest extends kyo.test.Test[Any]:

    import DiscordInvalidWebhookConfigException.Problem

    // RFC 8032 section 7.1, TEST 1: a valid Ed25519 public key.
    private val key = Discord.PublicKey.init("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a").getOrThrow

    "a path a request can reach is accepted, with or without a leading slash" in {
        assert(Chunk("", "interactions", "/discord/interactions").map(p => DiscordWebhookConfig.init(key, p).map(_.path)) ==
            Chunk("", "interactions", "/discord/interactions").map(Result.succeed(_)))
    }

    "the config holds the key it was given" in {
        assert(DiscordWebhookConfig.init(key).map(_.publicKey) == Result.succeed(key))
    }

    "a path character no request path carries is refused at its position" in {
        val cases = Chunk("hook?x" -> 4, "hook#x" -> 4, "ho ok" -> 2, "hook\n" -> 4, "hoök" -> 2)
        assert(cases.map((p, _) => DiscordWebhookConfig.init(key, p).failure) ==
            cases.map((_, at) => Present(DiscordInvalidWebhookConfigException(Problem.PathCharacter(at)))))
        assert(DiscordWebhookConfig.init(key, "hook?x").failure.exists(
            _.getMessage.contains("DiscordWebhookConfig is not usable: the path's character at position 4 cannot appear in a request path.")
        ))
    }

end DiscordWebhookConfigTest
