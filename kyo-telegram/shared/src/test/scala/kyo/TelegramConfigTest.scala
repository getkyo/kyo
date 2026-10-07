package kyo

class TelegramConfigTest extends kyo.test.Test[Any]:

    // Built from parts so the whole secret appears nowhere in this file's source text.
    private val secret = Seq("987654", "cfgTEST_secret-Part").mkString(":")
    private val token  = Telegram.Token.init(secret).getOrThrow

    private def url(text: String)(using Frame): HttpUrl = HttpUrl.parse(text).getOrThrow

    "the request config the module runs every call under states each field, takes TLS and transport from the config, and keeps nothing of the caller's" in {
        val tls       = HttpTlsConfig(sniHostname = Present("bot.example.com"))
        val transport = HttpTransportConfig.default.maxHeaderSize(128.kib)
        val config    =
            TelegramConfig.init(token, connectTimeout = 3.seconds, maxResponseLength = 4.mib, tls = tls, transport = transport).getOrThrow
        val request = kyo.internal.telegram.BotApi.requestConfig(config, 7.seconds)
        assert((
            request.baseUrl,
            request.timeout,
            request.connectTimeout,
            request.followRedirects,
            request.maxRedirects,
            request.retrySchedule,
            request.transportConfig,
            request.tls,
            request.maxResponseLength,
            request.autoFilters,
            request.clientFilter eq HttpFilter.noop
        ) == (
            Absent,
            7.seconds,
            3.seconds,
            false,
            0,
            Absent,
            transport,
            tls,
            4.mib,
            false,
            true
        ))
        assert(Chunk(HttpStatus(500), HttpStatus(404)).map(request.retryOn) == Chunk(true, false))
    }

    "a response length reaches kyo-http as given, for kyo-http to narrow where it reads a body" in {
        val sizes   = Chunk(0.bytes, 1.bytes, 20.mib, (Int.MaxValue.toLong + 1).bytes)
        val lengths = sizes.map { length =>
            val config = TelegramConfig.init(token, maxResponseLength = length).getOrThrow
            kyo.internal.telegram.BotApi.requestConfig(config, 7.seconds).maxResponseLength
        }
        assert(lengths == sizes)
    }

    "a rendered config shows the token redacted and not its value" in {
        val rendered = TelegramConfig.init(token).getOrThrow.toString
        assert(rendered.contains("Telegram.Token(<redacted>)"))
        assert(!rendered.contains(secret))
        assert(!rendered.contains("cfgTEST_secret-Part"))
    }

    "the bounds Telegram gives are accepted" in {
        val config = TelegramConfig.init(token, baseUrl = url("http://127.0.0.1:8081"), pollTimeout = 1.second, pollLimit = 1)
        assert(config.map(c => (c.baseUrl, c.pollTimeout, c.pollLimit)) == Result.succeed((url("http://127.0.0.1:8081"), 1.second, 1)))
        val withPath = TelegramConfig.init(token, baseUrl = url("https://proxy.example.com/telegram"))
        assert(withPath.map(_.baseUrl) == Result.succeed(url("https://proxy.example.com/telegram")))
        val widest = TelegramConfig.init(token, pollTimeout = Int.MaxValue.toLong.seconds, pollLimit = 100, requestTimeout = 1.nano)
        assert(widest.map(c => (c.pollTimeout, c.pollLimit, c.requestTimeout)) ==
            Result.succeed((Int.MaxValue.toLong.seconds, 100, 1.nano)))
    }

    "a response length bound is kept as given, zero and beyond Int.MaxValue bytes included, for the client to narrow" in {
        val values = Chunk(0.bytes, 1.bytes, (Int.MaxValue.toLong + 1).bytes)
        assert(values.map(v => TelegramConfig.init(token, maxResponseLength = v).map(_.maxResponseLength)) ==
            values.map(Result.succeed(_)))
    }

    "init fails with the setting and what is wrong" - {
        import TelegramInvalidConfigException.Problem
        import TelegramInvalidConfigException.UrlProblem

        def rejected(config: Result[TelegramInvalidConfigException, TelegramConfig]): Maybe[TelegramInvalidConfigException] =
            config.failure

        def message(config: Result[TelegramInvalidConfigException, TelegramConfig]): String =
            config.failure.fold("no failure")(_.getMessage)

        "a base URL that is not http or https, holds a query, or ends with a slash after a path" in {
            val found = Chunk(
                rejected(TelegramConfig.init(token, baseUrl = url("wss://api.telegram.org"))),
                rejected(TelegramConfig.init(token, baseUrl = HttpUrl.fromUri("/bot"))),
                rejected(TelegramConfig.init(token, baseUrl = HttpUrl.fromUri("hooks/x"))),
                rejected(TelegramConfig.init(token, baseUrl = HttpUrl(Present("ftp"), "api.telegram.org", 21, "/", Absent))),
                rejected(TelegramConfig.init(token, baseUrl = url("https://api.telegram.org?user=me"))),
                rejected(TelegramConfig.init(token, baseUrl = url("https://proxy.example.com/telegram/"))),
                rejected(TelegramConfig.init(token, baseUrl = HttpUrl(Present("https"), "", 443, "/", Absent))),
                rejected(TelegramConfig.init(token, baseUrl = url("http+unix://%2Ftmp%2Fbot.sock/api")))
            )
            assert(found == Chunk(
                UrlProblem.Scheme,
                UrlProblem.Scheme,
                UrlProblem.Scheme,
                UrlProblem.Scheme,
                UrlProblem.Query,
                UrlProblem.TrailingSlash,
                UrlProblem.Host,
                UrlProblem.UnixSocket
            ).map(p => Present(TelegramInvalidConfigException(Problem.BaseUrl(p)))))
        }

        "a base URL holding a character outside printable ASCII, in its host or its path, names its position" in {
            val found = Chunk(
                rejected(TelegramConfig.init(token, baseUrl = HttpUrl(Present("https"), "bøt.example.com", 443, "/", Absent))),
                rejected(TelegramConfig.init(token, baseUrl = HttpUrl(Present("https"), "proxy.example.com", 443, "/a\u0001b", Absent)))
            )
            assert(found == Chunk(
                Present(TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Character(9)))),
                Present(TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Character(27))))
            ))
        }

        "a base URL whose host carries user info is refused, and the message holds none of it" in {
            val credential = Seq("user", "cfgTEST_hostSecret").mkString(":")
            val config     =
                TelegramConfig.init(token, baseUrl = HttpUrl(Present("https"), s"$credential@api.telegram.org", 443, "/", Absent))
            assert(rejected(config) == Present(TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.UserInfo))))
            assert(message(config).contains("TelegramConfig.baseUrl must hold no user info."))
            assert(!message(config).contains(credential))
        }

        "user info in a parsed base URL is dropped by the parser, so the config holds and renders none of it" in {
            val credential = Seq("user", "cfgTEST_parsedSecret").mkString(":")
            val config     = TelegramConfig.init(token, baseUrl = url(s"https://$credential@api.telegram.org")).getOrThrow
            assert(config.baseUrl == TelegramConfig.TelegramServer)
            assert(!config.toString.contains(credential))
        }

        "a scheme in upper case is http or https all the same" in {
            val upper = HttpUrl(Present("HTTPS"), "api.telegram.org", 443, "/", Absent)
            assert(TelegramConfig.init(token, baseUrl = upper).map(_.baseUrl) == Result.succeed(upper))
        }

        "a base URL with or without the root's slash is the same server" in {
            assert(TelegramConfig.init(token, baseUrl = url("https://api.telegram.org/")).map(_.baseUrl) ==
                TelegramConfig.init(token).map(_.baseUrl))
        }

        "the base URL's messages, which do not render the URL" in {
            val marker = Seq("user", "marker").mkString("-")
            val query  = message(TelegramConfig.init(token, baseUrl = url(s"https://api.telegram.org?$marker")))
            assert(query.contains("TelegramConfig.baseUrl must hold no query."))
            assert(!query.contains(marker))
            assert(message(TelegramConfig.init(token, baseUrl = url("ws://h"))).contains(
                "TelegramConfig.baseUrl must be an http or https URL."
            ))
            assert(message(TelegramConfig.init(token, baseUrl = url("https://h/p/"))).contains(
                "TelegramConfig.baseUrl must not end with / after a path."
            ))
        }

        "a poll timeout below one second, of a fraction of a second, infinite or beyond Int.MaxValue seconds" in {
            val values = Chunk(Duration.Zero, 999.millis, 1500.millis, Duration.Infinity, (Int.MaxValue.toLong + 1).seconds)
            assert(values.map(v => rejected(TelegramConfig.init(token, pollTimeout = v))) ==
                values.map(v => Present(TelegramInvalidConfigException(Problem.PollTimeout(v)))))
            assert(message(TelegramConfig.init(token, pollTimeout = Duration.Zero)).contains(
                s"TelegramConfig.pollTimeout must be a whole number of seconds from 1 to 2147483647; got ${Duration.Zero.show}."
            ))
        }

        "a poll limit outside 1 to 100" in {
            val values = Chunk(0, -1, 101)
            assert(values.map(v => rejected(TelegramConfig.init(token, pollLimit = v))) ==
                values.map(v => Present(TelegramInvalidConfigException(Problem.PollLimit(v)))))
            assert(message(TelegramConfig.init(token, pollLimit = 101)).contains(
                "TelegramConfig.pollLimit must be between 1 and 100; got 101."
            ))
        }

        "a request timeout of zero or infinity" in {
            val values = Chunk(Duration.Zero, Duration.Infinity)
            assert(values.map(v => rejected(TelegramConfig.init(token, requestTimeout = v))) ==
                values.map(v => Present(TelegramInvalidConfigException(Problem.RequestTimeout(v)))))
            assert(message(TelegramConfig.init(token, requestTimeout = Duration.Infinity)).contains(
                s"TelegramConfig.requestTimeout must be positive and finite; got ${Duration.Infinity.show}."
            ))
        }

        "a transfer timeout of zero or infinity" in {
            val values = Chunk(Duration.Zero, Duration.Infinity)
            assert(values.map(v => rejected(TelegramConfig.init(token, transferTimeout = v))) ==
                values.map(v => Present(TelegramInvalidConfigException(Problem.TransferTimeout(v)))))
            assert(message(TelegramConfig.init(token, transferTimeout = Duration.Zero)).contains(
                s"TelegramConfig.transferTimeout must be positive and finite; got ${Duration.Zero.show}."
            ))
        }

        "a connect timeout of zero or infinity" in {
            val values = Chunk(Duration.Zero, Duration.Infinity)
            assert(values.map(v => rejected(TelegramConfig.init(token, connectTimeout = v))) ==
                values.map(v => Present(TelegramInvalidConfigException(Problem.ConnectTimeout(v)))))
            assert(message(TelegramConfig.init(token, connectTimeout = Duration.Zero)).contains(
                s"TelegramConfig.connectTimeout must be positive and finite; got ${Duration.Zero.show}."
            ))
        }

        "the first setting in declaration order is the one reported" in {
            assert(rejected(TelegramConfig.init(token, baseUrl = url("ws://h"), pollLimit = 0)) ==
                Present(TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Scheme))))
            assert(rejected(TelegramConfig.init(token, pollTimeout = Duration.Zero, pollLimit = 0)) ==
                Present(TelegramInvalidConfigException(Problem.PollTimeout(Duration.Zero))))
            assert(rejected(TelegramConfig.init(token, requestTimeout = Duration.Zero, transferTimeout = Duration.Zero)) ==
                Present(TelegramInvalidConfigException(Problem.RequestTimeout(Duration.Zero))))
            assert(rejected(TelegramConfig.init(token, transferTimeout = Duration.Zero, connectTimeout = Duration.Zero)) ==
                Present(TelegramInvalidConfigException(Problem.TransferTimeout(Duration.Zero))))
        }
    }

end TelegramConfigTest
