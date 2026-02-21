package kyo.http2

import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.Record

case class HttpResponse[Fields](
    status: HttpStatus,
    headers: Map[String, String],
    fields: Record[Fields]
):
    def &[A](field: Record[A]): HttpResponse[Fields & A] =
        copy(fields = fields & field)

    def header(name: String): Maybe[String] =
        val lower = name.toLowerCase
        headers.collectFirst { case (k, v) if k.toLowerCase == lower => v } match
            case Some(v) => Present(v)
            case None    => Absent

    def addHeader(name: String, value: String): HttpResponse[Fields] =
        copy(headers = headers + (name -> value))

    def setHeader(name: String, value: String): HttpResponse[Fields] =
        val lower = name.toLowerCase
        copy(headers = headers.filterNot(_._1.toLowerCase == lower) + (name -> value))
end HttpResponse

object HttpResponse:
    def apply(status: HttpStatus): HttpResponse[Any] =
        HttpResponse(status, Map.empty, Record.empty)
end HttpResponse
