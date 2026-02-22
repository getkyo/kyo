package kyo.http2

import kyo.Record2
import kyo.Record2.~

case class HttpResponse[Fields](
    status: HttpStatus,
    headers: HttpHeaders,
    fields: Record2[Fields]
):
    def addField[N <: String & Singleton, V](name: N, value: V): HttpResponse[Fields & name.type ~ V] =
        copy(fields = fields & name ~ value)

    def addFields[Fields2](r: Record2[Fields2]): HttpResponse[Fields & Fields2] =
        copy(fields = fields & r)

    def addHeader(name: String, value: String): HttpResponse[Fields] =
        copy(headers = headers.add(name, value))

    def setHeader(name: String, value: String): HttpResponse[Fields] =
        copy(headers = headers.set(name, value))

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
        HttpResponse(status, HttpHeaders.empty, Record2.empty)

    // 2xx
    def ok: HttpResponse[Any]        = apply(HttpStatus.OK)
    def created: HttpResponse[Any]   = apply(HttpStatus.Created)
    def accepted: HttpResponse[Any]  = apply(HttpStatus.Accepted)
    def noContent: HttpResponse[Any] = apply(HttpStatus.NoContent)

    // 3xx
    def redirect(url: String): HttpResponse[Any] =
        apply(HttpStatus.Found).setHeader("Location", url)
    def movedPermanently(url: String): HttpResponse[Any] =
        apply(HttpStatus.MovedPermanently).setHeader("Location", url)
    def notModified: HttpResponse[Any] = apply(HttpStatus.NotModified)

    // 4xx
    def badRequest: HttpResponse[Any]          = apply(HttpStatus.BadRequest)
    def unauthorized: HttpResponse[Any]        = apply(HttpStatus.Unauthorized)
    def forbidden: HttpResponse[Any]           = apply(HttpStatus.Forbidden)
    def notFound: HttpResponse[Any]            = apply(HttpStatus.NotFound)
    def conflict: HttpResponse[Any]            = apply(HttpStatus.Conflict)
    def unprocessableEntity: HttpResponse[Any] = apply(HttpStatus.UnprocessableEntity)
    def tooManyRequests: HttpResponse[Any]     = apply(HttpStatus.TooManyRequests)

    // 5xx
    def serverError: HttpResponse[Any]        = apply(HttpStatus.InternalServerError)
    def serviceUnavailable: HttpResponse[Any] = apply(HttpStatus.ServiceUnavailable)

end HttpResponse
