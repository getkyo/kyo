package kyo.internal

import kyo.*

/** Body representation for HTTP request/response payloads.
  *
  * Used by Protocol implementations to return parsed bodies from readRequest/readResponse. Immutable. Streaming variant wraps a kyo Stream.
  */
enum HttpBody derives CanEqual:
    case Empty
    case Buffered(data: Span[Byte])
    case Streamed(chunks: Stream[Span[Byte], Async])
end HttpBody
