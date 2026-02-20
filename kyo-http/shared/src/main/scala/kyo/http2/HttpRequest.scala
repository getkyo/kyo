package kyo.http2

import kyo.Record

case class HttpRequest[In](
    fields: HttpRoute.RequestDef[In],
    values: Record[In]
)
