package kyo

class TeamsRawJsonTest extends kyo.test.Test[Any]:

    "it renders redacted, not what it holds, and compares by value" in {
        val text = """{"secret":"what a user wrote"}"""
        val json = Teams.RawJson(Json.decode[Structure.Value](text).getOrThrow)
        assert(json.toString == "Teams.RawJson(<redacted>)")
        assert(!json.toString.contains("user wrote"))
        assert(json == Teams.RawJson(Json.decode[Structure.Value](text).getOrThrow))
    }

    "its schema is the JSON value itself" in {
        val text = """{"a":[1,"b",null,true]}"""
        val json = Json.decode[Teams.RawJson](text)
        assert(json.map(_.value) == Result.succeed(text))
        assert(json.map(j => Json.encode(j)) == Result.succeed(text))
    }

end TeamsRawJsonTest
