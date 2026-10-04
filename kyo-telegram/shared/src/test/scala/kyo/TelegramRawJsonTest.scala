package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramRawJsonTest extends kyo.test.Test[Any]:

    private def tree(json: String): Structure.Value =
        Json.decode[Structure.Value](json)(using summon[Json], Structure.Value.valueSchema).getOrThrow

    "a raw payload is the JSON itself, not a string holding it" in {
        val json = """{"poll":{"id":"5","options":[{"text":"yes","voter_count":2}],"is_closed":false}}"""
        assert(Json.decode[Telegram.RawJson](json).map(raw => wire(raw.value)) == Result.succeed(wire(json)))
        assert(wire(Telegram.RawJson(tree(json))) == wire(json))
    }

    "the rendering shows the length and nothing of the payload" in {
        val raw = Telegram.RawJson(tree("""{"text":"a private message"}"""))
        assert(raw.toString == "Telegram.RawJson(28 characters)")
    }

end TelegramRawJsonTest
