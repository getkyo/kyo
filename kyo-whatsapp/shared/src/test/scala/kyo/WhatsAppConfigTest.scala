package kyo

import WhatsAppInvalidConfigException.Problem
import WhatsAppInvalidConfigException.UrlProblem

class WhatsAppConfigTest extends BaseWhatsAppTest:

    val token = WhatsAppToken("TOKEN")
    val phone = WhatsAppId.PhoneNumberId("106540352242922")

    def refused(build: => WhatsAppConfig)(using Frame): Result[WhatsAppInvalidConfigException, WhatsAppConfig] =
        Result.catching[WhatsAppInvalidConfigException](build)

    def refusal(problem: Problem)(using Frame): Result[WhatsAppInvalidConfigException, WhatsAppConfig] =
        Result.fail(WhatsAppInvalidConfigException(problem))

    "copy validates the changed config" in {
        val cfg = WhatsAppConfig(token, phone)
        assert(cfg.copy(apiVersion = "v26.0").apiVersion == "v26.0")
        assert(refused(cfg.copy(apiVersion = "latest")) == refusal(Problem.ApiVersion))
        assert(cfg.apiVersion == "v25.0")
    }

    "a base url with a path keeps it, and accepts http" in {
        assert(WhatsAppConfig(token, phone, baseUrl = url("http://proxy.test:8080/graph")).baseUrl == url("http://proxy.test:8080/graph"))
    }

    "a base url that is not http or https is refused" in {
        val ftp = HttpUrl(Present("ftp"), "graph.test", 21, "/", Absent)
        assert(refused(WhatsAppConfig(token, phone, baseUrl = ftp)) == refusal(Problem.BaseUrl(UrlProblem.Scheme)))
    }

    "a base url without a host is refused" in {
        val noHost = HttpUrl(Present("https"), "", 443, "/", Absent)
        assert(refused(WhatsAppConfig(token, phone, baseUrl = noHost)) == refusal(Problem.BaseUrl(UrlProblem.Host)))
    }

    "a base url on a unix socket is refused" in {
        val socket = HttpUrl(Present("http"), "localhost", 80, "/", Absent, Present("/tmp/graph.sock"))
        assert(refused(WhatsAppConfig(token, phone, baseUrl = socket)) == refusal(Problem.BaseUrl(UrlProblem.UnixSocket)))
    }

    "a base url outside printable ASCII is refused, naming the position of the first such character" in {
        val unicode = HttpUrl(Present("https"), "gráph.test", 443, "/", Absent)
        assert(refused(WhatsAppConfig(token, phone, baseUrl = unicode)) == refusal(Problem.BaseUrl(UrlProblem.Character(10))))
    }

    "a base url whose host carries userinfo is refused, so the config's rendering holds no credential" in {
        val withUserInfo = HttpUrl(Present("https"), Seq("user", "secret@graph.test").mkString(":"), 443, "/", Absent)
        assert(refused(WhatsAppConfig(token, phone, baseUrl = withUserInfo)) == refusal(Problem.BaseUrl(UrlProblem.UserInfo)))
    }

    "kyo-http's parser drops a url's userinfo, so a parsed base url never carries one into the config" in {
        val secret = Seq("s3", "cret").mkString
        val parsed = WhatsAppConfig(token, phone, baseUrl = url(s"https://user:$secret@graph.test"))
        assert(parsed.baseUrl == url("https://graph.test"))
        assert(!parsed.toString.contains(secret), parsed.toString)
    }

    "a base url with a query is refused" in {
        assert(refused(WhatsAppConfig(token, phone, baseUrl = url("https://graph.test/?a=1"))) ==
            refusal(Problem.BaseUrl(UrlProblem.Query)))
    }

    "a base url whose path ends with a slash is refused" in {
        assert(refused(WhatsAppConfig(token, phone, baseUrl = url("https://graph.test/graph/"))) ==
            refusal(Problem.BaseUrl(UrlProblem.TrailingSlash)))
    }

    "an apiVersion other than v, digits, a dot and digits is refused" in {
        Seq("25.0", "v25", "v.0", "v25.", "vx.0", "v25.0/x", "").foreach { v =>
            assert(refused(WhatsAppConfig(token, phone, apiVersion = v)) == refusal(Problem.ApiVersion))
        }
        assert(WhatsAppConfig(token, phone, apiVersion = "v1.2").apiVersion == "v1.2")
    }

    "a phoneNumberId other than ASCII digits is refused" in {
        Seq("", "12a", "../me", "１２").foreach { id =>
            assert(refused(WhatsAppConfig(token, WhatsAppId.PhoneNumberId(id))) == refusal(Problem.PhoneNumberId))
        }
    }

    "a zero or infinite timeout is refused" in {
        assert(refused(WhatsAppConfig(token, phone, requestTimeout = Duration.Zero)) == refusal(Problem.RequestTimeout(Duration.Zero)))
        assert(refused(WhatsAppConfig(token, phone, requestTimeout = Duration.Infinity)) ==
            refusal(Problem.RequestTimeout(Duration.Infinity)))
        assert(refused(WhatsAppConfig(token, phone, connectTimeout = Duration.Zero)) == refusal(Problem.ConnectTimeout(Duration.Zero)))
        assert(refused(WhatsAppConfig(token, phone, connectTimeout = Duration.Infinity)) ==
            refusal(Problem.ConnectTimeout(Duration.Infinity)))
    }

    "a response bound outside 1 byte to Int.MaxValue bytes is refused" in {
        val over = ByteSize.fromBytes(Int.MaxValue.toLong + 1)
        val max  = ByteSize.fromBytes(Int.MaxValue.toLong)
        assert(refused(WhatsAppConfig(token, phone, maxResponseLength = ByteSize.Zero)) ==
            refusal(Problem.MaxResponseLength(ByteSize.Zero, max)))
        assert(refused(WhatsAppConfig(token, phone, maxResponseLength = over)) == refusal(Problem.MaxResponseLength(over, max)))
        assert(WhatsAppConfig(token, phone, maxResponseLength = ByteSize.fromBytes(1)).maxResponseLength == ByteSize.fromBytes(1))
    }

    // Built apart from the construction line: a leaf's development-mode message renders the source lines around its frame.
    val urlSecret = Seq("URL", "SECRET", "5a1c").mkString("-")

    "a refusal's message names the setting and not the base url's text" in {
        val withSecret = url(s"https://graph.test/?key=$urlSecret")
        val e          = refused(WhatsAppConfig(token, phone, baseUrl = withSecret))
        assert(e == refusal(Problem.BaseUrl(UrlProblem.Query)))
        assert(e.failure.map(_.getMessage).exists(_.contains("WhatsAppConfig.baseUrl must hold no query.")))
        assert(e.failure.forall(f => BaseWhatsAppTest.renderings(f).forall(!_.contains(urlSecret))))
    }

end WhatsAppConfigTest
