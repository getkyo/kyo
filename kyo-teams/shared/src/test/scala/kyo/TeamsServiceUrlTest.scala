package kyo

class TeamsServiceUrlTest extends kyo.test.Test[Any]:

    import TeamsException.UrlProblem

    "the service URLs Teams sends are accepted and encode back to their text" in {
        val texts = Chunk(
            "https://smba.trafficmanager.net/amer/",
            "https://smba.trafficmanager.net/teams/",
            "https://smba.infra.gcc.teams.microsoft.com/teams",
            "http://127.0.0.1:3978/"
        )
        val urls = texts.map(t => Teams.ServiceUrl.init(t).getOrThrow)
        assert(urls.map(_.value) == texts)
        assert(urls.map(u => Json.encode(u)) == texts.map(t => "\"" + t + "\""))
        assert(Json.decode[Teams.ServiceUrl]("\"https://smba.trafficmanager.net/amer/\"") == Result.succeed(urls.head))
        assert(urls.head.url.host == "smba.trafficmanager.net")
    }

    "init refuses each URL the module cannot send to, naming why" in {
        def problem(text: String): Maybe[UrlProblem] = Teams.ServiceUrl.init(text).failure.map(_.problem)
        val problems                                 = Chunk(
            problem("smba.trafficmanager.net/amer/"),
            problem("/amer/"),
            problem("ftp://smba.trafficmanager.net/"),
            problem("wss://smba.trafficmanager.net/"),
            problem("http+unix://%2Fvar%2Frun%2Fx.sock/v3"),
            problem("https:///amer/"),
            problem("https://user:pass@smba.trafficmanager.net/amer/"),
            problem("https://smba.trafficmanager.net/amer/?x=1"),
            problem("https://smba.trafficmanager.net/amer/#x"),
            problem("https://smba.trafficmanager.net/amér/"),
            problem("https://smba.trafficmanager.net/a b/")
        )
        assert(
            problems == Chunk(
                Present(UrlProblem.Scheme),
                Present(UrlProblem.Scheme),
                Present(UrlProblem.Unparsable),
                Present(UrlProblem.Scheme),
                Present(UrlProblem.UnixSocket),
                Present(UrlProblem.Host),
                Present(UrlProblem.UserInfo),
                Present(UrlProblem.Query),
                Present(UrlProblem.Fragment),
                Present(UrlProblem.Character(34)),
                Present(UrlProblem.Character(33))
            ),
            s"got: $problems"
        )
    }

    "a refusal's message names the problem and not the URL" in {
        // Built from parts: the message of a failure built on this line quotes the line in development mode.
        val text    = Chunk("https://user:", "hunter", "2@example.com/").mkString
        val message = Teams.ServiceUrl.init(text).failure.map(_.getMessage).getOrElse("")
        assert(message.contains("Teams.ServiceUrl is not usable: it holds user info"))
        assert(!message.contains("hunter2"))
    }

    "the schema's refusal carries the decode call's Frame" in {
        val decodeSite = summon[Frame]
        Json.decode[Teams.ServiceUrl]("\"wss://smba.trafficmanager.net/\"")(using
            summon[Json],
            summon[Schema[Teams.ServiceUrl]],
            decodeSite
        ) match
            case Result.Failure(e: ConstructorRejectedException) =>
                e.rejection match
                    case leaf: TeamsInvalidServiceUrlException =>
                        assert(leaf.problem == TeamsException.UrlProblem.Scheme)
                        assert(leaf.frame == decodeSite, s"refused at ${leaf.frame}, decoded at $decodeSite")
                    case other => fail(s"expected a TeamsInvalidServiceUrlException, got $other")
            case other => fail(s"expected a ConstructorRejectedException, got $other")
        end match
    }

    "the schema refuses what init refuses, with the leaf init fails with" in {
        def problem(json: String): Maybe[TeamsException.UrlProblem] =
            Json.decode[Teams.ServiceUrl](json) match
                case Result.Failure(e: ConstructorRejectedException) =>
                    e.rejection match
                        case leaf: TeamsInvalidServiceUrlException => Present(leaf.problem)
                        case _                                     => Absent
                case _ => Absent
        assert(problem("\"wss://smba.trafficmanager.net/\"") == Present(TeamsException.UrlProblem.Scheme))
        assert(problem("\"https://smba.trafficmanager.net/?q\"") == Present(TeamsException.UrlProblem.Query))
    }

end TeamsServiceUrlTest
