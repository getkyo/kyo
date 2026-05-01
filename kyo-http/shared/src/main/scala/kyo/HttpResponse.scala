package kyo

import kyo.*
import scala.annotation.targetName

/** An HTTP response carrying status, headers, and a typed `Record` of route-declared fields.
  *
  * In route-based handlers, construct responses using the companion's factory methods (`ok`, `badRequest`, `redirect`, etc.) and add
  * declared fields with `.addField("body", value)`. The `Fields` type parameter mirrors the route's `ResponseDef` — the compiler ensures
  * you provide all declared fields.
  *
  * Use `HttpResponse.halt(response)` to short-circuit request processing from any handler or filter, aborting with the given response
  * immediately. This is the standard mechanism for early exits (e.g., authorization failures), not exception throwing.
  *
  * @tparam Fields
  *   intersection type of all route-declared response fields (e.g., `"body" ~ User & "session" ~ HttpCookie[String]`)
  *
  * @see
  *   [[kyo.HttpRoute.ResponseDef]] Declares the response structure
  * @see
  *   [[kyo.HttpResponse.Halt]] The short-circuit wrapper used with `Abort`
  */
case class HttpResponse[Fields](
    status: HttpStatus,
    headers: HttpHeaders,
    fields: Record[Fields]
):
    def addField[N <: String & Singleton, V](name: N, value: V): HttpResponse[Fields & name.type ~ V] =
        copy(fields = fields & name ~ value)

    def addFields[Fields2](r: Record[Fields2]): HttpResponse[Fields & Fields2] =
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
        HttpResponse(status, HttpHeaders.empty, Record.empty)

    // 2xx
    def ok: HttpResponse[Any]        = apply(HttpStatus.OK)
    def created: HttpResponse[Any]   = apply(HttpStatus.Created)
    def accepted: HttpResponse[Any]  = apply(HttpStatus.Accepted)
    def noContent: HttpResponse[Any] = apply(HttpStatus.NoContent)

    def ok(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.OK).addField("body", body)
    @targetName("okJson")
    def ok[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.OK).addField("body", body)
    @targetName("okBinary")
    def ok(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.OK).addField("body", body)

    def created(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.Created).addField("body", body)
    @targetName("createdJson")
    def created[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.Created).addField("body", body)
    @targetName("createdBinary")
    def created(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.Created).addField("body", body)

    def accepted(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.Accepted).addField("body", body)
    @targetName("acceptedJson")
    def accepted[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.Accepted).addField("body", body)
    @targetName("acceptedBinary")
    def accepted(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.Accepted).addField("body", body)

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

    def badRequest(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.BadRequest).addField("body", body)
    @targetName("badRequestJson")
    def badRequest[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.BadRequest).addField("body", body)
    @targetName("badRequestBinary")
    def badRequest(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.BadRequest).addField("body", body)

    def unauthorized(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.Unauthorized).addField("body", body)
    @targetName("unauthorizedJson")
    def unauthorized[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.Unauthorized).addField("body", body)
    @targetName("unauthorizedBinary")
    def unauthorized(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.Unauthorized).addField("body", body)

    def forbidden(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.Forbidden).addField("body", body)
    @targetName("forbiddenJson")
    def forbidden[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.Forbidden).addField("body", body)
    @targetName("forbiddenBinary")
    def forbidden(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.Forbidden).addField("body", body)

    def notFound(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.NotFound).addField("body", body)
    @targetName("notFoundJson")
    def notFound[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.NotFound).addField("body", body)
    @targetName("notFoundBinary")
    def notFound(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.NotFound).addField("body", body)

    def conflict(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.Conflict).addField("body", body)
    @targetName("conflictJson")
    def conflict[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.Conflict).addField("body", body)
    @targetName("conflictBinary")
    def conflict(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.Conflict).addField("body", body)

    def unprocessableEntity(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.UnprocessableEntity).addField("body", body)
    @targetName("unprocessableEntityJson")
    def unprocessableEntity[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.UnprocessableEntity).addField("body", body)
    @targetName("unprocessableEntityBinary")
    def unprocessableEntity(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] =
        apply(HttpStatus.UnprocessableEntity).addField("body", body)

    def tooManyRequests(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.TooManyRequests).addField("body", body)
    @targetName("tooManyRequestsJson")
    def tooManyRequests[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.TooManyRequests).addField("body", body)
    @targetName("tooManyRequestsBinary")
    def tooManyRequests(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.TooManyRequests).addField("body", body)

    // 5xx
    def serverError: HttpResponse[Any]        = apply(HttpStatus.InternalServerError)
    def serviceUnavailable: HttpResponse[Any] = apply(HttpStatus.ServiceUnavailable)

    def serverError(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.InternalServerError).addField("body", body)
    @targetName("serverErrorJson")
    def serverError[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.InternalServerError).addField("body", body)
    @targetName("serverErrorBinary")
    def serverError(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = apply(HttpStatus.InternalServerError).addField("body", body)

    def serviceUnavailable(body: String): HttpResponse["body" ~ String] = apply(HttpStatus.ServiceUnavailable).addField("body", body)
    @targetName("serviceUnavailableJson")
    def serviceUnavailable[A: Schema](body: A): HttpResponse["body" ~ A] = apply(HttpStatus.ServiceUnavailable).addField("body", body)
    @targetName("serviceUnavailableBinary")
    def serviceUnavailable(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] =
        apply(HttpStatus.ServiceUnavailable).addField("body", body)

    /** Short-circuit signal for filters and handlers to abort request processing and send a response immediately.
      *
      * Used with `Abort.fail(Halt(response))` or the convenience `HttpResponse.halt(response)`. When a filter or handler aborts with Halt,
      * the framework skips remaining processing and sends the wrapped response directly. This is the idiomatic way to handle authorization
      * failures, rate limiting, and other early exits.
      */
    case class Halt(response: HttpResponse[Any])

    /** Convenience for short-circuiting: aborts with the given response immediately. */
    def halt(response: HttpResponse[Any])(using Frame): Nothing < Abort[Halt] =
        Abort.fail(Halt(response))

end HttpResponse
