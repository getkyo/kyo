package kyo

import kyo.SlackLiterals.*

class SlackConfigTest extends kyo.test.Test[Any]:

    "a rendered SlackConfig contains neither token" in {
        // Built from parts so the full secrets appear nowhere in this file's source text.
        val appSecret = Seq("xapp", "1", "A0CONFIGTEST", "w2Pn").mkString("-")
        val botSecret = Seq("xoxb", "22", "B0CONFIGTEST", "t8Vc").mkString("-")
        val rendered  = configOf(appLevelOf(appSecret), botOf(botSecret)).toString
        assert(!rendered.contains(appSecret), "app-level token rendered")
        assert(!rendered.contains(botSecret), "bot token rendered")
        assert(rendered.startsWith("SlackConfig(SlackToken.AppLevel(<redacted>),SlackToken.Bot(<redacted>),"), rendered)
    }

    private def build(keepAliveInterval: Maybe[Duration] = Present(30.seconds), ackDeadline: Duration = 3.seconds)(using
        Frame
    ): Result[SlackInvalidConfigException, SlackConfig] =
        SlackConfig.init(appLevelOf("xapp-1"), botOf("xoxb-1"), keepAliveInterval, ackDeadline)

    import SlackInvalidConfigException.Problem
    import SlackInvalidConfigException.UrlProblem

    "positional init arguments retain their meaning when file transfer options are omitted" in {
        val positional = SlackConfig.init(
            appLevelOf("xapp-1"),
            botOf("xoxb-1"),
            Absent,
            2.seconds,
            SlackConfig.Reconnect.Off,
            SlackConfig.SlackApi,
            4.seconds,
            5.seconds,
            6.mb
        )
        val named = SlackConfig.init(
            appLevelOf("xapp-1"),
            botOf("xoxb-1"),
            keepAliveInterval = Absent,
            ackDeadline = 2.seconds,
            reconnect = SlackConfig.Reconnect.Off,
            requestTimeout = 4.seconds,
            connectTimeout = 5.seconds,
            maxResponseLength = 6.mb
        )
        assert(positional == named)
    }

    "a zero ackDeadline fails init" in {
        assert(build(ackDeadline = Duration.Zero) == Result.fail(SlackInvalidConfigException(Problem.AckDeadline(Duration.Zero))))
    }

    "an infinite ackDeadline fails init" in {
        assert(build(ackDeadline = Duration.Infinity) == Result.fail(SlackInvalidConfigException(Problem.AckDeadline(Duration.Infinity))))
    }

    "an infinite keepAliveInterval fails init" in {
        assert(build(keepAliveInterval = Present(Duration.Infinity)) == Result.fail(
            SlackInvalidConfigException(Problem.KeepAliveInterval(Duration.Infinity))
        ))
    }

    "a zero keepAliveInterval fails init" in {
        assert(build(keepAliveInterval = Present(Duration.Zero)) == Result.fail(
            SlackInvalidConfigException(Problem.KeepAliveInterval(Duration.Zero))
        ))
    }

    private def withBase(url: HttpUrl)(using Frame): Result[SlackInvalidConfigException, SlackConfig] =
        SlackConfig.init(appLevelOf("xapp-1"), botOf("xoxb-1"), baseUrl = url)

    "a baseUrl that is not an absolute http or https url on a host in printable ASCII, or has user info, a query or a trailing slash, fails init" in {
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
        assert(cases.map((url, _) => withBase(url)) == cases.map((_, p) => Result.fail(SlackInvalidConfigException(Problem.BaseUrl(p)))))
        assert(withBase(HttpUrl(Present("HTTP"), "127.0.0.1", 8080, "/", Absent)).isSuccess)
    }

    "kyo-http's parser drops a url's user info, so a parsed base url never carries one into the config" in {
        val secret = Seq("s3", "cret").mkString
        val parsed = withBase(urlOf(s"https://user:$secret@slack.com/api"))
        assert(parsed.map(_.baseUrl) == Result.succeed(urlOf("https://slack.com/api")))
        assert(parsed.exists(!_.toString.contains(secret)), parsed.toString)
    }

    "an absent keepAliveInterval and the smallest positive durations are accepted" in {
        val built = build(keepAliveInterval = Absent, ackDeadline = 1.nanos)
        assert(built.map(c => (c.keepAliveInterval, c.ackDeadline)) == Result.succeed((Absent, 1.nanos)))
    }

    "a bounded field's setter runs the same check as init, and leaves the config unchanged on a failure" in {
        val config = configOf(appLevelOf("xapp-1"), botOf("xoxb-1"))
        assert(config.ackDeadline(Duration.Zero) == Result.fail(SlackInvalidConfigException(Problem.AckDeadline(Duration.Zero))))
        assert(config.keepAliveInterval(Present(Duration.Infinity)) ==
            Result.fail(SlackInvalidConfigException(Problem.KeepAliveInterval(Duration.Infinity))))
        assert(config.requestTimeout(Duration.Zero) == Result.fail(SlackInvalidConfigException(Problem.RequestTimeout(Duration.Zero))))
        assert(config.connectTimeout(Duration.Infinity) ==
            Result.fail(SlackInvalidConfigException(Problem.ConnectTimeout(Duration.Infinity))))
        assert(config.baseUrl(HttpUrl(Present("https"), "slack.com", 443, "/api/", Absent)) ==
            Result.fail(SlackInvalidConfigException(Problem.BaseUrl(UrlProblem.TrailingSlash))))
        assert(config.ackDeadline(1.second).map(_.ackDeadline) == Result.succeed(1.second))
        assert(config.baseUrl(urlOf("http://127.0.0.1:8080/api")).map(_.baseUrl) == Result.succeed(urlOf("http://127.0.0.1:8080/api")))
        assert(config.ackDeadline(1.second).map(others) == Result.succeed(others(config)))
    }

    private def others(c: SlackConfig) =
        (
            c.appLevel,
            c.bot,
            c.keepAliveInterval,
            c.reconnect,
            c.baseUrl,
            c.fileHosts,
            c.shareInterval,
            c.shareTimeout,
            c.requestTimeout,
            c.connectTimeout,
            c.maxResponseLength
        )

    "an upload reads its share every 500 milliseconds for up to 30 seconds by default, and a zero or infinite bound fails init and its setter" in {
        val config                                           = configOf(appLevelOf("xapp-1"), botOf("xoxb-1"))
        def withShare(interval: Duration, timeout: Duration) =
            SlackConfig.init(appLevelOf("xapp-1"), botOf("xoxb-1"), shareInterval = interval, shareTimeout = timeout)
        assert((config.shareInterval, config.shareTimeout) == (500.millis, 30.seconds))
        assert(Chunk(Duration.Zero, Duration.Infinity).map(d => (withShare(d, 1.second), withShare(1.second, d))) ==
            Chunk(Duration.Zero, Duration.Infinity).map(d =>
                (
                    Result.fail(SlackInvalidConfigException(Problem.ShareInterval(d))),
                    Result.fail(SlackInvalidConfigException(Problem.ShareTimeout(d)))
                )
            ))
        assert(config.shareInterval(Duration.Zero) == Result.fail(SlackInvalidConfigException(Problem.ShareInterval(Duration.Zero))))
        assert(config.shareTimeout(Duration.Infinity) ==
            Result.fail(SlackInvalidConfigException(Problem.ShareTimeout(Duration.Infinity))))
        assert(config.shareInterval(1.second).flatMap(_.shareTimeout(5.seconds)).map(c => (c.shareInterval, c.shareTimeout)) ==
            Result.succeed((1.second, 5.seconds)))
    }

    "a fileHost that is not an absolute http or https url on a host, or has user info or a query, fails init and the setter at its position" in {
        val good   = urlOf("https://files.slack.com")
        val config = configOf(appLevelOf("xapp-1"), botOf("xoxb-1"))
        val cases  = Chunk(
            HttpUrl(Present("ftp"), "files.slack.com", 21, "/", Absent)                -> UrlProblem.Scheme,
            HttpUrl(Present("https"), "", 443, "/", Absent)                            -> UrlProblem.Host,
            HttpUrl(Present("https"), "user:secret@files.slack.com", 443, "/", Absent) -> UrlProblem.UserInfo,
            HttpUrl(Present("https"), "files.slack.com", 443, "/", Present("x=1"))     -> UrlProblem.Query
        )
        assert(cases.map((url, _) => SlackConfig.init(appLevelOf("xapp-1"), botOf("xoxb-1"), fileHosts = Chunk(good, url))) ==
            cases.map((_, p) => Result.fail(SlackInvalidConfigException(Problem.FileHost(1, p)))))
        assert(cases.map((url, _) => config.fileHosts(Chunk(url, good))) ==
            cases.map((_, p) => Result.fail(SlackInvalidConfigException(Problem.FileHost(0, p)))))
    }

    "fileHosts is https://files.slack.com by default, and its setter changes it alone" in {
        val config = configOf(appLevelOf("xapp-1"), botOf("xoxb-1"))
        val hosts  = Chunk(urlOf("https://files.slack.com"), urlOf("http://127.0.0.1:8080"))
        assert(config.fileHosts.map(u => (u.scheme, u.host, u.port)) == Chunk((Present("https"), "files.slack.com", 443)))
        assert(config.fileHosts(hosts).map(_.fileHosts) == Result.succeed(hosts))
        assert(config.fileHosts(hosts).flatMap(_.fileHosts(config.fileHosts)) == Result.succeed(config))
        assert(config.fileHosts(Chunk.empty).map(_.fileHosts) == Result.succeed(Chunk.empty))
    }

    "a url is a file host's when its scheme and host match in any ASCII case and its port exactly, whatever its path" in {
        val hosts = SlackConfig.FileHosts
        assert(SlackConfig.allowed(hosts, urlOf("https://files.slack.com/files-pri/T1-F1/a.png")))
        assert(SlackConfig.allowed(hosts, HttpUrl(Present("HTTPS"), "FILES.Slack.com", 443, "/a", Absent)))
        assert(!SlackConfig.allowed(hosts, urlOf("http://files.slack.com/a")))
        assert(!SlackConfig.allowed(hosts, urlOf("https://files.slack.com:8443/a")))
        assert(!SlackConfig.allowed(hosts, urlOf("https://files.slack.com.example.org/a")))
        assert(!SlackConfig.allowed(Chunk.empty, urlOf("https://files.slack.com/a")))
    }

    "a response length reaches kyo-http as given, for kyo-http to narrow where it reads a body" in {
        val config  = configOf(appLevelOf("xapp-1"), botOf("xoxb-1"))
        val sizes   = Chunk(0.bytes, 1.bytes, 16.mb, (Int.MaxValue.toLong + 1).bytes)
        val lengths = sizes.map(v => SlackConfig.httpConfig(config.maxResponseLength(v)).maxResponseLength)
        assert(lengths == sizes)
    }

    "a response length bound is kept as given, zero and beyond Int.MaxValue bytes included, for the client to narrow" in {
        val values = Chunk(0.bytes, 1.bytes, (Int.MaxValue.toLong + 1).bytes)
        val config = configOf(appLevelOf("xapp-1"), botOf("xoxb-1"))
        assert(values.map(v => SlackConfig.init(appLevelOf("xapp-1"), botOf("xoxb-1"), maxResponseLength = v).map(_.maxResponseLength)) ==
            values.map(Result.succeed(_)))
        assert(values.map(v => config.maxResponseLength(v).maxResponseLength) == values)
        assert(config.maxResponseLength(0.bytes).maxResponseLength(config.maxResponseLength) == config)
    }

    "the tokens and the reconnect policy, which have no bounds, are replaced directly" in {
        val config = configOf(appLevelOf("xapp-1"), botOf("xoxb-1"))
        assert(config.appLevel(appLevelOf("xapp-2")).appLevel == appLevelOf("xapp-2"))
        assert(config.bot(botOf("xoxb-2")).bot == botOf("xoxb-2"))
        assert(config.reconnect(SlackConfig.Reconnect.Off).reconnect == SlackConfig.Reconnect.Off)
        assert(config.reconnect(SlackConfig.Reconnect.Off).ackDeadline == config.ackDeadline)
    }

end SlackConfigTest
