package kyo

/** Everything the Cloud API client needs: the access token, the registered phone-number id (a path segment of every messages and upload
  * url), the Graph API version (default `v25.0`), the base URL (default `https://graph.facebook.com`), and the request limits.
  *
  * Every url is `{baseUrl}/{apiVersion}/...`: messages and uploads go under `phoneNumberId`, media ids and `custom` paths under the
  * version.
  * `requestTimeout` bounds each request and `connectTimeout` opening its connection. `maxResponseLength` bounds an answer's body; its
  * default, 100 MB, is the largest media Meta stores (a document), so `WhatsAppMedia.download` of any stored file fits. It is never
  * refused: kyo-http narrows it where it reads a body, zero to one byte and past `Int.MaxValue` to `Int.MaxValue`.
  *
  * `retry`, when set, sends a call again on the Graph errors Meta documents as retryable, after the schedule's delay or the answer's
  * `Retry-After`, whichever is longer, until the schedule ends. No wait exceeds `retryMaxDelay` (default 60 seconds): an answer asking
  * for a longer one fails at once, a rate-limit leaf carrying the `retryAfter` it asked for. A retried send can be delivered twice,
  * since Meta may have accepted the first attempt before answering the error. Absent by default, so no call is retried.
  *
  * The module's HTTP client uses these settings and nothing of the caller's kyo-http configuration: no ambient filter, TLS setting, base
  * url, retry or redirect applies to a request that carries the token.
  *
  * IMPORTANT: a config is built only by `init`, which fails with a [[kyo.WhatsAppInvalidConfigException]] naming the setting the module
  * cannot use: a base URL that is not an absolute http or https URL on a host written in printable ASCII, or has userinfo, a query or a
  * trailing slash; an `apiVersion` other than `v`, digits, a dot and digits; a `phoneNumberId` other than ASCII digits (both are url path
  * segments); a zero or infinite timeout or `retryMaxDelay`. The token's `toString` is redacted and the base URL carries no userinfo, so the rendered config shows no credential. A changed config is built by `init` again, so it
  * is checked again.
  *
  * @see
  *   [[kyo.WhatsApp.run]] the client built from it
  */
final case class WhatsAppConfig private (
    token: WhatsAppToken,
    phoneNumberId: WhatsAppId.PhoneNumberId,
    apiVersion: String,
    baseUrl: HttpUrl,
    requestTimeout: Duration,
    connectTimeout: Duration,
    maxResponseLength: ByteSize,
    retry: Maybe[Schedule],
    retryMaxDelay: Duration
)(private[kyo] val httpConfig: HttpClientConfig) derives CanEqual

