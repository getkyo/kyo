package kyo.http2

import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.Record
import kyo.Record.~
import kyo.Tag

case class HttpResponse[Fields](
    status: HttpStatus,
    headers: HttpHeaders,
    cookies: Seq[(String, HttpCookie.Response[String])],
    fields: Record[Fields]
):
    def addField[N <: String & Singleton, V](name: N, value: V)(using Tag[V]): HttpResponse[Fields & name.type ~ V] =
        copy(fields = fields & name ~ value)

    def addFields[Fields2](r: Record[Fields2]): HttpResponse[Fields & Fields2] =
        copy(fields = fields & r)

    def cookie(name: String): Maybe[HttpCookie.Response[String]] =
        cookies.collectFirst { case (n, c) if n == name => c } match
            case Some(c) => Present(c)
            case None    => Absent

    def addHeader(name: String, value: String): HttpResponse[Fields] =
        copy(headers = headers.add(name, value))

    def setHeader(name: String, value: String): HttpResponse[Fields] =
        copy(headers = headers.set(name, value))

    def addCookie(name: String, cookie: HttpCookie.Response[String]): HttpResponse[Fields] =
        copy(cookies = cookies :+ (name, cookie))

    def addCookie(name: String, value: String)(using HttpCodec[String]): HttpResponse[Fields] =
        copy(cookies = cookies :+ (name, HttpCookie.Response(value)))

    // --- Common builder methods ---

    def cacheControl(directive: String): HttpResponse[Fields] =
        setHeader("Cache-Control", directive)

    def noCache: HttpResponse[Fields] = cacheControl("no-cache")

    def noStore: HttpResponse[Fields] = cacheControl("no-store")

    def etag(value: String): HttpResponse[Fields] =
        val quoted = if value.startsWith("\"") then value else s""""$value""""
        setHeader("ETag", quoted)

    def contentDisposition(filename: String, isInline: Boolean = false): HttpResponse[Fields] =
        val disposition = if isInline then "inline" else "attachment"
        setHeader("Content-Disposition", s"""$disposition; filename="$filename"""")

end HttpResponse

object HttpResponse:

    def apply(status: HttpStatus): HttpResponse[Any] =
        HttpResponse(status, HttpHeaders.empty, Seq.empty, Record.empty)

    // --- 2xx Success ---

    def ok: HttpResponse[Any]        = apply(HttpStatus.OK)
    def created: HttpResponse[Any]   = apply(HttpStatus.Created)
    def accepted: HttpResponse[Any]  = apply(HttpStatus.Accepted)
    def noContent: HttpResponse[Any] = apply(HttpStatus.NoContent)

    // --- 3xx Redirect ---

    def redirect(url: String): HttpResponse[Any] =
        apply(HttpStatus.Found).setHeader("Location", url)

    def movedPermanently(url: String): HttpResponse[Any] =
        apply(HttpStatus.MovedPermanently).setHeader("Location", url)

    def notModified: HttpResponse[Any] = apply(HttpStatus.NotModified)

    // --- 4xx Client Error ---

    def badRequest: HttpResponse[Any]          = apply(HttpStatus.BadRequest)
    def unauthorized: HttpResponse[Any]        = apply(HttpStatus.Unauthorized)
    def forbidden: HttpResponse[Any]           = apply(HttpStatus.Forbidden)
    def notFound: HttpResponse[Any]            = apply(HttpStatus.NotFound)
    def conflict: HttpResponse[Any]            = apply(HttpStatus.Conflict)
    def unprocessableEntity: HttpResponse[Any] = apply(HttpStatus.UnprocessableEntity)
    def tooManyRequests: HttpResponse[Any]     = apply(HttpStatus.TooManyRequests)

    // --- 5xx Server Error ---

    def serverError: HttpResponse[Any]        = apply(HttpStatus.InternalServerError)
    def serviceUnavailable: HttpResponse[Any] = apply(HttpStatus.ServiceUnavailable)

end HttpResponse
