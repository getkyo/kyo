package kyo

import WhatsAppInvalidConfigException.Problem
import WhatsAppInvalidConfigException.UrlProblem

class WhatsAppConfigTest extends BaseWhatsAppTest:

    val token = WhatsAppToken.init("TOKEN").getOrThrow
    val phone = WhatsAppId.PhoneNumberId("106540352242922")

    def refusal(problem: Problem)(using Frame): Result[WhatsAppInvalidConfigException, WhatsAppConfig] =
        Result.fail(WhatsAppInvalidConfigException(problem))

    "a config is built only by init, so a changed config is checked again" in {
        typeCheckFailure(
            """kyo.WhatsAppConfig.init(kyo.WhatsAppToken.init("t").getOrThrow, kyo.WhatsAppId.PhoneNumberId("1")).getOrThrow.copy(apiVersion = "latest")"""
        )
        typeCheckFailure(
            """new kyo.WhatsAppConfig(kyo.WhatsAppToken.init("t").getOrThrow, kyo.WhatsAppId.PhoneNumberId("1"), "latest", kyo.WhatsAppConfig.GraphApi, kyo.Duration.Zero, kyo.Duration.Zero, kyo.ByteSize.Zero)"""
        )
        assert(WhatsAppConfig.init(token, phone, apiVersion = "v26.0").map(_.apiVersion) == Result.succeed("v26.0"))
        assert(WhatsAppConfig.init(token, phone, apiVersion = "latest") == refusal(Problem.ApiVersion))
    }

    "a base url with a path keeps it, and accepts http" in {
        assert(WhatsAppConfig.init(token, phone, baseUrl = url("http://proxy.test:8080/graph")).map(_.baseUrl) ==
            Result.succeed(url("http://proxy.test:8080/graph")))
    }

    "a base url that is not http or https is refused" in {
        val ftp = HttpUrl(Present("ftp"), "graph.test", 21, "/", Absent)
        assert(WhatsAppConfig.init(token, phone, baseUrl = ftp) == refusal(Problem.BaseUrl(UrlProblem.Scheme)))
    }

    "a base url without a host is refused" in {
        val noHost = HttpUrl(Present("https"), "", 443, "/", Absent)
        assert(WhatsAppConfig.init(token, phone, baseUrl = noHost) == refusal(Problem.BaseUrl(UrlProblem.Host)))
    }

    "a base url on a unix socket is refused" in {
        val socket = HttpUrl(Present("http"), "localhost", 80, "/", Absent, Present("/tmp/graph.sock"))
        assert(WhatsAppConfig.init(token, phone, baseUrl = socket) == refusal(Problem.BaseUrl(UrlProblem.UnixSocket)))
    }

    "a base url outside printable ASCII is refused, naming the position of the first such character" in {
        val unicode = HttpUrl(Present("https"), "gráph.test", 443, "/", Absent)
        assert(WhatsAppConfig.init(token, phone, baseUrl = unicode) == refusal(Problem.BaseUrl(UrlProblem.Character(10))))
    }

    "a base url whose host carries userinfo is refused, so the config's rendering holds no credential" in {
        val withUserInfo = HttpUrl(Present("https"), Seq("user", "secret@graph.test").mkString(":"), 443, "/", Absent)
        assert(WhatsAppConfig.init(token, phone, baseUrl = withUserInfo) == refusal(Problem.BaseUrl(UrlProblem.UserInfo)))
    }

    "kyo-http's parser drops a url's userinfo, so a parsed base url never carries one into the config" in {
        val secret = Seq("s3", "cret").mkString
        val parsed = WhatsAppConfig.init(token, phone, baseUrl = url(s"https://user:$secret@graph.test")).getOrThrow
        assert(parsed.baseUrl == url("https://graph.test"))
        assert(!parsed.toString.contains(secret), parsed.toString)
    }

    "a base url with a query is refused" in {
        assert(WhatsAppConfig.init(token, phone, baseUrl = url("https://graph.test/?a=1")) == refusal(Problem.BaseUrl(UrlProblem.Query)))
    }

    "a base url whose path ends with a slash is refused" in {
        assert(WhatsAppConfig.init(token, phone, baseUrl = url("https://graph.test/graph/")) ==
            refusal(Problem.BaseUrl(UrlProblem.TrailingSlash)))
    }

    "an apiVersion other than v, digits, a dot and digits is refused" in {
        Seq("25.0", "v25", "v.0", "v25.", "vx.0", "v25.0/x", "").foreach { v =>
            assert(WhatsAppConfig.init(token, phone, apiVersion = v) == refusal(Problem.ApiVersion), s"for $v")
        }
        assert(WhatsAppConfig.init(token, phone, apiVersion = "v1.2").map(_.apiVersion) == Result.succeed("v1.2"))
    }

    "a phoneNumberId other than ASCII digits is refused" in {
        Seq("", "12a", "../me", "１２").foreach { id =>
            assert(WhatsAppConfig.init(token, WhatsAppId.PhoneNumberId(id)) == refusal(Problem.PhoneNumberId), s"for $id")
        }
        succeed
    }

    "a zero or infinite timeout is refused" in {
        assert(WhatsAppConfig.init(token, phone, requestTimeout = Duration.Zero) == refusal(Problem.RequestTimeout(Duration.Zero)))
        assert(WhatsAppConfig.init(token, phone, requestTimeout = Duration.Infinity) == refusal(Problem.RequestTimeout(Duration.Infinity)))
        assert(WhatsAppConfig.init(token, phone, connectTimeout = Duration.Zero) == refusal(Problem.ConnectTimeout(Duration.Zero)))
        assert(WhatsAppConfig.init(token, phone, connectTimeout = Duration.Infinity) == refusal(Problem.ConnectTimeout(Duration.Infinity)))
    }

    "a zero or infinite retryMaxDelay is refused, and retry defaults to none with a 60 second bound" in {
        assert(WhatsAppConfig.init(token, phone, retryMaxDelay = Duration.Zero) == refusal(Problem.RetryMaxDelay(Duration.Zero)))
        assert(WhatsAppConfig.init(token, phone, retryMaxDelay = Duration.Infinity) == refusal(Problem.RetryMaxDelay(Duration.Infinity)))
        assert(WhatsAppConfig.init(token, phone).map(c => (c.retry, c.retryMaxDelay)) == Result.succeed((Absent, 60.seconds)))
    }

    "a zero response bound is held as given and reaches the request config unchanged, for kyo-http to narrow" in {
        assert(WhatsAppConfig.init(token, phone, maxResponseLength = ByteSize.Zero).map(c =>
            (c.maxResponseLength, c.httpConfig.maxResponseLength)
        ) == Result.succeed((ByteSize.Zero, ByteSize.Zero)))
    }

    "a one-byte response bound is held and reaches the request config unchanged" in {
        assert(WhatsAppConfig.init(token, phone, maxResponseLength = 1.bytes).map(c =>
            (c.maxResponseLength, c.httpConfig.maxResponseLength)
        ) == Result.succeed((1.bytes, 1.bytes)))
    }

    "a response bound past Int.MaxValue bytes is held as given and reaches the request config unchanged, for kyo-http to narrow" in {
        val over = ByteSize.fromBytes(Int.MaxValue.toLong + 1)
        assert(WhatsAppConfig.init(token, phone, maxResponseLength = over).map(c =>
            (c.maxResponseLength, c.httpConfig.maxResponseLength)
        ) == Result.succeed((over, over)))
    }

    // Built apart from the construction line: a leaf's development-mode message renders the source lines around its frame.
    val urlSecret = Seq("URL", "SECRET", "5a1c").mkString("-")

    "a refusal's message names the setting and not the base url's text" in {
        val withSecret = url(s"https://graph.test/?key=$urlSecret")
        val e          = WhatsAppConfig.init(token, phone, baseUrl = withSecret)
        assert(e == refusal(Problem.BaseUrl(UrlProblem.Query)))
        assert(e.failure.map(_.getMessage).exists(_.contains("WhatsAppConfig.baseUrl must hold no query.")))
        assert(e.failure.forall(f => BaseWhatsAppTest.renderings(f).forall(!_.contains(urlSecret))))
    }

end WhatsAppConfigTest
