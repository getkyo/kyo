package kyo

import kyo.*

/** Configuration for an [[kyo.HttpClient]], controlling timeouts, retries, redirects, client filters, and base URL resolution.
  *
  * Applied via `HttpClient.withConfig(_.followRedirects(false)) { ... }`. The function overload composes with the current config, nested
  * `withConfig` calls stack rather than replace each other, so each layer only overrides the fields it changes. To discard the current
  * config entirely, use `HttpClient.withConfig(newConfig) { ... }`.
  *
  * The limits are checked values, so neither the constructor nor `copy` can hold one the client would misbehave on: a
  * [[HttpClientConfig.TimeLimit]], [[HttpClientConfig.RedirectLimit]] or [[HttpClientConfig.ResponseLimit]] is built only by its `init`.
  * The setters taking a raw `Duration` or `Int` return a `Result` that fails with an [[kyo.HttpConfigException]] at the caller's `Frame`,
  * and `HttpClient.withConfig(_.timeout(10.seconds)) { ... }` turns that into an `Abort` before the block runs. Chain further raw setters
  * with `flatMap`: `_.timeout(10.seconds).flatMap(_.connectTimeout(2.seconds))`.
  *
  * The `baseUrl` field is resolved only for requests with path-only URLs (where `scheme` is absent). A request to `/users` with the
  * base `https://api.example.com/v1` resolves to `https://api.example.com/v1/users`: the base's path is a prefix. Requests with a
  * full URL ignore `baseUrl`. WebSocket connections also honor `baseUrl` for path-only URLs.
  *
  * Retry behavior is inactive unless a `retrySchedule` is set. When active, the client retries on network errors and on responses where
  * `retryOn(status)` returns true (default: `_.isServerError`). The `timeout` wraps the entire retry loop, so a short timeout may prevent
  * retries from running. Retries do not apply to WebSocket or streaming connections.
  *
  * @param baseUrl
  *   Prefix for path-only request URLs. Absent by default, all URLs must be absolute. When set, requests to `/path` resolve to
  *   `baseUrl + /path`. Requests with a scheme (e.g. `https://...`) ignore this field. Also applied to WebSocket connections. A
  *   [[HttpClientConfig.BaseUrl]] is built by `BaseUrl.init`, or by the `baseUrl` setter that takes a `String` or an `HttpUrl`, each of
  *   which refuses a URL without a scheme, or without a host or Unix socket, with an [[kyo.HttpUrlParseException]].
  * @param timeout
  *   Maximum duration for the entire request lifecycle including retries, until the callback of `sendWith` returns. Defaults to 5
  *   seconds. `TimeLimit.unlimited` disables it. A streamed body consumed inside that callback is under it; the streams
  *   `getStreamBytes`, `getSseJson`, `getSseText` and `getNdJson` return are consumed after their request completed at the head, so the
  *   timeout bounds the head and not the body. Does not apply to WebSocket connections (they are long-lived by design).
  * @param connectTimeout
  *   Maximum duration for the TCP connect (and TLS handshake if applicable). Defaults to 30 seconds. `TimeLimit.unlimited` leaves it to
  *   the OS TCP timeout. Applies to both HTTP and WebSocket connections.
  * @param followRedirects
  *   Whether to automatically follow 3xx redirects. Defaults to true. When enabled, the client follows up to `maxRedirects` hops, handling
  *   303 See Other by changing the method to GET per RFC 9110.
  * @param maxRedirects
  *   Maximum number of redirect hops before failing with [[HttpRedirectLoopException]]. Defaults to 10.
  * @param retrySchedule
  *   Backoff schedule for retries. Absent by default (no retries). When set, the client retries requests that fail with network errors or
  *   where `retryOn(status)` returns true.
  * @param retryOn
  *   Predicate that determines which response status codes trigger a retry. Defaults to `_.isServerError` (5xx). Only evaluated when
  *   `retrySchedule` is set.
  * @param tls
  *   TLS settings applied to HTTPS connections made by this client. See [[HttpTlsConfig]]. Per-request TLS overrides can be set via
  *   `HttpClientConfig` on [[HttpClient.init]].
  * @param transportConfig
  *   Transport-level tuning applied to connections this client opens: the per-connection buffer sizes, the parser's header cap, and the TLS
  *   handshake deadline. The TCP connect deadline is not there, it is this config's own `connectTimeout`. See [[HttpTransportConfig]].
  * @param maxResponseLength
  *   Hard cap, in bytes, on a BUFFERED response body the client accumulates in memory. A server (malicious or buggy) that streams an
  *   unbounded chunked or connection-close-framed body, or declares an enormous `Content-Length`, would otherwise grow the client's buffer
  *   without limit until the JVM runs out of memory (CWE-400). When a buffered response exceeds this, the request fails with
  *   [[HttpPayloadTooLargeException]] instead. Defaults to 100 MiB: a safety ceiling, not a functional limit, large enough for realistic
  *   buffered JSON/HTML/file responses. Responses larger than this should use the streaming API (`getStreamBytes`, `getSseJson`, etc.),
  *   which is NOT subject to this cap (the caller controls consumption).
  * @param autoFilters
  *   Whether ServiceLoader-discovered client filters are applied. Defaults to true.
  * @param clientFilter
  *   Programmatic client filter applied to outgoing HTTP requests and WebSocket upgrade handshakes after auto filters and before route
  *   filters.
  *
  * @see
  *   [[kyo.HttpClient.withConfig]] Applies this config to a block of code
  * @see
  *   [[kyo.HttpTlsConfig]] TLS certificate validation settings
  * @see
  *   [[kyo.Schedule]] Controls retry timing and backoff
  */
