package kyo.http2

import kyo.Maybe
import kyo.Span

case class HttpPart(
    name: String,
    filename: Maybe[String],
    contentType: Maybe[String],
    data: Span[Byte]
) derives CanEqual:
    require(name.nonEmpty, "Part name cannot be empty")
end HttpPart
