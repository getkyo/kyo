package kyo

import kyo.*

/** Configuration for an [[kyo.HttpClient]], controlling timeouts, retries, redirects, client filters, and base URL resolution.
  *
  * Applied via `HttpClient.withConfig(_.timeout(10.seconds)) { ... }`. The function overload composes with the current config, nested
  * `withConfig` calls stack rather than replace each other, so each layer only overrides the fields it changes. To discard the current
  * config entirely, use `HttpClient.withConfig(newConfig) { ... }`.
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
  *   seconds. A zero timeout fails the request at once with [[HttpTimeoutException]], before any connection is taken
  *   or opened. `Duration.Infinity` arms no deadline. A `Duration` is never negative: building one from a negative amount yields zero. A
  *   streamed body consumed inside that callback is under it; the streams `getStreamBytes`, `getSseJson`, `getSseText` and `getNdJson`
  *   return are consumed after their request completed at the head, so the timeout bounds the head and not the body. Does not apply to
  *   WebSocket connections (they are long-lived by design).
  * @param connectTimeout
  *   Maximum duration for the TCP connect. Defaults to 30 seconds. A zero deadline fails every connect at once with
  *   [[HttpConnectTimeoutException]]; a pooled connection that is already open is still reused. `Duration.Infinity` arms no deadline, so
  *   the OS TCP timeout applies. A TLS handshake has its own deadline, `HttpTransportConfig.handshakeTimeout`. Applies to both HTTP and
  *   WebSocket connections.
  * @param followRedirects
  *   Whether to automatically follow 3xx redirects. Defaults to true. When enabled, the client follows up to `maxRedirects` hops, handling
  *   303 See Other by changing the method to GET per RFC 9110.
  * @param maxRedirects
  *   Maximum number of redirect hops before failing with [[HttpRedirectLoopException]]. Defaults to 10. Zero fails the first redirect
  *   response with [[HttpRedirectLoopException]], and a negative value means zero.
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
  *   which is NOT subject to this cap (the caller controls consumption). Narrowed where it is enforced, as kyo-core's stream reads narrow
  *   their buffers: zero becomes one byte and a size beyond `Int.MaxValue` becomes `Int.MaxValue`, the most one buffered body can hold.
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
    timeout: Duration = 5.seconds,
    connectTimeout: Duration = 30.seconds,
    followRedirects: Boolean = true,
    maxRedirects: Int = 10,
    retrySchedule: Maybe[Schedule] = Absent,
    retryOn: HttpStatus => Boolean = _.isServerError,
    transportConfig: HttpTransportConfig = HttpTransportConfig.default,
    tls: HttpTlsConfig = HttpTlsConfig.default,
    maxResponseLength: ByteSize = HttpClientConfig.DefaultMaxResponseLength,
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

    def timeout(d: Duration): HttpClientConfig                       = copy(timeout = d)
    def connectTimeout(d: Duration): HttpClientConfig                = copy(connectTimeout = d)
    def followRedirects(v: Boolean): HttpClientConfig                = copy(followRedirects = v)
    def maxRedirects(v: Int): HttpClientConfig                       = copy(maxRedirects = v)
    def retry(schedule: Schedule): HttpClientConfig                  = copy(retrySchedule = Present(schedule))
    def retryOn(f: HttpStatus => Boolean): HttpClientConfig          = copy(retryOn = f)
    def transportConfig(v: HttpTransportConfig): HttpClientConfig    = copy(transportConfig = v)
    def tls(config: HttpTlsConfig): HttpClientConfig                 = copy(tls = config)
    def maxResponseLength(v: ByteSize): HttpClientConfig             = copy(maxResponseLength = v)
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

    /** The default cap on a buffered response body (see [[HttpClientConfig.maxResponseLength]]). */
    val DefaultMaxResponseLength: ByteSize = 100.mib

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

end HttpClientConfig
