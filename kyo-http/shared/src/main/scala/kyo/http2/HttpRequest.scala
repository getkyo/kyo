package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Absent
import kyo.Frame
import kyo.Maybe
import kyo.Present
import kyo.Record
import kyo.Tag
import kyo.Record.~

case class HttpRequest[Fields](
    method: HttpMethod,
    url: HttpUrl,
    headers: HttpHeaders,
    cookies: Seq[(String, HttpCookie.Request[String])],
    pathParams: Map[String, String],
    fields: Record[Fields]
):
    def path: String = url.path

    def addField[N <: String & Singleton, V](name: N, value: V)(using Tag[V]): HttpRequest[Fields & name.type ~ V] =
        copy(fields = fields & name ~ value)

    def addFields[Fields2](r: Record[Fields2]): HttpRequest[Fields & Fields2] =
        copy(fields = fields & r)

    def query(name: String): Maybe[String] =
        url.query(name)

    def queryAll(name: String): Seq[String] =
        url.queryAll(name)

    def cookie(name: String): Maybe[HttpCookie.Request[String]] =
        cookies.collectFirst { case (n, c) if n == name => c } match
            case Some(c) => Present(c)
            case None    => Absent

    def addHeader(name: String, value: String): HttpRequest[Fields] =
        copy(headers = headers.add(name, value))

    def setHeader(name: String, value: String): HttpRequest[Fields] =
        copy(headers = headers.set(name, value))

    def withPathParams(params: Map[String, String]): HttpRequest[Fields] =
        copy(pathParams = params)

end HttpRequest

object HttpRequest:

    def apply(method: HttpMethod, url: HttpUrl): HttpRequest[Any] =
        HttpRequest(method, url, HttpHeaders.empty, Seq.empty, Map.empty, Record.empty)

    /** Parse URL and create request. Fails with Abort[String] on malformed URL. */
    def parse(method: HttpMethod, rawUrl: String)(using Frame): HttpRequest[Any] < Abort[String] =
        Abort.get(HttpUrl.parse(rawUrl).map(url => apply(method, url)))

    def get(url: HttpUrl): HttpRequest[Any]     = apply(HttpMethod.GET, url)
    def post(url: HttpUrl): HttpRequest[Any]    = apply(HttpMethod.POST, url)
    def put(url: HttpUrl): HttpRequest[Any]     = apply(HttpMethod.PUT, url)
    def patch(url: HttpUrl): HttpRequest[Any]   = apply(HttpMethod.PATCH, url)
    def delete(url: HttpUrl): HttpRequest[Any]  = apply(HttpMethod.DELETE, url)
    def head(url: HttpUrl): HttpRequest[Any]    = apply(HttpMethod.HEAD, url)
    def options(url: HttpUrl): HttpRequest[Any] = apply(HttpMethod.OPTIONS, url)

end HttpRequest
