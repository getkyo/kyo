package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpClientConfig")
class JsHttpClientConfig(@JSName("$http") val underlying: HttpClientConfig) extends js.Object:
    import kyo.JsFacadeGivens.given
    def baseUrl(url: Predef.String) =
        new JsHttpClientConfig(underlying.baseUrl(url))

    def baseUrl() =
        new JsMaybe(underlying.baseUrl)

    def connectTimeout(d: JsDuration) =
        new JsHttpClientConfig(underlying.connectTimeout(d.underlying))

    def connectTimeout() =
        new JsMaybe(underlying.connectTimeout)

    def followRedirects(v: Boolean) =
        new JsHttpClientConfig(underlying.followRedirects(v))

    def followRedirects() =
        underlying.followRedirects

    def maxRedirects(v: Int) =
        new JsHttpClientConfig(underlying.maxRedirects(v))

    def maxRedirects() =
        underlying.maxRedirects

    def retry(schedule: JsSchedule) =
        new JsHttpClientConfig(underlying.retry(schedule.underlying))

    def retryOn() =
        underlying.retryOn

    def retryOn(f: Function1[HttpStatus, Boolean]) =
        new JsHttpClientConfig(underlying.retryOn(f))

    def retrySchedule() =
        new JsMaybe(underlying.retrySchedule)

    def timeout() =
        new JsMaybe(underlying.timeout)

    def timeout(d: JsDuration) =
        new JsHttpClientConfig(underlying.timeout(d.underlying))


end JsHttpClientConfig