package kyo

class TelegramConfigTest extends kyo.test.Test[Any]:

    // Built from parts so the whole secret appears nowhere in this file's source text.
    private val secret = Seq("987654", "cfgTEST_secret-Part").mkString(":")
    private val token  = TelegramToken(secret)

    private def url(text: String)(using Frame): HttpUrl = HttpUrl.parse(text).getOrThrow

    "the request config the module runs every call under states each field and keeps nothing of the caller's" in {
        val config  = TelegramConfig(token, connectTimeout = 3.seconds, maxResponseLength = 4.mib)
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
            HttpTransportConfig.default,
            HttpTlsConfig.default,
            4 * 1024 * 1024,
            false,
            true
        ))
        assert(Chunk(HttpStatus(500), HttpStatus(404)).map(request.retryOn) == Chunk(true, false))
    }

    "a rendered config shows the token redacted and not its value" in {
        val rendered = TelegramConfig(token).toString
        assert(rendered.contains("TelegramToken(<redacted>)"))
        assert(!rendered.contains(secret))
        assert(!rendered.contains("cfgTEST_secret-Part"))
    }

    "the bounds Telegram gives are accepted" in {
        val config = TelegramConfig(token, baseUrl = url("http://127.0.0.1:8081"), pollTimeout = 1.second, pollLimit = 1)
        assert((config.baseUrl, config.pollTimeout, config.pollLimit) == (url("http://127.0.0.1:8081"), 1.second, 1))
        val withPath = TelegramConfig(token, baseUrl = url("https://proxy.example.com/telegram"))
        assert(withPath.baseUrl == url("https://proxy.example.com/telegram"))
        val widest = TelegramConfig(token, pollTimeout = Int.MaxValue.toLong.seconds, pollLimit = 100, requestTimeout = 1.nano)
        assert((widest.pollTimeout, widest.pollLimit, widest.requestTimeout) == (Int.MaxValue.toLong.seconds, 100, 1.nano))
    }

    "construction panics with the setting and what is wrong" - {
        import TelegramInvalidConfigException.Problem
        import TelegramInvalidConfigException.UrlProblem

        def rejected(build: => TelegramConfig)(using kyo.test.AssertScope): TelegramInvalidConfigException =
            intercept[TelegramInvalidConfigException](build)

        "a base URL that is not http or https, holds a query, or ends with a slash after a path" in {
            val found = Chunk(
                rejected(TelegramConfig(token, baseUrl = url("wss://api.telegram.org"))),
                rejected(TelegramConfig(token, baseUrl = HttpUrl.fromUri("/bot"))),
                rejected(TelegramConfig(token, baseUrl = HttpUrl.fromUri("hooks/x"))),
                rejected(TelegramConfig(token, baseUrl = HttpUrl(Present("ftp"), "api.telegram.org", 21, "/", Absent))),
                rejected(TelegramConfig(token, baseUrl = url("https://api.telegram.org?user=me"))),
                rejected(TelegramConfig(token, baseUrl = url("https://proxy.example.com/telegram/"))),
                rejected(TelegramConfig(token, baseUrl = HttpUrl(Present("https"), "", 443, "/", Absent))),
                rejected(TelegramConfig(token, baseUrl = url("http+unix://%2Ftmp%2Fbot.sock/api")))
            )
            assert(found == Chunk(
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Scheme)),
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Scheme)),
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Scheme)),
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Scheme)),
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Query)),
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.TrailingSlash)),
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Host)),
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.UnixSocket))
            ))
        }

        "a base URL holding a character outside printable ASCII, in its host or its path, names its position" in {
            val found = Chunk(
                rejected(TelegramConfig(token, baseUrl = HttpUrl(Present("https"), "bøt.example.com", 443, "/", Absent))),
                rejected(TelegramConfig(token, baseUrl = HttpUrl(Present("https"), "proxy.example.com", 443, "/a\u0001b", Absent)))
            )
            assert(found == Chunk(
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Character(9))),
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Character(27)))
            ))
        }

        "a base URL whose host carries user info is refused, and the message holds none of it" in {
            val credential = Seq("user", "cfgTEST_hostSecret").mkString(":")
            val ex = rejected(TelegramConfig(token, baseUrl = HttpUrl(Present("https"), s"$credential@api.telegram.org", 443, "/", Absent)))
            assert(ex == TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.UserInfo)))
            assert(ex.getMessage.contains("TelegramConfig.baseUrl must hold no user info."))
            assert(!ex.getMessage.contains(credential))
        }

        "user info in a parsed base URL is dropped by the parser, so the config holds and renders none of it" in {
            val credential = Seq("user", "cfgTEST_parsedSecret").mkString(":")
            val config     = TelegramConfig(token, baseUrl = url(s"https://$credential@api.telegram.org"))
            assert(config.baseUrl == TelegramConfig.TelegramServer)
            assert(!config.toString.contains(credential))
        }

        "a scheme in upper case is http or https all the same" in {
            val upper = HttpUrl(Present("HTTPS"), "api.telegram.org", 443, "/", Absent)
            assert(TelegramConfig(token, baseUrl = upper).baseUrl == upper)
        }

        "a base URL with or without the root's slash is the same server" in {
            assert(TelegramConfig(token, baseUrl = url("https://api.telegram.org/")).baseUrl == TelegramConfig(token).baseUrl)
        }

        "the base URL's messages, which do not render the URL" in {
            val marker = Seq("user", "marker").mkString("-")
            val query  = rejected(TelegramConfig(token, baseUrl = url(s"https://api.telegram.org?$marker")))
            assert(query.getMessage.contains("TelegramConfig.baseUrl must hold no query."))
            assert(!query.getMessage.contains(marker))
            assert(rejected(TelegramConfig(token, baseUrl = url("ws://h"))).getMessage.contains(
                "TelegramConfig.baseUrl must be an http or https URL."
            ))
            assert(rejected(TelegramConfig(token, baseUrl = url("https://h/p/"))).getMessage.contains(
                "TelegramConfig.baseUrl must not end with / after a path."
            ))
        }

        "a poll timeout below one second, of a fraction of a second, infinite or beyond Int.MaxValue seconds" in {
            val values = Chunk(Duration.Zero, 999.millis, 1500.millis, Duration.Infinity, (Int.MaxValue.toLong + 1).seconds)
            assert(values.map(v => rejected(TelegramConfig(token, pollTimeout = v))) ==
                values.map(v => TelegramInvalidConfigException(Problem.PollTimeout(v))))
            assert(rejected(TelegramConfig(token, pollTimeout = Duration.Zero)).getMessage.contains(
                s"TelegramConfig.pollTimeout must be a whole number of seconds from 1 to 2147483647; got ${Duration.Zero.show}."
            ))
        }

        "a poll limit outside 1 to 100" in {
            val values = Chunk(0, -1, 101)
            assert(values.map(v => rejected(TelegramConfig(token, pollLimit = v))) ==
                values.map(v => TelegramInvalidConfigException(Problem.PollLimit(v))))
            assert(rejected(TelegramConfig(token, pollLimit = 101)).getMessage.contains(
                "TelegramConfig.pollLimit must be between 1 and 100; got 101."
            ))
        }

        "a request timeout of zero or infinity" in {
            val values = Chunk(Duration.Zero, Duration.Infinity)
            assert(values.map(v => rejected(TelegramConfig(token, requestTimeout = v))) ==
                values.map(v => TelegramInvalidConfigException(Problem.RequestTimeout(v))))
            assert(rejected(TelegramConfig(token, requestTimeout = Duration.Infinity)).getMessage.contains(
                s"TelegramConfig.requestTimeout must be positive and finite; got ${Duration.Infinity.show}."
            ))
        }

        "a connect timeout of zero or infinity" in {
            val values = Chunk(Duration.Zero, Duration.Infinity)
            assert(values.map(v => rejected(TelegramConfig(token, connectTimeout = v))) ==
                values.map(v => TelegramInvalidConfigException(Problem.ConnectTimeout(v))))
            assert(rejected(TelegramConfig(token, connectTimeout = Duration.Zero)).getMessage.contains(
                s"TelegramConfig.connectTimeout must be positive and finite; got ${Duration.Zero.show}."
            ))
        }

        "a response length bound of zero or beyond Int.MaxValue bytes, and the bounds themselves accepted" in {
            val values = Chunk(0.bytes, (Int.MaxValue.toLong + 1).bytes)
            assert(values.map(v => rejected(TelegramConfig(token, maxResponseLength = v))) ==
                values.map(v => TelegramInvalidConfigException(Problem.MaxResponseLength(v, Int.MaxValue.toLong.bytes))))
            assert(Chunk(1.bytes, Int.MaxValue.toLong.bytes).map(v => TelegramConfig(token, maxResponseLength = v).maxResponseLength) ==
                Chunk(1.bytes, Int.MaxValue.toLong.bytes))
            assert(rejected(TelegramConfig(token, maxResponseLength = 0.bytes)).getMessage.contains(
                s"TelegramConfig.maxResponseLength must be from 1 byte to ${Int.MaxValue.toLong.bytes.show}; got ${0.bytes.show}."
            ))
        }

        "the first setting in declaration order is the one reported" in {
            assert(rejected(TelegramConfig(token, baseUrl = url("ws://h"), pollLimit = 0)) ==
                TelegramInvalidConfigException(Problem.BaseUrl(UrlProblem.Scheme)))
            assert(rejected(TelegramConfig(token, pollTimeout = Duration.Zero, pollLimit = 0)) ==
                TelegramInvalidConfigException(Problem.PollTimeout(Duration.Zero)))
        }
    }

end TelegramConfigTest
