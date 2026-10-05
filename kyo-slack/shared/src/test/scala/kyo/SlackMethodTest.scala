package kyo

import kyo.SlackLiterals.*

class SlackMethodTest extends kyo.test.Test[Any]:

    private def problem(name: String): Maybe[SlackInvalidMethodException.Problem] = SlackMethod.init(name).failure.map(_.problem)

    "a Web API method name of letters, digits, dots and underscores is accepted as given" in {
        assert(methodOf("users.list").value == "users.list")
        assert(methodOf("admin.conversations.setTeams").value == "admin.conversations.setTeams")
        assert(methodOf("oauth.v2.access").value == "oauth.v2.access")
        assert(methodOf("bots_info").value == "bots_info")
    }

    "a name that would change the request url fails init, never stripped" in {
        import SlackInvalidMethodException.Problem
        assert(problem("") == Present(Problem.Empty))
        assert(problem("users.list?cursor=abc") == Present(Problem.Character(10)))
        assert(problem("users.list#frag") == Present(Problem.Character(10)))
        assert(problem("../oauth.access") == Present(Problem.Character(2)))
        assert(problem("chat postMessage") == Present(Problem.Character(4)))
        assert(problem("chät.update") == Present(Problem.Character(2)))
    }

    "a name of only dots, a . or .. segment after the base url, fails init" in {
        import SlackInvalidMethodException.Problem
        assert(Chunk(".", "..", "...").map(problem) == Chunk.fill(3)(Present(Problem.Dots)))
        assert(problem("a..b") == Absent)
    }

    "the failure names a position and not the name" in {
        // Built from parts: the rendered message quotes the source lines around the call site.
        val query   = Seq("tok", "en=abc").mkString
        val name    = "users.list?" + query
        val message = refused(SlackMethod.init(name)).getMessage
        assert(message.nonEmpty && !message.contains(query), message)
    }

end SlackMethodTest