object WhatsAppConfig:

    /** The config, or the [[kyo.WhatsAppInvalidConfigException]] naming the first setting the module cannot use. */
    def init(
        token: WhatsAppToken,
        phoneNumberId: WhatsAppId.PhoneNumberId,
        apiVersion: String = "v25.0",
        baseUrl: HttpUrl = GraphApi,
        requestTimeout: Duration = 10.seconds,
        connectTimeout: Duration = 10.seconds,
        maxResponseLength: ByteSize = 100.mb,
        retry: Maybe[Schedule] = Absent,
        retryMaxDelay: Duration = 60.seconds
    )(using Frame): Result[WhatsAppInvalidConfigException, WhatsAppConfig] =
        problemOf(baseUrl, apiVersion, phoneNumberId, requestTimeout, connectTimeout, retryMaxDelay) match
            case Present(problem) => Result.fail(WhatsAppInvalidConfigException(problem))
            case Absent           =>
                Result.succeed(new WhatsAppConfig(
                    token,
                    phoneNumberId,
                    apiVersion,
                    baseUrl,
                    requestTimeout,
                    connectTimeout,
                    maxResponseLength,
                    retry,
                    retryMaxDelay
                )(httpConfigOf(requestTimeout, connectTimeout, maxResponseLength)))
    end init

    /** Meta's Graph API, `https://graph.facebook.com`. */
    val GraphApi: HttpUrl = HttpUrl(Present("https"), "graph.facebook.com", 443, "/", Absent)

    private def problemOf(
        baseUrl: HttpUrl,
        apiVersion: String,
        phoneNumberId: WhatsAppId.PhoneNumberId,
        requestTimeout: Duration,
        connectTimeout: Duration,
        retryMaxDelay: Duration
    ): Maybe[WhatsAppInvalidConfigException.Problem] =
        import WhatsAppInvalidConfigException.Problem
        urlProblemOf(baseUrl).map(Problem.BaseUrl(_))
            .orElse(if validVersion(apiVersion) then Absent else Present(Problem.ApiVersion))
            .orElse(if validPhoneNumberId(phoneNumberId.value) then Absent else Present(Problem.PhoneNumberId))
            .orElse(if positiveFinite(requestTimeout) then Absent else Present(Problem.RequestTimeout(requestTimeout)))
            .orElse(if positiveFinite(connectTimeout) then Absent else Present(Problem.ConnectTimeout(connectTimeout)))
            .orElse(if positiveFinite(retryMaxDelay) then Absent else Present(Problem.RetryMaxDelay(retryMaxDelay)))
    end problemOf

    /** The kyo-http configuration of a request that carries the token. Every field is set, so nothing is inherited from the caller. */
    private def httpConfigOf(requestTimeout: Duration, connectTimeout: Duration, maxResponseLength: ByteSize): HttpClientConfig =
        HttpClientConfig(
            baseUrl = Absent,
            timeout = requestTimeout,
            connectTimeout = connectTimeout,
            followRedirects = false,
            maxRedirects = 0,
            retrySchedule = Absent,
            retryOn = _.isServerError,
            transportConfig = HttpTransportConfig.default,
            tls = HttpTlsConfig.default,
            maxResponseLength = maxResponseLength,
            autoFilters = false,
            clientFilter = HttpFilter.noop
        )

    private def positiveFinite(d: Duration): Boolean = d > Duration.Zero && d.isFinite

    private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'

    private def validVersion(v: String): Boolean =
        v.length >= 4 && v.charAt(0) == 'v' && {
            val dot = v.indexOf('.')
            dot > 1 && dot < v.length - 1 && v.substring(1, dot).forall(isDigit) && v.substring(dot + 1).forall(isDigit)
        }

    private def validPhoneNumberId(id: String): Boolean = id.nonEmpty && id.forall(isDigit)

    private def urlProblemOf(url: HttpUrl): Maybe[WhatsAppInvalidConfigException.UrlProblem] =
        import WhatsAppInvalidConfigException.UrlProblem
        absoluteProblemOf(url)
            .orElse(if url.host.contains('@') then Present(UrlProblem.UserInfo) else Absent)
            .orElse(if url.rawQuery.nonEmpty then Present(UrlProblem.Query) else Absent)
            .orElse(if url.path != "/" && url.path.endsWith("/") then Present(UrlProblem.TrailingSlash) else Absent)
    end urlProblemOf

    /** Why `url` is not an absolute http or https URL on a TCP host written in printable ASCII, the only URLs the module sends to: kyo-http
      * refuses to send a host or path outside ASCII.
      */
    private[kyo] def absoluteProblemOf(url: HttpUrl): Maybe[WhatsAppInvalidConfigException.UrlProblem] =
        import WhatsAppInvalidConfigException.UrlProblem
        def asciiLower(s: String): String = s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)
        if !url.scheme.map(asciiLower).exists(s => s == "http" || s == "https") then Present(UrlProblem.Scheme)
        else if url.host.isEmpty then Present(UrlProblem.Host)
        else if url.unixSocket.nonEmpty then Present(UrlProblem.UnixSocket)
        else
            val at = url.full.indexWhere(c => c < '!' || c > '~')
            if at >= 0 then Present(UrlProblem.Character(at)) else Absent
        end if
    end absoluteProblemOf

    /** `config.baseUrl` with `/{apiVersion}/{segments}` as its path, and `query` as its query. */
    private[kyo] def versioned(config: WhatsAppConfig, segments: String, query: HttpQueryParams = HttpQueryParams.empty): HttpUrl =
        val base = if config.baseUrl.path == "/" then "" else config.baseUrl.path
        val raw  = if query.isEmpty then Absent else Present(query.toQueryString)
        config.baseUrl.copy(path = s"$base/${config.apiVersion}/$segments", rawQuery = raw)
    end versioned

end WhatsAppConfig
