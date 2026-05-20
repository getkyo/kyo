package kyo.internal.util

import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

/** Pure byte-stream functions that thread (result, remainingStream) tuples.
  *
  * All functions take a stream and return their result via a continuation `f` so callers can chain multiple reads on the same stream
  * without any mutable state.
  */
object ByteStream:

    val CRLF: Array[Byte]      = "\r\n".getBytes(StandardCharsets.US_ASCII)
    val CRLF_CRLF: Array[Byte] = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII)

    /** Read from src until delimiter is found.
      *
      * Passes (bytesBeforeDelimiter, remainingStream) to f. The delimiter bytes are consumed and not included in the result. If the stream
      * ends before the delimiter is found, fails with HttpConnectionClosedException. If more than maxSize bytes are accumulated before the
      * delimiter, fails with HttpProtocolException.
      */
    inline def readUntilWith[A, S](src: Stream[Span[Byte], Async], delimiter: Array[Byte], maxSize: Int)(
        inline f: (Span[Byte], Stream[Span[Byte], Async]) => A < S
    )(using inline frame: Frame): A < (S & Async & Abort[HttpException]) =
        Loop(src, Span.empty[Byte]) { (stream, buffer) =>
            stream.splitAt(1).map { (chunk, rest) =>
                if chunk.isEmpty then Abort.fail(HttpConnectionClosedException())
                else
                    val span     = chunk(0)
                    val combined = if buffer.isEmpty then span else Span.concat(buffer, span)
                    indexOf(combined.toArrayUnsafe, delimiter) match
                        case -1 =>
                            if combined.size > maxSize then
                                Abort.fail(HttpProtocolException("Data exceeds max size before delimiter"))
                            else
                                Loop.continue(rest, combined)
                        case idx =>
                            if idx > maxSize then
                                Abort.fail(HttpProtocolException("Data exceeds max size before delimiter"))
                            else
                                val before = combined.slice(0, idx)
                                val after  = combined.slice(idx + delimiter.length, combined.size)
                                val remaining =
                                    if after.nonEmpty then Stream.init(Seq(after)).concat(rest)
                                    else rest
                                Loop.done((before, remaining))
                    end match
            }
        }.map { case (a, b) => f(a, b) }

    /** Read exactly n bytes from src.
      *
      * Passes (nBytes, remainingStream) to f. If the stream ends before n bytes are available, fails with HttpConnectionClosedException.
      * Calls f immediately with (empty, src) if n <= 0.
      */
    inline def readExactWith[A, S](src: Stream[Span[Byte], Async], n: Int)(
        inline f: (Span[Byte], Stream[Span[Byte], Async]) => A < S
    )(using inline frame: Frame): A < (S & Async & Abort[HttpException]) =
        if n <= 0 then f(Span.empty[Byte], src)
        else
            Loop(src, Chunk.empty[Span[Byte]], 0) { (stream, chunks, count) =>
                stream.splitAt(1).map { (chunk, rest) =>
                    if chunk.isEmpty then Abort.fail(HttpConnectionClosedException())
                    else
                        val span     = chunk(0)
                        val take     = math.min(span.size, n - count)
                        val piece    = span.slice(0, take)
                        val newCount = count + take
                        if newCount >= n then
                            val after =
                                if take < span.size then span.slice(take, span.size)
                                else Span.empty[Byte]
                            val remaining =
                                if after.nonEmpty then Stream.init(Seq(after)).concat(rest)
                                else rest
                            Loop.done((Span.concat(chunks.append(piece).toSeq*), remaining))
                        else
                            Loop.continue(rest, chunks.append(piece), newCount)
                        end if
                }
            }.map { case (a, b) => f(a, b) }

    /** Read a single CRLF-terminated line from src.
      *
      * Passes (lineBytes, remainingStream) to f where lineBytes does not include the CRLF. Delegates to readUntilWith with the CRLF
      * delimiter.
      */
    inline def readLineWith[A, S](src: Stream[Span[Byte], Async], maxSize: Int = 8192)(
        inline f: (Span[Byte], Stream[Span[Byte], Async]) => A < S
    )(using inline frame: Frame): A < (S & Async & Abort[HttpException]) =
        readUntilWith(src, CRLF, maxSize)(f)

    /** Find the first occurrence of needle in haystack. Returns -1 if not found.
      *
      * Simple O(n*m) search — sufficient for typical delimiter sizes (2-4 bytes).
      */
    private[internal] def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
        if needle.length == 0 then 0
        else if haystack.length < needle.length then -1
        else
            @tailrec def matchAt(i: Int, j: Int): Boolean =
                if j >= needle.length then true
                else if haystack(i + j) != needle(j) then false
                else matchAt(i, j + 1)
            @tailrec def search(i: Int): Int =
                if i > haystack.length - needle.length then -1
                else if matchAt(i, 0) then i
                else search(i + 1)
            search(0)

end ByteStream
