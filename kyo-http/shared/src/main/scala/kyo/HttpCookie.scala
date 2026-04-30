package kyo

import kyo.*

/** HTTP cookie with a typed value and configurable attributes (maxAge, domain, path, secure, httpOnly, sameSite).
  *
  * Cookie values are serialized using `HttpCodec[A]`, not `Schema[A]`. For typed cookies (e.g., `HttpCookie[Int]`), the codec handles
  * conversion.
  *
  * On routes, request cookies are declared with `.request(_.cookie[A]("name"))` and response cookies with `.response(_.cookie[A]("name"))`.
  * Request cookies are read from the `Cookie` header; response cookies are written as `Set-Cookie` headers with the configured attributes.
  *
  * @tparam A
  *   the type of the cookie value
  *
  * @see
  *   [[kyo.HttpHeaders.addCookie]] Low-level cookie header manipulation
  * @see
  *   [[kyo.HttpCodec]] Handles cookie value serialization
  */
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
