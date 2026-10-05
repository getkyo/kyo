package kyo

import kyo.internal.teams.ServiceUrls

/** Everything a bot needs to call the Bot Framework's REST connector and to verify the requests it sends: the app id, the credential
  * that obtains the outbound token, the identity platform and OpenID endpoints, the service URL allowlist, the verification bounds,
  * the request limits and the opt-in retry.
  *
  * `credential` obtains the outbound token: a client secret, a federated assertion the caller supplies, or a managed identity's local
  * endpoint. `tenant` selects the identity platform's path: `botframework.com` for a multitenant registration, the tenant id for a
  * single-tenant one. `loginUrl`, `scope`, `openIdMetadataUrl` and `issuer` default to the public cloud's values, which Microsoft
  * documents; a sovereign cloud or a local test peer replaces them. `scope` is one OAuth scope token (RFC 6749 section 3.3), since
  * the identity platform's client credentials flow takes a single `/.default` scope; `issuer` is the `iss` an inbound token must
  * carry, which RFC 7519 lets be any string, so only an empty one is refused. Neither has a documented length.
  *
  * `serviceHosts` lists the origins (scheme, host and port) the outbound token may be sent to. The default is the hosts Microsoft
  * documents for the public cloud and the GCC, GCC High and DoD clouds; a call to any other origin is refused before a token is
  * attached.
  *
  * `clockSkew` widens an inbound token's validity on both sides (5 minutes, the skew Microsoft states). `tokenRefreshMargin` renews the
  * outbound token that long before it expires. `keysMaxAge` bounds the age of the cached signing keys (24 hours, as Microsoft asks),
  * and `keysMinRefresh` is how long after a key fetch an unknown key id may fetch again (1 hour, as Microsoft's reference client does).
  * `maxTokenLength` bounds an inbound token before it is parsed.
  *
  * `maxResponseLength` and `keysMaxResponseLength` (4 MiB each) bound the bodies of the module's calls and of its key fetches. Any size
  * is held as given. kyo-http's limit is a positive `Int`, so the request narrows it: zero becomes 1 byte and a size above
  * `Int.MaxValue` bytes becomes `Int.MaxValue`.
  *
  * `retry` is `Absent` by default, so a failure reaches the caller as it happened. Given a schedule, a Bot Connector call answered 412,
  * 429, 502, 503 or 504 (the statuses Microsoft says to retry) is sent again under it, waiting at least a `Retry-After` the answer
  * carried and never longer than `retryMaxDelay`: an answer asking for a longer wait fails as it came, its `Retry-After` on the
  * rate-limit leaf. The token request and the key fetches are never retried.
  *
  * `tls` and `transport` are the settings of the module's own HTTP client; nothing of the caller's kyo-http configuration reaches a
  * request that carries a credential.
  *
  * IMPORTANT: `init` refuses a config that holds a value the module cannot use with a [[kyo.TeamsInvalidConfigException]] naming the
  * first such setting. The rendered config never shows a credential, each of whose `toString` is redacted.
  */
final case class TeamsConfig private[kyo] (
    appId: Teams.AppId,
    credential: TeamsConfig.Credential,
    tenant: TeamsConfig.Tenant,
    loginUrl: HttpUrl,
    scope: String,
    openIdMetadataUrl: HttpUrl,
    issuer: String,
    serviceHosts: Chunk[HttpUrl],
    clockSkew: Duration,
    tokenRefreshMargin: Duration,
    keysMaxAge: Duration,
    keysMinRefresh: Duration,
    maxTokenLength: Int,
    requestTimeout: Duration,
    connectTimeout: Duration,
    maxResponseLength: ByteSize,
    keysMaxResponseLength: ByteSize,
    retry: Maybe[Schedule],
    retryMaxDelay: Duration,
    tls: HttpTlsConfig,
    transport: HttpTransportConfig
)

