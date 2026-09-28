package kyo

class SlackMethodTest extends kyo.test.Test[Any]:

    private def refused(name: String): Maybe[SlackInvalidMethodException.Problem] =
        try
            SlackMethod(name)
            Absent
        catch
            case e: SlackInvalidMethodException => Present(e.problem)

    "a Web API method name of letters, digits, dots and underscores is accepted as given" in {
        assert(SlackMethod("users.list").value == "users.list")
        assert(SlackMethod("admin.conversations.setTeams").value == "admin.conversations.setTeams")
        assert(SlackMethod("oauth.v2.access").value == "oauth.v2.access")
        assert(SlackMethod("bots_info").value == "bots_info")
    }

    "a name that would change the request url is refused at construction, never stripped" in {
        import SlackInvalidMethodException.Problem
        assert(refused("") == Present(Problem.Empty))
        assert(refused("users.list?cursor=abc") == Present(Problem.Character(10)))
        assert(refused("users.list#frag") == Present(Problem.Character(10)))
        assert(refused("../oauth.access") == Present(Problem.Character(2)))
        assert(refused("chat postMessage") == Present(Problem.Character(4)))
        assert(refused("chät.update") == Present(Problem.Character(2)))
    }

    "a name of only dots, a . or .. segment after the base url, is refused" in {
        import SlackInvalidMethodException.Problem
        assert(Chunk(".", "..", "...").map(refused) == Chunk.fill(3)(Present(Problem.Dots)))
        assert(refused("a..b") == Absent)
    }

    "the refusal names a position and not the name" in {
        // The name is held in a val: the rendered message quotes the call site's source line.
        val name    = "users.list?token=abc"
        val message =
            try
                SlackMethod(name)
                ""
            catch
                case e: SlackInvalidMethodException => e.getMessage
        assert(message.nonEmpty && !message.contains("token=abc"), message)
    }

end SlackMethodTest
