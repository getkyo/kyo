package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpCookie")
class JsHttpCookie[A](@JSName("$http") val underlying: HttpCookie[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def codec() =
        new JsHttpCodec(underlying.codec)

    def domain(d: Predef.String) =
        new JsHttpCookie(underlying.domain(d))

    def domain() =
        new JsMaybe(underlying.domain)

    def httpOnly() =
        underlying.httpOnly

    def httpOnly(b: Boolean) =
        new JsHttpCookie(underlying.httpOnly(b))

    def maxAge(d: JsDuration) =
        new JsHttpCookie(underlying.maxAge(d.underlying))

    def maxAge() =
        new JsMaybe(underlying.maxAge)

    def path() =
        new JsMaybe(underlying.path)

    def path(p: Predef.String) =
        new JsHttpCookie(underlying.path(p))

    def sameSite() =
        new JsMaybe(underlying.sameSite)

    def sameSite(s: HttpCookie.SameSite) =
        new JsHttpCookie(underlying.sameSite(s))

    def secure(b: Boolean) =
        new JsHttpCookie(underlying.secure(b))

    def secure() =
        underlying.secure

    def value() =
        underlying.value


end JsHttpCookie

object JsHttpCookie:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[A](value: A) =
        new JsHttpCookie(HttpCookie.apply(value))

    @JSExportStatic
    def apply[A](value: A, codec: JsHttpCodec[A], maxAge: JsMaybe[Duration], domain: JsMaybe[Predef.String], path: JsMaybe[Predef.String], secure: Boolean, httpOnly: Boolean, sameSite: JsMaybe[HttpCookie.SameSite]) =
        new JsHttpCookie(HttpCookie.apply(value, codec.underlying, maxAge.underlying, domain.underlying, path.underlying, secure, httpOnly, sameSite.underlying))

    @JSExportStatic
    def given_CanEqual_HttpCookie_HttpCookie[A, B]() =
        HttpCookie.given_CanEqual_HttpCookie_HttpCookie


end JsHttpCookie