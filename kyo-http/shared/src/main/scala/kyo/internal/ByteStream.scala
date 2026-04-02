package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Pure byte-stream functions that thread (result, remainingStream) tuples.
  *
  * All functions take a stream and return a (result, remainingStream) pair so callers can chain multiple reads on the same stream without
  * any mutable state.
  */
object ByteStream:

    val CRLF: Array[Byte]      = "\r\n".getBytes(StandardCharsets.UTF_8)
    val CRLF_CRLF: Array[Byte] = "\r\n\r\n".getBytes(StandardCharsets.UTF_8)

    /** Read from src until delimiter is found.
      *
      * Returns (bytesBeforeDelimiter, remainingStream). The delimiter bytes are consumed and not included in the result. If the stream ends
      * before the delimiter is found, fails with HttpConnectionClosedException. If more than maxSize bytes are accumulated before the
      * delimiter, fails with HttpProtocolException.
      */
    def readUntil(src: Stream[Span[Byte], Async], delimiter: Array[Byte], maxSize: Int)(using
        Frame
    )
        : (Span[Byte], Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        Loop(src, Span.empty[Byte]) { (stream, buffer) =>
            stream.splitAt(1).map { (chunk, rest) =>
                if chunk.isEmpty then Abort.fail(HttpConnectionClosedException())
                else
                    val span     = chunk(0)
                    val combined = if buffer.isEmpty then span else Span.concat(buffer, span)
                    if combined.size > maxSize then
                        Abort.fail(HttpProtocolException("Data exceeds max size before delimiter"))
                    else
                        indexOf(combined.toArrayUnsafe, delimiter) match
                            case -1 => Loop.continue(rest, combined)
                            case idx =>
                                val before = combined.slice(0, idx)
                                val after  = combined.slice(idx + delimiter.length, combined.size)
                                val remaining =
                                    if after.nonEmpty then Stream.init(Seq(after)).concat(rest)
                                    else rest
                                Loop.done((before, remaining))
                    end if
            }
        }

    /** Read exactly n bytes from src.
      *
      * Returns (nBytes, remainingStream). If the stream ends before n bytes are available, fails with HttpConnectionClosedException.
      * Returns (empty, src) immediately if n <= 0.
      */
    def readExact(src: Stream[Span[Byte], Async], n: Int)(using
        Frame
    )
        : (Span[Byte], Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        if n <= 0 then (Span.empty[Byte], src)
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
            }

    /** Read a single CRLF-terminated line from src.
      *
      * Returns (lineBytes, remainingStream) where lineBytes does not include the CRLF. Delegates to readUntil with the CRLF delimiter.
      */
    def readLine(src: Stream[Span[Byte], Async], maxSize: Int = 8192)(using
        Frame
    )
        : (Span[Byte], Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        readUntil(src, CRLF, maxSize)

    /** Find the first occurrence of needle in haystack. Returns -1 if not found.
      *
      * Simple O(n*m) search — sufficient for typical delimiter sizes (2-4 bytes).
      */
    private[internal] def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
        if needle.length == 0 then 0
        else if haystack.length < needle.length then -1
        else
            var i = 0
            while i <= haystack.length - needle.length do
                var j     = 0
                var found = true
                while found && j < needle.length do
                    if haystack(i + j) != needle(j) then found = false
                    j += 1
                if found then return i
                i += 1
            end while
            -1

end ByteStream
