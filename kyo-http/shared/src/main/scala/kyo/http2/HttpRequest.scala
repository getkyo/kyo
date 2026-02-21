package kyo.http2

import kyo.Frame
import kyo.Maybe
import kyo.Record
import kyo.Record.~
import kyo.Result
import kyo.Tag

case class HttpRequest[Fields](
    method: HttpMethod,
    url: HttpUrl,
    headers: HttpHeaders,
    fields: Record[Fields]
):
    def path: String = url.path

    def query(name: String): Maybe[String] = url.query(name)

    def queryAll(name: String): Seq[String] = url.queryAll(name)

    def addField[N <: String & Singleton, V](name: N, value: V)(using Tag[V]): HttpRequest[Fields & name.type ~ V] =
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

    def parse(method: HttpMethod, rawUrl: String)(using Frame): Result[HttpError, HttpRequest[Any]] =
        HttpUrl.parse(rawUrl).map(url => apply(method, url))

    def get(url: HttpUrl): HttpRequest[Any]     = apply(HttpMethod.GET, url)
    def post(url: HttpUrl): HttpRequest[Any]    = apply(HttpMethod.POST, url)
    def put(url: HttpUrl): HttpRequest[Any]     = apply(HttpMethod.PUT, url)
    def patch(url: HttpUrl): HttpRequest[Any]   = apply(HttpMethod.PATCH, url)
    def delete(url: HttpUrl): HttpRequest[Any]  = apply(HttpMethod.DELETE, url)
    def head(url: HttpUrl): HttpRequest[Any]    = apply(HttpMethod.HEAD, url)
    def options(url: HttpUrl): HttpRequest[Any] = apply(HttpMethod.OPTIONS, url)

    def get(rawUrl: String)(using Frame): Result[HttpError, HttpRequest[Any]]     = parse(HttpMethod.GET, rawUrl)
    def post(rawUrl: String)(using Frame): Result[HttpError, HttpRequest[Any]]    = parse(HttpMethod.POST, rawUrl)
    def put(rawUrl: String)(using Frame): Result[HttpError, HttpRequest[Any]]     = parse(HttpMethod.PUT, rawUrl)
    def patch(rawUrl: String)(using Frame): Result[HttpError, HttpRequest[Any]]   = parse(HttpMethod.PATCH, rawUrl)
    def delete(rawUrl: String)(using Frame): Result[HttpError, HttpRequest[Any]]  = parse(HttpMethod.DELETE, rawUrl)
    def head(rawUrl: String)(using Frame): Result[HttpError, HttpRequest[Any]]    = parse(HttpMethod.HEAD, rawUrl)
    def options(rawUrl: String)(using Frame): Result[HttpError, HttpRequest[Any]] = parse(HttpMethod.OPTIONS, rawUrl)

end HttpRequest
