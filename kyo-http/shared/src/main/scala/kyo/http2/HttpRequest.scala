package kyo.http2

import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.Record

case class HttpRequest[Fields](
    method: HttpMethod,
    path: String,
    headers: Map[String, String],
    fields: Record[Fields]
):
    def &[A](field: Record[A]): HttpRequest[Fields & A] =
        copy(fields = fields & field)

    def header(name: String): Maybe[String] =
        val lower = name.toLowerCase
        headers.collectFirst { case (k, v) if k.toLowerCase == lower => v } match
            case Some(v) => Present(v)
            case None    => Absent

    def addHeader(name: String, value: String): HttpRequest[Fields] =
        copy(headers = headers + (name -> value))

    def setHeader(name: String, value: String): HttpRequest[Fields] =
        val lower = name.toLowerCase
        copy(headers = headers.filterNot(_._1.toLowerCase == lower) + (name -> value))
end HttpRequest

object HttpRequest:
    def apply(method: HttpMethod, path: String): HttpRequest[Any] =
        HttpRequest(method, path, Map.empty, Record.empty)
end HttpRequest
