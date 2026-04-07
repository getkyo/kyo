package kyo

import kyo.*

/** Client configuration controlling timeouts, retries, redirects, and base URL resolution.
  *
  * Applied via `HttpClient.withConfig(_.timeout(10.seconds)) { ... }`. The function overload stacks with the current config, so nested
  * `withConfig` calls compose rather than replace each other.
  *
  * The `baseUrl` is used only for requests with relative paths (where `scheme` is absent). A request to `/users` with
  * `baseUrl("https://api.example.com")` resolves to `https://api.example.com/users`.
  *
  * @see
  *   [[kyo.HttpClient.withConfig]] Applies this config to a block of code
  * @see
  *   [[kyo.Schedule]] Controls retry timing
  */
case class HttpClientConfig(
    baseUrl: Maybe[HttpUrl] = Absent,
    timeout: Maybe[Duration] = Present(5.seconds),
    connectTimeout: Maybe[Duration] = Absent,
    followRedirects: Boolean = true,
    maxRedirects: Int = 10,
    retrySchedule: Maybe[Schedule] = Absent,
    retryOn: HttpStatus => Boolean = _.isServerError
):
    require(maxRedirects >= 0, s"maxRedirects must be non-negative: $maxRedirects")
    timeout.foreach(d => require(d > Duration.Zero, s"timeout must be positive: $d"))
    connectTimeout.foreach(d => require(d > Duration.Zero, s"connectTimeout must be positive: $d"))

    def baseUrl(url: String)(using Frame): HttpClientConfig = copy(baseUrl = Present(HttpUrl.parse(url).getOrThrow))
    def baseUrl(url: HttpUrl): HttpClientConfig             = copy(baseUrl = Present(url))
    def timeout(d: Duration): HttpClientConfig              = copy(timeout = Present(d))
    def connectTimeout(d: Duration): HttpClientConfig       = copy(connectTimeout = Present(d))
    def followRedirects(v: Boolean): HttpClientConfig       = copy(followRedirects = v)
    def maxRedirects(v: Int): HttpClientConfig              = copy(maxRedirects = v)
    def retry(schedule: Schedule): HttpClientConfig         = copy(retrySchedule = Present(schedule))
    def retryOn(f: HttpStatus => Boolean): HttpClientConfig = copy(retryOn = f)
end HttpClientConfig
