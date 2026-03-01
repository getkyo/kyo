package kyo

import kyo.*

/** An HTTP response carrying status, headers, and a typed `Record` of route-declared fields.
  *
  * In route-based handlers, construct responses using the companion's factory methods (`okJson`, `badRequestText`, `redirect`, etc.) and
  * add declared fields with `.addField("body", value)`. The `Fields` type parameter mirrors the route's `ResponseDef` — the compiler
  * ensures you provide all declared fields.
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

    def okText(body: String): HttpResponse["body" ~ String]                 = ok.addField("body", body)
    def okJson[A: Json](body: A): HttpResponse["body" ~ A]                  = ok.addField("body", body)
    def okBinary(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]]       = ok.addField("body", body)
    def createdText(body: String): HttpResponse["body" ~ String]            = created.addField("body", body)
    def createdJson[A: Json](body: A): HttpResponse["body" ~ A]             = created.addField("body", body)
    def createdBinary(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]]  = created.addField("body", body)
    def acceptedText(body: String): HttpResponse["body" ~ String]           = accepted.addField("body", body)
    def acceptedJson[A: Json](body: A): HttpResponse["body" ~ A]            = accepted.addField("body", body)
    def acceptedBinary(body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] = accepted.addField("body", body)

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

    def badRequestText(body: String): HttpResponse["body" ~ String]          = badRequest.addField("body", body)
    def badRequestJson[A: Json](body: A): HttpResponse["body" ~ A]           = badRequest.addField("body", body)
    def unauthorizedText(body: String): HttpResponse["body" ~ String]        = unauthorized.addField("body", body)
    def unauthorizedJson[A: Json](body: A): HttpResponse["body" ~ A]         = unauthorized.addField("body", body)
    def forbiddenText(body: String): HttpResponse["body" ~ String]           = forbidden.addField("body", body)
    def forbiddenJson[A: Json](body: A): HttpResponse["body" ~ A]            = forbidden.addField("body", body)
    def notFoundText(body: String): HttpResponse["body" ~ String]            = notFound.addField("body", body)
    def notFoundJson[A: Json](body: A): HttpResponse["body" ~ A]             = notFound.addField("body", body)
    def conflictText(body: String): HttpResponse["body" ~ String]            = conflict.addField("body", body)
    def conflictJson[A: Json](body: A): HttpResponse["body" ~ A]             = conflict.addField("body", body)
    def unprocessableEntityText(body: String): HttpResponse["body" ~ String] = unprocessableEntity.addField("body", body)
    def unprocessableEntityJson[A: Json](body: A): HttpResponse["body" ~ A]  = unprocessableEntity.addField("body", body)
    def tooManyRequestsText(body: String): HttpResponse["body" ~ String]     = tooManyRequests.addField("body", body)
    def tooManyRequestsJson[A: Json](body: A): HttpResponse["body" ~ A]      = tooManyRequests.addField("body", body)

    // 5xx
    def serverError: HttpResponse[Any]        = apply(HttpStatus.InternalServerError)
    def serviceUnavailable: HttpResponse[Any] = apply(HttpStatus.ServiceUnavailable)

    def serverErrorText(body: String): HttpResponse["body" ~ String]        = serverError.addField("body", body)
    def serverErrorJson[A: Json](body: A): HttpResponse["body" ~ A]         = serverError.addField("body", body)
    def serviceUnavailableText(body: String): HttpResponse["body" ~ String] = serviceUnavailable.addField("body", body)
    def serviceUnavailableJson[A: Json](body: A): HttpResponse["body" ~ A]  = serviceUnavailable.addField("body", body)

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
