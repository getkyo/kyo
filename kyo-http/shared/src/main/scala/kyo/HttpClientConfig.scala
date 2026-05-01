package kyo

import kyo.*

/** Configuration for an [[kyo.HttpClient]], controlling timeouts, retries, redirects, and base URL resolution.
  *
  * Applied via `HttpClient.withConfig(_.timeout(10.seconds)) { ... }`. The function overload composes with the current config — nested
  * `withConfig` calls stack rather than replace each other, so each layer only overrides the fields it changes. To discard the current
  * config entirely, use `HttpClient.withConfig(newConfig) { ... }`.
  *
  * The `baseUrl` field is resolved only for requests with path-only URLs (where `scheme` is absent). A request to `/users` with
  * `baseUrl("https://api.example.com")` resolves to `https://api.example.com/users`. Requests with a full URL ignore `baseUrl`. WebSocket
  * connections also honor `baseUrl` for path-only URLs.
  *
  * Retry behavior is inactive unless a `retrySchedule` is set. When active, the client retries on network errors and on responses where
  * `retryOn(status)` returns true (default: `_.isServerError`). The `timeout` wraps the entire retry loop, so a short timeout may prevent
  * retries from running. Retries do not apply to WebSocket or streaming connections.
  *
  * @param baseUrl
  *   Prefix for path-only request URLs. Absent by default — all URLs must be absolute. When set, requests to `/path` resolve to
  *   `baseUrl + /path`. Requests with a scheme (e.g. `https://...`) ignore this field. Also applied to WebSocket connections.
  * @param timeout
  *   Maximum duration for the entire request lifecycle including retries. Defaults to 5 seconds. Set to `Duration.Infinity` to disable.
  *   Does not apply to WebSocket connections (they are long-lived by design).
  * @param connectTimeout
  *   Maximum duration for the TCP connect (and TLS handshake if applicable). Defaults to 30 seconds. Set to `Duration.Infinity` to use the
  *   OS TCP timeout instead. Applies to both HTTP and WebSocket connections.
  * @param followRedirects
  *   Whether to automatically follow 3xx redirects. Defaults to true. When enabled, the client follows up to `maxRedirects` hops, handling
  *   303 See Other by changing the method to GET per RFC 9110.
  * @param maxRedirects
  *   Maximum number of redirect hops before failing with [[HttpRedirectLoopException]]. Defaults to 10. Must be non-negative.
  * @param retrySchedule
  *   Backoff schedule for retries. Absent by default (no retries). When set, the client retries requests that fail with network errors or
  *   where `retryOn(status)` returns true.
  * @param retryOn
  *   Predicate that determines which response status codes trigger a retry. Defaults to `_.isServerError` (5xx). Only evaluated when
  *   `retrySchedule` is set.
  * @param transportConfig
  *   Low-level I/O tuning: read buffer size, channel capacity, I/O pool size. See [[HttpTransportConfig]].
  * @param tls
  *   TLS settings applied to HTTPS connections made by this client. See [[HttpTlsConfig]]. Per-request TLS overrides can be set via
  *   `HttpClientConfig` on [[HttpClient.init]].
  *
  * @see
  *   [[kyo.HttpClient.withConfig]] Applies this config to a block of code
  * @see
  *   [[kyo.HttpTransportConfig]] Low-level I/O buffer and pool size tuning
  * @see
  *   [[kyo.HttpTlsConfig]] TLS certificate validation settings
  * @see
  *   [[kyo.Schedule]] Controls retry timing and backoff
  */
case class HttpClientConfig(
    baseUrl: Maybe[HttpUrl] = Absent,
    timeout: Duration = 5.seconds,
    connectTimeout: Duration = 30.seconds,
    followRedirects: Boolean = true,
    maxRedirects: Int = 10,
    retrySchedule: Maybe[Schedule] = Absent,
    retryOn: HttpStatus => Boolean = _.isServerError,
    transportConfig: HttpTransportConfig = HttpTransportConfig.default,
    tls: HttpTlsConfig = HttpTlsConfig.default
):
    require(maxRedirects >= 0, s"maxRedirects must be non-negative: $maxRedirects")
    require(timeout > Duration.Zero || timeout == Duration.Infinity, s"timeout must be positive or Infinity: $timeout")
    require(
        connectTimeout > Duration.Zero || connectTimeout == Duration.Infinity,
        s"connectTimeout must be positive or Infinity: $connectTimeout"
    )

    def baseUrl(url: String)(using Frame): HttpClientConfig       = copy(baseUrl = Present(HttpUrl.parse(url).getOrThrow))
    def baseUrl(url: HttpUrl): HttpClientConfig                   = copy(baseUrl = Present(url))
    def timeout(d: Duration): HttpClientConfig                    = copy(timeout = d)
    def connectTimeout(d: Duration): HttpClientConfig             = copy(connectTimeout = d)
    def followRedirects(v: Boolean): HttpClientConfig             = copy(followRedirects = v)
    def maxRedirects(v: Int): HttpClientConfig                    = copy(maxRedirects = v)
    def retry(schedule: Schedule): HttpClientConfig               = copy(retrySchedule = Present(schedule))
    def retryOn(f: HttpStatus => Boolean): HttpClientConfig       = copy(retryOn = f)
    def transportConfig(v: HttpTransportConfig): HttpClientConfig = copy(transportConfig = v)
    def tls(config: HttpTlsConfig): HttpClientConfig              = copy(tls = config)
end HttpClientConfig
