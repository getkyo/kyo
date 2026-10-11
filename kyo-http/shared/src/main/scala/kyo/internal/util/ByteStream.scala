package kyo.internal.util

import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

/** Pure byte-stream functions that thread (result, remainingStream) tuples.
  *
  * All functions take a stream and return their result via a continuation `f` so callers can chain multiple reads on the same stream
  * without any mutable state.
  */
private[kyo] object ByteStream:

    val CRLF: Array[Byte]      = "\r\n".getBytes(StandardCharsets.US_ASCII)
    val CRLF_CRLF: Array[Byte] = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII)

    /** Read from src until delimiter is found.
      *
      * Passes (bytesBeforeDelimiter, remainingStream) to f. The delimiter bytes are consumed and not included in the result. If the stream
      * ends before the delimiter is found, fails with HttpConnectionClosedException in the phase `endedIn`, which the caller names because
      * it knows whether the bytes owed were a message head or a body. If more than maxSize bytes precede the delimiter, fails with
      * HttpProtocolException, as soon as the bytes read prove it, whatever the read boundaries.
      *
      * Each read is scanned once and kept as read: what carries over between reads is the length of the delimiter prefix the bytes so far
      * end with, and the reads are joined once, when the delimiter is found. An empty delimiter is found before the first byte, and
      * nothing is read.
      */
    inline def readUntilWith[A, S](
        endedIn: HttpConnectionClosedException.Phase,
        src: Stream[Span[Byte], Async],
        delimiter: Array[Byte],
        maxSize: Int
    )(
        inline f: (Span[Byte], Stream[Span[Byte], Async]) => A < S
    )(using inline frame: Frame): A < (S & Async & Abort[HttpException]) =
        if delimiter.isEmpty then f(Span.empty[Byte], src)
        else readNonEmptyUntilWith(endedIn, src, delimiter, maxSize)(f)

    private inline def readNonEmptyUntilWith[A, S](
        endedIn: HttpConnectionClosedException.Phase,
        src: Stream[Span[Byte], Async],
        delimiter: Array[Byte],
        maxSize: Int
    )(
        inline f: (Span[Byte], Stream[Span[Byte], Async]) => A < S
    )(using inline frame: Frame): A < (S & Async & Abort[HttpException]) =
        val fallback = delimiterFallback(delimiter)
        Loop(src, Chunk.empty[Span[Byte]], 0, 0) { (stream, reads, size, matched) =>
            stream.splitAt(1).map { (chunk, rest) =>
                if chunk.isEmpty then Abort.fail(HttpConnectionClosedException(endedIn))
                else
                    val span    = chunk(0)
                    val scanned = advanceMatch(delimiter, fallback, matched, span)
                    if scanned < 0 then
                        val end    = -scanned
                        val before = size + end - delimiter.length
                        if before > maxSize then Abort.fail(HttpProtocolException("Data exceeds max size before delimiter"))
                        else
                            val head =
                                if reads.isEmpty then span.slice(0, before)
                                else Span.concat(reads.append(span.slice(0, end)).toSeq*).slice(0, before)
                            val after     = span.slice(end, span.size)
                            val remaining = if after.nonEmpty then Stream.init(Seq(after)).concat(rest) else rest
                            Loop.done((head, remaining))
                        end if
                    else if size + span.size - scanned > maxSize then
                        Abort.fail(HttpProtocolException("Data exceeds max size before delimiter"))
                    else Loop.continue(rest, reads.append(span), size + span.size, scanned)
                    end if
            }
        }.map { case (a, b) => f(a, b) }
    end readNonEmptyUntilWith

    /** Read exactly n bytes from src.
      *
      * Passes (nBytes, remainingStream) to f. If the stream ends before n bytes are available, fails with HttpConnectionClosedException in
      * the phase `endedIn`. Calls f immediately with (empty, src) if n <= 0.
      */
    inline def readExactWith[A, S](endedIn: HttpConnectionClosedException.Phase, src: Stream[Span[Byte], Async], n: Int)(
        inline f: (Span[Byte], Stream[Span[Byte], Async]) => A < S
    )(using inline frame: Frame): A < (S & Async & Abort[HttpException]) =
        if n <= 0 then f(Span.empty[Byte], src)
        else
            Loop(src, Chunk.empty[Span[Byte]], 0) { (stream, chunks, count) =>
                stream.splitAt(1).map { (chunk, rest) =>
                    if chunk.isEmpty then Abort.fail(HttpConnectionClosedException(endedIn))
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
    inline def readLineWith[A, S](endedIn: HttpConnectionClosedException.Phase, src: Stream[Span[Byte], Async], maxSize: Int = 8192)(
        inline f: (Span[Byte], Stream[Span[Byte], Async]) => A < S
    )(using inline frame: Frame): A < (S & Async & Abort[HttpException]) =
        readUntilWith(endedIn, src, CRLF, maxSize)(f)

    /** The Knuth-Morris-Pratt fallback of a non-empty delimiter: entry `i` is the length of the longest proper prefix of the delimiter's
      * first `i + 1` bytes that is also their suffix.
      */
    private[internal] def delimiterFallback(delimiter: Array[Byte]): Array[Int] =
        val table                                 = new Array[Int](delimiter.length)
        @tailrec def widest(k: Int, b: Byte): Int =
            if delimiter(k) == b then k + 1
            else if k == 0 then 0
            else widest(table(k - 1), b)
        @tailrec def fill(i: Int): Unit =
            if i < delimiter.length then
                table(i) = widest(table(i - 1), delimiter(i))
                fill(i + 1)
        fill(1)
        table
    end delimiterFallback

    /** Scans `span` for the delimiter, given that the bytes before it end with the delimiter's first `matched` bytes.
      *
      * Returns the length of the delimiter prefix the bytes end with after `span` when the delimiter does not complete in it, and otherwise
      * the negated index in `span` just past the delimiter's last byte, which is at least 1.
      */
    private[internal] def advanceMatch(delimiter: Array[Byte], fallback: Array[Int], matched: Int, span: Span[Byte]): Int =
        @tailrec def step(k: Int, b: Byte): Int =
            if delimiter(k) == b then k + 1
            else if k == 0 then 0
            else step(fallback(k - 1), b)
        @tailrec def loop(i: Int, k: Int): Int =
            if k == delimiter.length then -i
            else if i == span.size then k
            else loop(i + 1, step(k, span(i)))
        loop(0, matched)
    end advanceMatch

end ByteStream
