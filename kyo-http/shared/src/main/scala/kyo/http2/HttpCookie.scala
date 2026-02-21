package kyo.http2

import kyo.Absent
import kyo.Duration
import kyo.Maybe
import kyo.Present

case class HttpCookie[A](
    value: A,
    codec: HttpCodec[A],
    maxAge: Maybe[Duration] = Absent,
    domain: Maybe[String] = Absent,
    path: Maybe[String] = Absent,
    secure: Boolean = false,
    httpOnly: Boolean = false,
    sameSite: Maybe[HttpCookie.SameSite] = Absent
):
    def maxAge(d: Duration): HttpCookie[A]              = copy(maxAge = Present(d))
    def domain(d: String): HttpCookie[A]                = copy(domain = Present(d))
    def path(p: String): HttpCookie[A]                  = copy(path = Present(p))
    def secure(b: Boolean): HttpCookie[A]               = copy(secure = b)
    def httpOnly(b: Boolean): HttpCookie[A]             = copy(httpOnly = b)
    def sameSite(s: HttpCookie.SameSite): HttpCookie[A] = copy(sameSite = Present(s))
end HttpCookie

object HttpCookie:

    given [A, B](using CanEqual[A, B]): CanEqual[HttpCookie[A], HttpCookie[B]] = CanEqual.derived

    def apply[A](value: A)(using codec: HttpCodec[A]): HttpCookie[A] =
        HttpCookie(value, codec)

    enum SameSite derives CanEqual:
        case Strict, Lax, None

end HttpCookie
