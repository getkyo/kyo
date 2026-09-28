package kyo

class SlackConfigTest extends kyo.test.Test[Any]:

    "a rendered SlackConfig contains neither token" in {
        // Built from parts so the full secrets appear nowhere in this file's source text.
        val appSecret = Seq("xapp", "1", "A0CONFIGTEST", "w2Pn").mkString("-")
        val botSecret = Seq("xoxb", "22", "B0CONFIGTEST", "t8Vc").mkString("-")
        val rendered  = SlackConfig(SlackToken.AppLevel(appSecret), SlackToken.Bot(botSecret)).toString
        assert(!rendered.contains(appSecret), "app-level token rendered")
        assert(!rendered.contains(botSecret), "bot token rendered")
        assert(rendered.startsWith("SlackConfig(SlackToken.AppLevel(<redacted>),SlackToken.Bot(<redacted>),"), rendered)
    }

    private def build(keepAliveInterval: Maybe[Duration] = Present(30.seconds), ackDeadline: Duration = 3.seconds)
        : Result[Nothing, SlackConfig] =
        Result(SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"), keepAliveInterval, ackDeadline))

    import SlackInvalidConfigException.Problem
    import SlackInvalidConfigException.UrlProblem

    "a zero ackDeadline is rejected when the config is built" in {
        assert(build(ackDeadline = Duration.Zero) == Result.panic(SlackInvalidConfigException(Problem.AckDeadline(Duration.Zero))))
    }

    "an infinite ackDeadline is rejected when the config is built" in {
        assert(build(ackDeadline = Duration.Infinity) == Result.panic(SlackInvalidConfigException(Problem.AckDeadline(Duration.Infinity))))
    }

    "an infinite keepAliveInterval is rejected when the config is built" in {
        assert(build(keepAliveInterval = Present(Duration.Infinity)) == Result.panic(
            SlackInvalidConfigException(Problem.KeepAliveInterval(Duration.Infinity))
        ))
    }

    "a zero keepAliveInterval is rejected when the config is built" in {
        assert(build(keepAliveInterval = Present(Duration.Zero)) == Result.panic(
            SlackInvalidConfigException(Problem.KeepAliveInterval(Duration.Zero))
        ))
    }

    private def withBase(url: HttpUrl)(using Frame): Result[Nothing, SlackConfig] =
        Result(SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"), baseUrl = url))

    "a baseUrl that is not an absolute http or https url on a host in printable ASCII, or has user info, a query or a trailing slash, is rejected" in {
        val cases = Chunk(
            HttpUrl(Absent, "", 80, "/api", Absent)                                      -> UrlProblem.Scheme,
            HttpUrl(Present("ws"), "slack.com", 80, "/api", Absent)                      -> UrlProblem.Scheme,
            HttpUrl(Present("https"), "", 443, "/api", Absent)                           -> UrlProblem.Host,
            HttpUrl(Present("http"), "localhost", 80, "/api", Absent, Present("/tmp/s")) -> UrlProblem.UnixSocket,
            HttpUrl(Present("https"), "slâck.com", 443, "/api", Absent)                  -> UrlProblem.Character(10),
            HttpUrl(Present("https"), "user:secret@slack.com", 443, "/api", Absent)      -> UrlProblem.UserInfo,
            HttpUrl(Present("https"), "slack.com", 443, "/api", Present("x=1"))          -> UrlProblem.Query,
            HttpUrl(Present("https"), "slack.com", 443, "/api/", Absent)                 -> UrlProblem.TrailingSlash
        )
        assert(cases.map((url, _) => withBase(url)) == cases.map((_, p) => Result.panic(SlackInvalidConfigException(Problem.BaseUrl(p)))))
        assert(withBase(HttpUrl(Present("HTTP"), "127.0.0.1", 8080, "/", Absent)).isSuccess)
    }

    "kyo-http's parser drops a url's user info, so a parsed base url never carries one into the config" in {
        val secret = Seq("s3", "cret").mkString
        val parsed = withBase(HttpUrl.parse(s"https://user:$secret@slack.com/api").getOrThrow)
        assert(parsed.map(_.baseUrl) == Result.succeed(HttpUrl.parse("https://slack.com/api").getOrThrow))
        assert(parsed.exists(!_.toString.contains(secret)), parsed.toString)
    }

    "an absent keepAliveInterval and the smallest positive durations are accepted" in {
        val built = build(keepAliveInterval = Absent, ackDeadline = 1.nanos)
        assert(built.map(c => (c.keepAliveInterval, c.ackDeadline)) == Result.succeed((Absent, 1.nanos)))
    }

end SlackConfigTest
