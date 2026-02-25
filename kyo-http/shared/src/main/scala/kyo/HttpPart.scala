package kyo

import kyo.*

case class HttpPart(
    name: String,
    filename: Maybe[String],
    contentType: Maybe[String],
    data: Span[Byte]
) derives CanEqual
