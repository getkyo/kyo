package kyo

import java.nio.charset.StandardCharsets

/** Sealed body type representing either a fully-buffered byte array or a lazy byte stream.
  *
  * The two-variant hierarchy enables compile-time safety: `HttpRequest` and `HttpResponse` track the body type via their `B` type
  * parameter, and body accessors like `bodyText` and `bodyAs` are gated by `<:<` evidence so they're only available when the body is
  * `HttpBody.Bytes`. The `use` method provides exhaustive access via fold without requiring the caller to know the concrete type.
  *
  * @see
  *   [[kyo.HttpBody.Bytes]]
  * @see
  *   [[kyo.HttpBody.Streamed]]
  * @see
  *   [[kyo.HttpRequest]]
  * @see
  *   [[kyo.HttpResponse]]
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

    /** Fully-buffered HTTP body backed by a byte array. */
    final class Bytes private[kyo] (private val _data: Array[Byte]) extends HttpBody:
        def data: Array[Byte] = _data
        def text: String =
            if _data.isEmpty then "" else new String(_data, StandardCharsets.UTF_8)
        def span: Span[Byte] = Span.fromUnsafe(_data)
        def as[A: Schema](using Frame): A < Abort[HttpError] =
            val t = text
            Abort.get(Schema[A].decode(t).mapFailure(HttpError.ParseError(_)))
        end as
        def isEmpty: Boolean = _data.isEmpty
    end Bytes

    /** Streaming HTTP body backed by a `Stream[Span[Byte], Async]`. */
    final class Streamed private[kyo] (private val _stream: Stream[Span[Byte], Async]) extends HttpBody:
        def stream: Stream[Span[Byte], Async] = _stream
        def isEmpty: Boolean                  = false

end HttpBody
