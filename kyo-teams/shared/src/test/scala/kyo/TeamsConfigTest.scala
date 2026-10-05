package kyo

class TeamsConfigTest extends kyo.test.Test[Any]:

    import TeamsException.UrlProblem
    import TeamsInvalidConfigException.Problem

    // Built from parts so the whole secret appears nowhere in this file's source text.
    private val secretText = Chunk("cfgTEST", "~secret.", "Part_9").mkString
    private val headerText = Chunk("cfgTEST", "-identity-", "header").mkString

    private val appId  = Teams.AppId.init("00001111-aaaa-2222-bbbb-3333cccc4444").getOrThrow
    private val secret = TeamsConfig.Credential.Secret(Teams.ClientSecret.init(secretText).getOrThrow)

    private def url(text: String)(using Frame): HttpUrl = HttpUrl.parse(text).getOrThrow

    private def problem(config: Result[TeamsInvalidConfigException, TeamsConfig]): Maybe[Problem] =
        config.failure.map(_.problem)

    "the defaults are the public cloud's documented values" in {
        val config = TeamsConfig.init(appId, secret).getOrThrow
        assert((
            config.tenant,
            config.loginUrl.full,
            config.scope,
            config.openIdMetadataUrl.full,
            config.issuer,
            config.serviceHosts.map(_.full),
            config.clockSkew,
            config.tokenRefreshMargin,
            config.keysMaxAge,
            config.keysMinRefresh,
            config.maxTokenLength,
            config.retry,
            config.retryMaxDelay
        ) == (
            TeamsConfig.Tenant.MultiTenant,
            "https://login.microsoftonline.com/",
            "https://api.botframework.com/.default",
            "https://login.botframework.com/v1/.well-known/openidconfiguration",
            "https://api.botframework.com",
            Chunk(
                "https://smba.trafficmanager.net/",
                "https://smba.infra.gcc.teams.microsoft.com/",
                "https://smba.infra.gov.teams.microsoft.us/",
                "https://smba.infra.dod.teams.microsoft.us/"
            ),
            5.minutes,
            5.minutes,
            24.hours,
            1.hour,
            16384,
            Absent,
            60.seconds
        ))
    }

    "every default service origin is https, so the token travels in plain http only to an origin the caller lists" in {
        assert(TeamsConfig.ServiceHosts.map(h => (h.scheme, h.port)) == Chunk.fill(4)((Present("https"), 443)))
        val defaults = TeamsConfig.init(appId, secret).getOrThrow.serviceHosts
        val plain    = Teams.ServiceUrl.init("http://smba.trafficmanager.net/amer/").getOrThrow
        assert(!kyo.internal.teams.ServiceUrls.allowed(defaults, plain.url))
        assert(kyo.internal.teams.ServiceUrls.allowed(
            defaults,
            Teams.ServiceUrl.init("https://smba.trafficmanager.net/amer/").getOrThrow.url
        ))
    }

    "a rendered config shows each credential redacted and not its value" in {
        val header    = Teams.IdentityHeader.init(headerText).getOrThrow
        val assertion = Teams.ClientAssertion.init(secretText).getOrThrow
        val rendered  = Chunk(
            TeamsConfig.init(appId, secret).getOrThrow.toString,
            TeamsConfig.init(appId, TeamsConfig.Credential.ManagedIdentity(url("http://127.0.0.1:41741/MSI/token/"), header)).getOrThrow
                .toString,
            TeamsConfig.init(appId, TeamsConfig.Credential.Federated(assertion)).getOrThrow.toString
        )
        assert(rendered(0).contains("Teams.ClientSecret(<redacted>)"))
        assert(rendered(1).contains("Teams.IdentityHeader(<redacted>)"))
        assert(rendered(2).contains("Teams.ClientAssertion(<redacted>)"))
        assert(rendered.forall(r => !r.contains("cfgTEST")))
    }

    "a federated credential whose assertion fails is kept in the config as given" in {
        val federated: TeamsConfig.Credential =
            TeamsConfig.Credential.Federated(Abort.fail(TeamsCredentialException("no workload token")))
        val config: Result[TeamsInvalidConfigException, TeamsConfig] = TeamsConfig.init(appId, federated)
        assert(config.map(_.credential) == Result.succeed(federated))
    }

    "a single-tenant registration names its tenant" in {
        val tenant = Teams.TenantId.init("aaaabbbb-0000-cccc-1111-dddd2222eeee").getOrThrow
        val config = TeamsConfig.init(appId, secret, tenant = TeamsConfig.Tenant.SingleTenant(tenant))
        assert(config.map(_.tenant) == Result.succeed(TeamsConfig.Tenant.SingleTenant(tenant)))
    }

    "local peers and the bounds are accepted" in {
        val config = TeamsConfig.init(
            appId,
            secret,
            loginUrl = url("http://127.0.0.1:8081"),
            openIdMetadataUrl = url("http://127.0.0.1:8082/v1/.well-known/openidconfiguration"),
            serviceHosts = Chunk(url("http://127.0.0.1:8083")),
            clockSkew = Duration.Zero,
            tokenRefreshMargin = Duration.Zero,
            keysMaxAge = 1.nano,
            keysMinRefresh = 1.nano,
            maxTokenLength = 1,
            requestTimeout = 1.nano,
            connectTimeout = 1.nano,
            maxResponseLength = 0.bytes,
            keysMaxResponseLength = (Int.MaxValue.toLong + 1).bytes,
            retry = Present(Schedule.fixed(1.second).take(3)),
            retryMaxDelay = 1.nano
        )
        assert(config.map(c => (c.serviceHosts.map(_.full), c.keysMinRefresh, c.retry.nonEmpty, c.retryMaxDelay)) ==
            Result.succeed((Chunk("http://127.0.0.1:8083/"), 1.nano, true, 1.nano)))
        assert(config.map(c => (c.maxResponseLength, c.keysMaxResponseLength)) ==
            Result.succeed((0.bytes, (Int.MaxValue.toLong + 1).bytes)))
    }

    "init fails with the first setting the module cannot use" - {

        "the URLs it sends to" in {
            val header = Teams.IdentityHeader.init(headerText).getOrThrow
            assert(Chunk(
                problem(TeamsConfig.init(appId, secret, loginUrl = url("wss://login.microsoftonline.com"))),
                problem(TeamsConfig.init(appId, secret, loginUrl = HttpUrl.fromUri("/token"))),
                problem(TeamsConfig.init(appId, secret, loginUrl = url("https://login.microsoftonline.com?x=1"))),
                problem(TeamsConfig.init(appId, secret, openIdMetadataUrl = url("http+unix://%2Ftmp%2Fs.sock/v1"))),
                problem(TeamsConfig.init(appId, secret, openIdMetadataUrl = HttpUrl(Present("https"), "", 443, "/", Absent))),
                problem(TeamsConfig.init(appId, secret, loginUrl = HttpUrl(Present("https"), "u:p@login.example.com", 443, "/", Absent))),
                problem(TeamsConfig.init(appId, secret, loginUrl = HttpUrl(Present("https"), "lögin.example.com", 443, "/", Absent))),
                problem(TeamsConfig.init(
                    appId,
                    TeamsConfig.Credential.ManagedIdentity(HttpUrl(Present("ftp"), "x", 21, "/", Absent), header)
                ))
            ) == Chunk(
                Present(Problem.LoginUrl(UrlProblem.Scheme)),
                Present(Problem.LoginUrl(UrlProblem.Scheme)),
                Present(Problem.LoginUrl(UrlProblem.Query)),
                Present(Problem.OpenIdMetadataUrl(UrlProblem.UnixSocket)),
                Present(Problem.OpenIdMetadataUrl(UrlProblem.Host)),
                Present(Problem.LoginUrl(UrlProblem.UserInfo)),
                Present(Problem.LoginUrl(UrlProblem.Character(9))),
                Present(Problem.IdentityEndpoint(UrlProblem.Scheme))
            ))
        }

        "the service origins" in {
            assert(Chunk(
                problem(TeamsConfig.init(appId, secret, serviceHosts = Chunk.empty)),
                problem(TeamsConfig.init(appId, secret, serviceHosts = Chunk(url("https://smba.trafficmanager.net/teams/")))),
                problem(TeamsConfig.init(appId, secret, serviceHosts = Chunk(url("https://a.example.com"), url("ws://b.example.com"))))
            ) == Chunk(
                Present(Problem.NoServiceHosts),
                Present(Problem.ServiceHost(0, UrlProblem.Path)),
                Present(Problem.ServiceHost(1, UrlProblem.Scheme))
            ))
        }

        "the text settings" in {
            assert(Chunk(
                problem(TeamsConfig.init(appId, secret, scope = "")),
                problem(TeamsConfig.init(appId, secret, scope = "https://api.botframework.com/.default x")),
                problem(TeamsConfig.init(appId, secret, scope = "a\"b")),
                problem(TeamsConfig.init(appId, secret, issuer = ""))
            ) == Chunk(
                Present(Problem.Scope(Absent)),
                Present(Problem.Scope(Present(37))),
                Present(Problem.Scope(Present(1))),
                Present(Problem.Issuer)
            ))
        }

        "a scope or issuer of any length is accepted, and an issuer may hold any character" in {
            assert(Chunk(
                problem(TeamsConfig.init(appId, secret, scope = "s" * 4096)),
                problem(TeamsConfig.init(appId, secret, issuer = "i" * 4096)),
                problem(TeamsConfig.init(appId, secret, issuer = "issuer with spaces"))
            ) == Chunk(Absent, Absent, Absent))
        }

        "the durations and bounds" in {
            assert(Chunk(
                problem(TeamsConfig.init(appId, secret, clockSkew = Duration.Infinity)),
                problem(TeamsConfig.init(appId, secret, tokenRefreshMargin = Duration.Infinity)),
                problem(TeamsConfig.init(appId, secret, keysMaxAge = Duration.Zero)),
                problem(TeamsConfig.init(appId, secret, keysMaxAge = 1.hour, keysMinRefresh = 2.hours)),
                problem(TeamsConfig.init(appId, secret, maxTokenLength = 0)),
                problem(TeamsConfig.init(appId, secret, requestTimeout = Duration.Zero)),
                problem(TeamsConfig.init(appId, secret, connectTimeout = Duration.Infinity)),
                problem(TeamsConfig.init(appId, secret, connectTimeout = Duration.Zero)),
                problem(TeamsConfig.init(appId, secret, retryMaxDelay = Duration.Zero)),
                problem(TeamsConfig.init(appId, secret, retryMaxDelay = Duration.Infinity))
            ) == Chunk(
                Present(Problem.ClockSkew(Duration.Infinity)),
                Present(Problem.TokenRefreshMargin(Duration.Infinity)),
                Present(Problem.KeysMaxAge(Duration.Zero)),
                Present(Problem.KeysMinRefresh(2.hours, 1.hour)),
                Present(Problem.MaxTokenLength(0)),
                Present(Problem.RequestTimeout(Duration.Zero)),
                Present(Problem.ConnectTimeout(Duration.Infinity)),
                Present(Problem.ConnectTimeout(Duration.Zero)),
                Present(Problem.RetryMaxDelay(Duration.Zero)),
                Present(Problem.RetryMaxDelay(Duration.Infinity))
            ))
        }

        "in declaration order, and the message names the setting" in {
            val both = TeamsConfig.init(appId, secret, loginUrl = HttpUrl.fromUri("/x"), requestTimeout = Duration.Zero)
            assert(problem(both) == Present(Problem.LoginUrl(UrlProblem.Scheme)))
            assert(both.failure.map(_.getMessage).exists(_.contains("TeamsConfig.loginUrl has a scheme that is not accepted here")))
        }
    }

end TeamsConfigTest
