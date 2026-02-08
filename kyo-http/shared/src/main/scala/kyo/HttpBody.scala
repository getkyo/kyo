package kyo

import java.nio.charset.StandardCharsets

/** Sealed body type for HTTP responses.
  *
  * HttpBody is either `Bytes` (fully buffered in memory) or `Streamed` (lazy byte stream). This sealed hierarchy enables compile-time
  * safety: body accessors like `bodyText` and `bodyAs` are only available on `HttpResponse[HttpBody.Bytes]` via extension methods.
  */
sealed abstract class HttpBody:
    def use[A](ifBytes: HttpBody.Bytes => A, ifStreamed: HttpBody.Streamed => A): A =
        this match
            case b: HttpBody.Bytes    => ifBytes(b)
            case s: HttpBody.Streamed => ifStreamed(s)
end HttpBody

object HttpBody:

    val empty: Bytes = new Bytes(Array.empty)

    def apply(bytes: Array[Byte]): Bytes = new Bytes(bytes)

    def apply(text: String): Bytes = new Bytes(text.getBytes(StandardCharsets.UTF_8))

    def stream(s: Stream[Span[Byte], Async]): Streamed = new Streamed(s)
    final class Bytes private[kyo] (private val _data: Array[Byte]) extends HttpBody:
        def data: Array[Byte] = _data
        def text: String =
            if _data.isEmpty then "" else new String(_data, StandardCharsets.UTF_8)
        def span: Span[Byte] = Span.fromUnsafe(_data)
        def as[A: Schema](using Frame): A < Abort[HttpError] =
            val t = text
            try Schema[A].decode(t)
            catch case e: Throwable => Abort.fail(HttpError.ParseError(s"Failed to parse response body", e))
        end as
        def isEmpty: Boolean = _data.isEmpty
    end Bytes

    final class Streamed private[kyo] (private val _stream: Stream[Span[Byte], Async]) extends HttpBody:
        def stream: Stream[Span[Byte], Async] = _stream
        def isEmpty: Boolean                  = false

end HttpBody
