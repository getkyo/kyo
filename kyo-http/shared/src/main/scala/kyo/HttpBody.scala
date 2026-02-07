package kyo

import java.nio.charset.StandardCharsets

/** Sealed body type for HTTP responses.
  *
  * HttpBody is either `Bytes` (fully buffered in memory) or `Streamed` (lazy byte stream). This sealed hierarchy enables compile-time
  * safety: body accessors like `bodyText` and `bodyAs` are only available on `HttpResponse[HttpBody.Bytes]` via extension methods.
  */
sealed abstract class HttpBody

object HttpBody:

    val empty: Bytes = new Bytes(Array.empty)

    def apply(bytes: Array[Byte]): Bytes = new Bytes(bytes)

    def apply(text: String): Bytes = new Bytes(text.getBytes(StandardCharsets.UTF_8))

    def stream(s: Stream[Span[Byte], Async]): Streamed = new Streamed(s)

    // TODO can we use Span for data? Let's try to isolate Array usage as much as possible. Review it for the entire kyo-http module!
    // TODO let's not expose `val`s in APIs (see `data`). It complicates binary compatibility
    final class Bytes private[kyo] (val data: Array[Byte]) extends HttpBody:
        def text: String =
            if data.isEmpty then "" else new String(data, StandardCharsets.UTF_8)
        def span: Span[Byte] = Span.fromUnsafe(data)
        def as[A: Schema](using Frame): A < Abort[HttpError] =
            val t = text
            // TODO how about we return a `Result` in decode?
            try Schema[A].decode(t)
            catch case e: Throwable => Abort.fail(HttpError.ParseError(s"Failed to parse response body", e))
        end as
        def isEmpty: Boolean = data.isEmpty
    end Bytes

    // TODO let's not expose `val`s in APIs (stream). It complicates binary compatibility
    final class Streamed private[kyo] (val stream: Stream[Span[Byte], Async]) extends HttpBody:
        def isEmpty: Boolean = false

end HttpBody