case class HttpClientConfig(
    baseUrl: Maybe[HttpClientConfig.BaseUrl] = Absent,
    timeout: HttpClientConfig.TimeLimit = HttpClientConfig.TimeLimit.defaultTimeout,
    connectTimeout: HttpClientConfig.TimeLimit = HttpClientConfig.TimeLimit.defaultConnectTimeout,
    followRedirects: Boolean = true,
    maxRedirects: HttpClientConfig.RedirectLimit = HttpClientConfig.RedirectLimit.default,
    retrySchedule: Maybe[Schedule] = Absent,
    retryOn: HttpStatus => Boolean = _.isServerError,
    transportConfig: HttpTransportConfig = HttpTransportConfig.default,
    tls: HttpTlsConfig = HttpTlsConfig.default,
    maxResponseLength: HttpClientConfig.ResponseLimit = HttpClientConfig.ResponseLimit.default,
    autoFilters: Boolean = true,
    clientFilter: HttpFilter.Passthrough[Nothing] = HttpFilter.noop
):

    def baseUrl(base: HttpClientConfig.BaseUrl): HttpClientConfig = copy(baseUrl = Present(base))

    /** This config with `url` as its base, or the [[kyo.HttpUrlParseException]] naming why `url` cannot be one. */
    def baseUrl(url: String)(using Frame): Result[HttpUrlParseException, HttpClientConfig] =
        HttpClientConfig.BaseUrl.init(url).map(baseUrl)

    /** This config with `url` as its base, or the [[kyo.HttpUrlParseException]] naming why `url` cannot be one. */
    def baseUrl(url: HttpUrl)(using Frame): Result[HttpUrlParseException, HttpClientConfig] =
        HttpClientConfig.BaseUrl.init(url).map(baseUrl)

    def timeout(limit: HttpClientConfig.TimeLimit): HttpClientConfig = copy(timeout = limit)

    /** This config with `d` as its timeout, or the [[kyo.HttpConfigException]] refusing a zero duration. */
    def timeout(d: Duration)(using Frame): Result[HttpConfigException, HttpClientConfig] =
        HttpClientConfig.TimeLimit.check("timeout", d).map(timeout)

    def connectTimeout(limit: HttpClientConfig.TimeLimit): HttpClientConfig = copy(connectTimeout = limit)

    /** This config with `d` as its connect timeout, or the [[kyo.HttpConfigException]] refusing a zero duration. */
    def connectTimeout(d: Duration)(using Frame): Result[HttpConfigException, HttpClientConfig] =
        HttpClientConfig.TimeLimit.check("connectTimeout", d).map(connectTimeout)

    def maxRedirects(limit: HttpClientConfig.RedirectLimit): HttpClientConfig = copy(maxRedirects = limit)

    /** This config following at most `n` redirects, or the [[kyo.HttpConfigException]] refusing a negative `n`. */
    def maxRedirects(n: Int)(using Frame): Result[HttpConfigException, HttpClientConfig] =
        HttpClientConfig.RedirectLimit.init(n).map(maxRedirects)

    def maxResponseLength(limit: HttpClientConfig.ResponseLimit): HttpClientConfig = copy(maxResponseLength = limit)

    /** This config buffering at most `bytes` of a response, or the [[kyo.HttpConfigException]] refusing a non-positive `bytes`. */
    def maxResponseLength(bytes: Int)(using Frame): Result[HttpConfigException, HttpClientConfig] =
        HttpClientConfig.ResponseLimit.init(bytes).map(maxResponseLength)

    def followRedirects(v: Boolean): HttpClientConfig                = copy(followRedirects = v)
    def retry(schedule: Schedule): HttpClientConfig                  = copy(retrySchedule = Present(schedule))
    def retryOn(f: HttpStatus => Boolean): HttpClientConfig          = copy(retryOn = f)
    def transportConfig(v: HttpTransportConfig): HttpClientConfig    = copy(transportConfig = v)
    def tls(config: HttpTlsConfig): HttpClientConfig                 = copy(tls = config)
    def autoFilters(v: Boolean): HttpClientConfig                    = copy(autoFilters = v)
    def withoutAutoFilters: HttpClientConfig                         = autoFilters(false)
    def filter(f: HttpFilter.Passthrough[Nothing]): HttpClientConfig =
        copy(clientFilter = clientFilter.andThen(f))
    def filters(fs: Seq[HttpFilter.Passthrough[Nothing]]): HttpClientConfig =
        copy(clientFilter = fs.foldLeft(clientFilter)(_.andThen(_)))
    def clearFilters: HttpClientConfig =
        copy(clientFilter = HttpFilter.noop)