object TeamsConfig:

    given CanEqual[TeamsConfig, TeamsConfig] = CanEqual.derived

    /** The config, or a [[kyo.TeamsInvalidConfigException]] naming the first setting the module cannot use, in declaration order. */
    def init(
        appId: Teams.AppId,
        credential: Credential,
        tenant: Tenant = Tenant.MultiTenant,
        loginUrl: HttpUrl = LoginServer,
        scope: String = BotFrameworkScope,
        openIdMetadataUrl: HttpUrl = OpenIdMetadata,
        issuer: String = BotFrameworkIssuer,
        serviceHosts: Chunk[HttpUrl] = ServiceHosts,
        clockSkew: Duration = 5.minutes,
        tokenRefreshMargin: Duration = 5.minutes,
        keysMaxAge: Duration = 24.hours,
        keysMinRefresh: Duration = 1.hour,
        maxTokenLength: Int = 16384,
        requestTimeout: Duration = 10.seconds,
        connectTimeout: Duration = 10.seconds,
        maxResponseLength: ByteSize = 4.mib,
        keysMaxResponseLength: ByteSize = 4.mib,
        retry: Maybe[Schedule] = Absent,
        retryMaxDelay: Duration = 60.seconds,
        tls: HttpTlsConfig = HttpTlsConfig.default,
        transport: HttpTransportConfig = HttpTransportConfig.default
    )(using Frame): Result[TeamsInvalidConfigException, TeamsConfig] =
        val problem = problemOf(
            credential,
            loginUrl,
            scope,
            openIdMetadataUrl,
            issuer,
            serviceHosts,
            clockSkew,
            tokenRefreshMargin,
            keysMaxAge,
            keysMinRefresh,
            maxTokenLength,
            requestTimeout,
            connectTimeout,
            retryMaxDelay
        )
        problem.fold(
            Result.succeed(new TeamsConfig(
                appId,
                credential,
                tenant,
                loginUrl,
                scope,
                openIdMetadataUrl,
                issuer,
                serviceHosts,
                clockSkew,
                tokenRefreshMargin,
                keysMaxAge,
                keysMinRefresh,
                maxTokenLength,
                requestTimeout,
                connectTimeout,
                maxResponseLength,
                keysMaxResponseLength,
                retry,
                retryMaxDelay,
                tls,
                transport
            ))
        )(p => Result.fail(TeamsInvalidConfigException(p)))
    end init

    /** How the bot obtains its outbound token from the identity platform.
      *
      *   - `Secret`: the client secret of the app registration, in the token request's body.
      *   - `Federated`: an assertion from an identity provider the app registration trusts (a workload identity), obtained by running
      *     `assertion` before each token request, so a short-lived assertion is fresh. `assertion` maps its own failure into a
      *     [[kyo.TeamsCredentialException]], which every outbound verb's failure includes.
      *   - `ManagedIdentity`: the token endpoint App Service and Azure Functions run for a user-assigned managed identity, at the
      *     `IDENTITY_ENDPOINT` the platform sets, with the `IDENTITY_HEADER` value. The identity's client id is the config's `appId`,
      *     and the token's resource is `scope` without its `/.default` suffix.
      *
      * Microsoft recommends a federated credential or a managed identity over a secret where the deployment allows one.
      */
    sealed trait Credential
    object Credential:
        given CanEqual[Credential, Credential] = CanEqual.derived

        /** The client secret of the app registration. */
        final case class Secret(secret: Teams.ClientSecret) extends Credential

        /** An assertion `assertion` obtains before each token request. */
        final case class Federated(assertion: Teams.ClientAssertion < (Async & Abort[TeamsCredentialException])) extends Credential

        /** The managed identity endpoint at `endpoint`, sent `header` in `X-IDENTITY-HEADER`. */
        final case class ManagedIdentity(endpoint: HttpUrl, header: Teams.IdentityHeader) extends Credential
    end Credential

    /** Which identity platform tenant issues the outbound token: the Bot Framework's own for a multitenant registration, or the bot's
      * tenant for a single-tenant one.
      */
    enum Tenant derives CanEqual:
        case MultiTenant
        case SingleTenant(tenant: Teams.TenantId)
    end Tenant

    /** The identity platform's login host, `https://login.microsoftonline.com`. */
    val LoginServer: HttpUrl = HttpUrl(Present("https"), "login.microsoftonline.com", 443, "/", Absent)

    /** The scope the Bot Connector's token is requested for. */
    val BotFrameworkScope: String = "https://api.botframework.com/.default"

    /** The Bot Framework's OpenID metadata document, which names the signing keys of inbound tokens. */
    val OpenIdMetadata: HttpUrl =
        HttpUrl(Present("https"), "login.botframework.com", 443, "/v1/.well-known/openidconfiguration", Absent)

    /** The issuer of every token the Bot Connector sends a bot. */
    val BotFrameworkIssuer: String = "https://api.botframework.com"

    /** The origins Microsoft documents service URLs at: the public cloud, GCC, GCC High and DoD. */
    val ServiceHosts: Chunk[HttpUrl] = Chunk(
        "smba.trafficmanager.net",
        "smba.infra.gcc.teams.microsoft.com",
        "smba.infra.gov.teams.microsoft.us",
        "smba.infra.dod.teams.microsoft.us"
    ).map(host => HttpUrl(Present("https"), host, 443, "/", Absent))

    /** The first problem among the settings, in declaration order. kyo-http's client config throws on a non-positive timeout, so every
      * timeout is refused here first.
      */
    private def problemOf(
        credential: Credential,
        loginUrl: HttpUrl,
        scope: String,
        openIdMetadataUrl: HttpUrl,
        issuer: String,
        serviceHosts: Chunk[HttpUrl],
        clockSkew: Duration,
        tokenRefreshMargin: Duration,
        keysMaxAge: Duration,
        keysMinRefresh: Duration,
        maxTokenLength: Int,
        requestTimeout: Duration,
        connectTimeout: Duration,
        retryMaxDelay: Duration
    ): Maybe[TeamsInvalidConfigException.Problem] =
        import TeamsInvalidConfigException.Problem
        // RFC 6749 section 3.3: scope-token = 1*( %x21 / %x23-5B / %x5D-7E ). The client credentials flow takes one scope.
        def scopeProblem: Maybe[Problem] =
            if scope.isEmpty then Present(Problem.Scope(Absent))
            else
                val bad = scope.indexWhere(c => c <= ' ' || c > '~' || c == '"' || c == '\\')
                if bad >= 0 then Present(Problem.Scope(Present(bad))) else Absent
        def endpoint: Maybe[Problem] =
            credential match
                case Credential.ManagedIdentity(url, _) => ServiceUrls.baseProblemOf(url).map(Problem.IdentityEndpoint(_))
                case _: Credential.Secret               => Absent
                case _: Credential.Federated            => Absent
        def hosts: Maybe[Problem] =
            if serviceHosts.isEmpty then Present(Problem.NoServiceHosts)
            else
                serviceHosts.zipWithIndex.foldLeft(Maybe.empty[Problem]) { case (found, (url, index)) =>
                    found.orElse(ServiceUrls.originProblemOf(url).map(Problem.ServiceHost(index, _)))
                }
        endpoint
            .orElse(ServiceUrls.baseProblemOf(loginUrl).map(Problem.LoginUrl(_)))
            .orElse(scopeProblem)
            .orElse(ServiceUrls.baseProblemOf(openIdMetadataUrl).map(Problem.OpenIdMetadataUrl(_)))
            .orElse(if issuer.nonEmpty then Absent else Present(Problem.Issuer))
            .orElse(hosts)
            .orElse(if clockSkew.isFinite then Absent else Present(Problem.ClockSkew(clockSkew)))
            .orElse(if tokenRefreshMargin.isFinite then Absent else Present(Problem.TokenRefreshMargin(tokenRefreshMargin)))
            .orElse(if positiveFinite(keysMaxAge) then Absent else Present(Problem.KeysMaxAge(keysMaxAge)))
            .orElse(
                if keysMinRefresh <= keysMaxAge then Absent
                else Present(Problem.KeysMinRefresh(keysMinRefresh, keysMaxAge))
            )
            .orElse(if maxTokenLength > 0 then Absent else Present(Problem.MaxTokenLength(maxTokenLength)))
            .orElse(if positiveFinite(requestTimeout) then Absent else Present(Problem.RequestTimeout(requestTimeout)))
            .orElse(if positiveFinite(connectTimeout) then Absent else Present(Problem.ConnectTimeout(connectTimeout)))
            .orElse(if positiveFinite(retryMaxDelay) then Absent else Present(Problem.RetryMaxDelay(retryMaxDelay)))
    end problemOf

    private def positiveFinite(d: Duration): Boolean = d > Duration.Zero && d.isFinite

end TeamsConfig
