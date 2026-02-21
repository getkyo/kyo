package kyo.http2

import kyo.Absent
import kyo.Duration
import kyo.Maybe
import kyo.Present
import scala.annotation.targetName

sealed abstract class HttpCookie[A]:
    def value: A
    def codec: HttpCodec[A]

object HttpCookie:

    given [A, B](using CanEqual[A, B]): CanEqual[HttpCookie[A], HttpCookie[B]] = CanEqual.derived

    case class Request[A](value: A, codec: HttpCodec[A]) extends HttpCookie[A]:
        def toResponse: Response[A] = Response(value, codec)

    object Request:
        @targetName("init")
        def apply[A](value: A)(using codec: HttpCodec[A]): Request[A] =
            Request(value, codec)
    end Request

    case class Response[A](
        value: A,
        codec: HttpCodec[A],
        maxAge: Maybe[Duration] = Absent,
        domain: Maybe[String] = Absent,
        path: Maybe[String] = Absent,
        secure: Boolean = false,
        httpOnly: Boolean = false,
        sameSite: Maybe[Response.SameSite] = Absent
    ) extends HttpCookie[A]:
        def maxAge(d: Duration): Response[A]            = copy(maxAge = Present(d))
        def domain(d: String): Response[A]              = copy(domain = Present(d))
        def path(p: String): Response[A]                = copy(path = Present(p))
        def secure(b: Boolean): Response[A]             = copy(secure = b)
        def httpOnly(b: Boolean): Response[A]           = copy(httpOnly = b)
        def sameSite(s: Response.SameSite): Response[A] = copy(sameSite = Present(s))
    end Response

    object Response:
        def apply[A](value: A)(using codec: HttpCodec[A]): Response[A] =
            Response(value, codec)

        enum SameSite derives CanEqual:
            case Strict, Lax, None
    end Response

end HttpCookie
