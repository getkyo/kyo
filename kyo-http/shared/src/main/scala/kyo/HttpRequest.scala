package kyo

import kyo.*

/** An HTTP request carrying method, URL, headers, and a typed `Record` of route-declared fields.
  *
  * The `Fields` type parameter reflects the fields declared by the route's `RequestDef`. Inside a handler created with
  * `route.handler { req => ... }`, all declared fields are accessible via `req.fields` with compile-time checked access — for example,
  * `req.fields.id` for a path capture, `req.fields.page` for a query parameter, or `req.fields.body` for a parsed request body.
  *
  * For raw HTTP access outside of route-based handlers, use `query(name)` and `queryAll(name)` to read query parameters directly from the
  * URL.
  *
  * @tparam Fields
  *   intersection type of all route-declared fields (e.g., `"id" ~ Int & "body" ~ User`)
  *
  * @see
  *   [[kyo.HttpRoute.RequestDef]] Declares which fields to extract
  * @see
  *   [[kyo.Record]] The typed record holding field values at runtime
  * @see
  *   [[kyo.HttpResponse]] The corresponding response type
  */
case class HttpRequest[Fields](
    method: HttpMethod,
    url: HttpUrl,
    headers: HttpHeaders,
    fields: Record[Fields]
):
    def path: String = url.path

    def query(name: String): Maybe[String] = url.query(name)

    def queryAll(name: String): Seq[String] = url.queryAll(name)

    def addField[N <: String & Singleton, V](name: N, value: V): HttpRequest[Fields & name.type ~ V] =
        copy(fields = fields & name ~ value)

    def addFields[Fields2](r: Record[Fields2]): HttpRequest[Fields & Fields2] =
        copy(fields = fields & r)

    def addHeader(name: String, value: String): HttpRequest[Fields] =
        copy(headers = headers.add(name, value))

    def setHeader(name: String, value: String): HttpRequest[Fields] =
        copy(headers = headers.set(name, value))

end HttpRequest

object HttpRequest:

    def apply(method: HttpMethod, url: HttpUrl): HttpRequest[Any] =
        HttpRequest(method, url, HttpHeaders.empty, Record.empty)

    def parse(method: HttpMethod, rawUrl: String)(using Frame): Result[HttpException, HttpRequest[Any]] =
        HttpUrl.parse(rawUrl).map(url => apply(method, url))

    def getRaw(url: HttpUrl): HttpRequest[Any]     = apply(HttpMethod.GET, url)
    def postRaw(url: HttpUrl): HttpRequest[Any]    = apply(HttpMethod.POST, url)
    def putRaw(url: HttpUrl): HttpRequest[Any]     = apply(HttpMethod.PUT, url)
    def patchRaw(url: HttpUrl): HttpRequest[Any]   = apply(HttpMethod.PATCH, url)
    def deleteRaw(url: HttpUrl): HttpRequest[Any]  = apply(HttpMethod.DELETE, url)
    def headRaw(url: HttpUrl): HttpRequest[Any]    = apply(HttpMethod.HEAD, url)
    def optionsRaw(url: HttpUrl): HttpRequest[Any] = apply(HttpMethod.OPTIONS, url)

    def getRaw(rawUrl: String)(using Frame): Result[HttpException, HttpRequest[Any]]     = parse(HttpMethod.GET, rawUrl)
    def postRaw(rawUrl: String)(using Frame): Result[HttpException, HttpRequest[Any]]    = parse(HttpMethod.POST, rawUrl)
    def putRaw(rawUrl: String)(using Frame): Result[HttpException, HttpRequest[Any]]     = parse(HttpMethod.PUT, rawUrl)
    def patchRaw(rawUrl: String)(using Frame): Result[HttpException, HttpRequest[Any]]   = parse(HttpMethod.PATCH, rawUrl)
    def deleteRaw(rawUrl: String)(using Frame): Result[HttpException, HttpRequest[Any]]  = parse(HttpMethod.DELETE, rawUrl)
    def headRaw(rawUrl: String)(using Frame): Result[HttpException, HttpRequest[Any]]    = parse(HttpMethod.HEAD, rawUrl)
    def optionsRaw(rawUrl: String)(using Frame): Result[HttpException, HttpRequest[Any]] = parse(HttpMethod.OPTIONS, rawUrl)

    /** A single part in a multipart request, representing an uploaded file or form field.
      *
      * Parts are produced by routes with `.request(_.bodyMultipart)` or `.request(_.bodyMultipartStream)`. The `filename` and `contentType`
      * fields are present for file uploads but absent for plain form fields.
      */
    case class Part(
        name: String,
        filename: Maybe[String],
        contentType: Maybe[String],
        data: Span[Byte]
    ) derives CanEqual

end HttpRequest
