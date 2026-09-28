package kyo

/** Everything the Cloud API client needs: the access token, the registered phone-number id (a path segment of every messages and upload
  * url), the Graph API version (default `v25.0`), the base URL (default `https://graph.facebook.com`), and the request limits.
  *
  * Every url is `{baseUrl}/{apiVersion}/...`: messages and uploads go under `phoneNumberId`, media ids and `custom` paths under the
  * version.
  * `requestTimeout` bounds each request and `connectTimeout` opening its connection. `maxResponseLength` bounds an answer's body; its
  * default, 100 MB, is the largest media Meta stores (a document), so `WhatsAppMedia.download` of any stored file fits.
  *
  * The module's HTTP client uses these settings and nothing of the caller's kyo-http configuration: no ambient filter, TLS setting, base
  * url, retry or redirect applies to a request that carries the token.
  *
  * IMPORTANT: a config that holds a value the module cannot use is a programming mistake, so building one panics with a
  * [[kyo.WhatsAppInvalidConfigException]] naming the setting: a base URL that is not an absolute http or https URL on a host written in
  * printable ASCII, or has userinfo, a query or a trailing slash; an `apiVersion` other than `v`, digits, a dot and digits; a
  * `phoneNumberId` other than ASCII digits (both are url path segments); a zero or infinite timeout; a response bound outside 1 byte to
  * `Int.MaxValue` bytes. The token's `toString` is redacted and the base URL carries no userinfo, so the rendered config shows no
  * credential.
  *
  * @see
  *   [[kyo.WhatsApp.let]] the client built from it
  */
final case class WhatsAppConfig(
    token: WhatsAppToken,
    phoneNumberId: WhatsAppId.PhoneNumberId,
    apiVersion: String = "v25.0",
    baseUrl: HttpUrl = WhatsAppConfig.GraphApi,
    requestTimeout: Duration = 10.seconds,
    connectTimeout: Duration = 10.seconds,
    maxResponseLength: ByteSize = 100.mb
)(using Frame) derives CanEqual:
    WhatsAppConfig.problemOf(this).foreach(problem => throw WhatsAppInvalidConfigException(problem))
end WhatsAppConfig

object WhatsAppConfig:

    /** Meta's Graph API, `https://graph.facebook.com`. */
    val GraphApi: HttpUrl = HttpUrl(Present("https"), "graph.facebook.com", 443, "/", Absent)

    /** The largest response bound kyo-http can hold. */
    val MaxResponseLength: ByteSize = ByteSize.fromBytes(Int.MaxValue.toLong)

    private def problemOf(config: WhatsAppConfig): Maybe[WhatsAppInvalidConfigException.Problem] =
        import WhatsAppInvalidConfigException.Problem
        urlProblemOf(config.baseUrl).map(Problem.BaseUrl(_))
            .orElse(if validVersion(config.apiVersion) then Absent else Present(Problem.ApiVersion))
            .orElse(if validPhoneNumberId(config.phoneNumberId.value) then Absent else Present(Problem.PhoneNumberId))
            .orElse(if positiveFinite(config.requestTimeout) then Absent else Present(Problem.RequestTimeout(config.requestTimeout)))
            .orElse(if positiveFinite(config.connectTimeout) then Absent else Present(Problem.ConnectTimeout(config.connectTimeout)))
            .orElse(
                if config.maxResponseLength.toBytes >= 1 && config.maxResponseLength.toBytes <= MaxResponseLength.toBytes then Absent
                else Present(Problem.MaxResponseLength(config.maxResponseLength, MaxResponseLength))
            )
    end problemOf

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
    private[kyo] def versioned(config: WhatsAppConfig, segments: String, query: Seq[(String, String)] = Seq.empty): HttpUrl =
        val base = if config.baseUrl.path == "/" then "" else config.baseUrl.path
        val raw  =
            if query.isEmpty then Absent
            else Present(query.map((k, v) => s"${encode(k)}=${encode(v)}").mkString("&"))
        config.baseUrl.copy(path = s"$base/${config.apiVersion}/$segments", rawQuery = raw)
    end versioned

    /** The whole kyo-http configuration of a request that carries the token; nothing is inherited from the caller. */
    private[kyo] def httpConfig(config: WhatsAppConfig): HttpClientConfig =
        HttpClientConfig(
            baseUrl = Absent,
            timeout = config.requestTimeout,
            connectTimeout = config.connectTimeout,
            followRedirects = false,
            maxRedirects = 0,
            retrySchedule = Absent,
            retryOn = _.isServerError,
            transportConfig = HttpTransportConfig.default,
            tls = HttpTlsConfig.default,
            maxResponseLength = config.maxResponseLength.toBytes.toInt,
            autoFilters = false,
            clientFilter = HttpFilter.noop
        )

    /** RFC 3986 percent-encoding of every byte outside the unreserved set, over UTF-8. */
    private def encode(s: String): String =
        val sb = new StringBuilder
        s.getBytes(java.nio.charset.StandardCharsets.UTF_8).foreach { b =>
            val c = (b & 0xff).toChar
            if (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~'
            then discard(sb.append(c))
            else discard(sb.append('%').append("0123456789ABCDEF".charAt((b >> 4) & 0xf)).append("0123456789ABCDEF".charAt(b & 0xf)))
            end if
        }
        sb.toString
    end encode

end WhatsAppConfig