end HttpClientConfig

object HttpClientConfig:

    /** A URL a client can resolve path-only requests against: absolute, with a scheme and either a host or a Unix socket.
      *
      * Built only by `init`, so an [[HttpClientConfig]] cannot hold a base without a scheme or a host. Its path is a prefix: a request to
      * `/users` under `https://api.example.com/v1` goes to `https://api.example.com/v1/users`.
      */
    opaque type BaseUrl = HttpUrl

    object BaseUrl:
        /** `url` parsed as a base, or the [[kyo.HttpUrlParseException]] naming why it cannot be one. */
        def init(url: String)(using Frame): Result[HttpUrlParseException, BaseUrl] = HttpUrl.parseBase(url)

        /** `url` as a base, or the [[kyo.HttpUrlParseException]] naming why it cannot be one. */
        def init(url: HttpUrl)(using Frame): Result[HttpUrlParseException, BaseUrl] = HttpUrl.checkBase(url)

        given CanEqual[BaseUrl, BaseUrl] = CanEqual.derived

        extension (self: BaseUrl) def url: HttpUrl = self
    end BaseUrl

    /** A duration a request or a connect may take: positive, or `Duration.Infinity` for no limit. Zero would fail every request before it
      * starts, so it is refused rather than held.
      */
    opaque type TimeLimit = Duration

    object TimeLimit:
        val defaultTimeout: TimeLimit        = 5.seconds
        val defaultConnectTimeout: TimeLimit = 30.seconds

        /** How long a pooled connection may sit idle before the client closes it: the idle timeout proxies and load balancers default to.
          */
        val defaultIdleConnectionTimeout: TimeLimit = 60.seconds
        val unlimited: TimeLimit                    = Duration.Infinity

        /** `d` as a time limit, or the [[kyo.HttpConfigException]] refusing a zero duration. */
        def init(d: Duration)(using Frame): Result[HttpConfigException, TimeLimit] = check("timeLimit", d)

        private[kyo] def check(setting: String, d: Duration)(using Frame): Result[HttpConfigException, TimeLimit] =
            if d > Duration.Zero then Result.succeed(d)
            else Result.fail(HttpConfigException(setting, d.show, "positive, or Duration.Infinity for no limit"))

        given CanEqual[TimeLimit, TimeLimit] = CanEqual.derived

        extension (self: TimeLimit)
            def duration: Duration = self

            /** The longer of this limit and `floor`. Total: the result is never shorter than this limit, so it is valid too. */
            def max(floor: Duration): TimeLimit = if floor > self then floor else self
        end extension
    end TimeLimit

    /** How many redirects a request follows before failing with [[kyo.HttpRedirectLoopException]]: zero or more. */
    opaque type RedirectLimit = Int

    object RedirectLimit:
        val default: RedirectLimit = 10

        /** The limit for a literal `n`, checked at compile time: a negative literal, or an argument that is not a constant, does not
          * compile. A value known only at runtime goes through [[init]].
          */
        inline def apply(inline n: Int): RedirectLimit =
            inline if n < 0 then compiletime.error("HttpClientConfig.RedirectLimit must be zero or more")
            else n

        /** `n` as a redirect limit, or the [[kyo.HttpConfigException]] refusing a negative `n`. */
        def init(n: Int)(using Frame): Result[HttpConfigException, RedirectLimit] =
            if n >= 0 then Result.succeed(n)
            else Result.fail(HttpConfigException("maxRedirects", n.toString, "zero or more"))

        given CanEqual[RedirectLimit, RedirectLimit] = CanEqual.derived

        extension (self: RedirectLimit) def count: Int = self
    end RedirectLimit

    /** The most bytes of a buffered response body a client holds before failing with [[kyo.HttpPayloadTooLargeException]]: one or more. */
    opaque type ResponseLimit = Int

    object ResponseLimit:
        val default: ResponseLimit = 100 * 1024 * 1024

        /** The limit for a literal `bytes`, checked at compile time: a literal below one, or an argument that is not a constant, does not
          * compile. A value known only at runtime goes through [[init]].
          */
        inline def apply(inline bytes: Int): ResponseLimit =
            inline if bytes < 1 then compiletime.error("HttpClientConfig.ResponseLimit must be one or more")
            else bytes

        /** `bytes` as a response limit, or the [[kyo.HttpConfigException]] refusing a non-positive `bytes`. */
        def init(bytes: Int)(using Frame): Result[HttpConfigException, ResponseLimit] =
            if bytes > 0 then Result.succeed(bytes)
            else Result.fail(HttpConfigException("maxResponseLength", bytes.toString, "one byte or more"))

        given CanEqual[ResponseLimit, ResponseLimit] = CanEqual.derived

        extension (self: ResponseLimit) def bytes: Int = self
    end ResponseLimit

end HttpClientConfig
