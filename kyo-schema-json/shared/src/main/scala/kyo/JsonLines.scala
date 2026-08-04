package kyo

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec

/** Line-delimited JSON (JSONL, also called NDJSON): one JSON value per line.
  *
  * The format is the interchange shape for append-only record logs, including AI agent session transcripts and structured application
  * logs. This object holds the pure surface: framing a byte sequence into records.
  *
  * Framing is byte-level and splits on `'\n'`. That is a complete framer rather than an approximation, because JSON requires control
  * characters inside strings to be escaped, so a valid record cannot contain an unescaped newline. Each complete record then goes to the
  * ordinary whole-input JSON reader, which is why no incremental JSON parser is needed.
  *
  * Framing happens on bytes rather than on text because a UTF-8 multibyte character can straddle a chunk boundary. Splitting bytes first
  * and decoding each complete record afterwards removes that hazard without an incremental text decoder.
  *
  * Reachable as `Json.Lines`, which aliases this object.
  *
  * @see
  *   [[JsonLines.Framer]] for the resumable, chunk-oriented framing value
  */
object JsonLines:

    /** Maximum bytes in a single record before framing aborts.
      *
      * A byte stream that contains no newline would otherwise grow the framer's residual without bound. The `maxDepth` and
      * `maxCollectionSize` limits operate inside a single document and do not cover this.
      */
    val DefaultMaxLineBytes: Int = 16 * 1024 * 1024

    private val LimitName: String = "JSONL record length"

    private val Newline: Byte = '\n'.toByte
    private val Return: Byte  = '\r'.toByte
    private val Tab: Byte     = '\t'.toByte
    private val Space: Byte   = ' '.toByte

    private val Bom: Span[Byte] =
        Span(0xef.toByte, 0xbb.toByte, 0xbf.toByte)

    /** One framed record, with its position in the overall input.
      *
      * The bytes are the record's own UTF-8 encoding with the line terminator (`'\n'` and any preceding `'\r'`) already removed. They are
      * carried as bytes rather than text so that framing never has to decode, which is what keeps a record identical no matter how the
      * input was split into chunks.
      *
      * `index` counts only records that were emitted, so blank and whitespace-only lines do not consume an index. `byteOffset` counts
      * every byte of the overall input, including skipped blank lines and a leading byte order mark, so it addresses the record in the
      * original source.
      *
      * @param bytes
      *   the record's UTF-8 bytes, with any line terminator removed
      * @param index
      *   zero-based position among non-blank records
      * @param byteOffset
      *   offset of the record's first byte within the overall input
      */
    final case class Record(bytes: Span[Byte], index: Long, byteOffset: Long):
        /** The record decoded as UTF-8 text. */
        def text: String = new String(bytes.toArray, StandardCharsets.UTF_8)
    end Record

    /** Immutable, resumable framer over arbitrary byte chunks.
      *
      * Holds the only stateful concern in JSONL handling, a record straddling a chunk boundary, as a value. Callers thread it through
      * their own loop rather than the framer owning one, so the same framer serves a whole-input decode and a live stream without
      * duplication. Feeding a framer never mutates it: [[feed]] returns the advanced framer, and the receiver stays usable.
      *
      * A byte order mark is recognized across chunk boundaries. While the bytes seen so far are still a proper prefix of the mark, the
      * framer holds them and stays in its start state rather than guessing, so a one-byte-at-a-time feed strips the mark exactly as a
      * whole-input feed does.
      *
      * @param residual
      *   bytes seen since the last record terminator
      * @param nextIndex
      *   index the next emitted record will carry
      * @param residualOffset
      *   overall-input offset of `residual`'s first byte
      * @param maxLineBytes
      *   record size ceiling
      * @param atStart
      *   true until a byte that settles the byte order mark question is consumed
      */
    final case class Framer private[kyo] (
        residual: Span[Byte],
        nextIndex: Long,
        residualOffset: Long,
        maxLineBytes: Int,
        atStart: Boolean
    ):

        /** Feeds a chunk, returning the advanced framer and every record the chunk completed.
          *
          * @param chunk
          *   the next bytes of the input, of any size including empty
          * @return
          *   the advanced framer paired with the completed records, or a failure if a record or the pending residual exceeds
          *   `maxLineBytes`
          */
        def feed(chunk: Span[Byte])(using Frame): Result[LimitExceededException, (Framer, Chunk[Record])] =
            val combined = if residual.isEmpty then chunk else residual ++ chunk
            if atStart && startsWithBomPrefix(combined) then
                if combined.size < Bom.size then
                    // Still undecided: the bytes so far are a proper prefix of the mark, and a proper
                    // prefix holds no newline, so there is nothing to emit while waiting for the rest.
                    Result.succeed[LimitExceededException, (Framer, Chunk[Record])](
                        (Framer(combined, nextIndex, residualOffset, maxLineBytes, true), Chunk.empty)
                    )
                else
                    scan(combined.slice(Bom.size, combined.size), residualOffset + Bom.size)
            else scan(combined, residualOffset)
            end if
        end feed

        /** Returns the unterminated trailing record, if the input ended without a newline.
          *
          * @return
          *   the final record when the residual holds anything other than whitespace, `Absent` otherwise
          */
        def finish: Maybe[Record] =
            val trimmed = stripReturn(residual)
            if isBlank(trimmed) then Absent
            else Present(Record(trimmed, nextIndex, residualOffset))
        end finish

        private def startsWithBomPrefix(buf: Span[Byte]): Boolean =
            val n  = math.min(buf.size, Bom.size)
            var i  = 0
            var ok = true
            while ok && i < n do
                if buf(i) != Bom(i) then ok = false
                i += 1
            ok
        end startsWithBomPrefix

        private def scan(buf: Span[Byte], offset: Long)(using Frame): Result[LimitExceededException, (Framer, Chunk[Record])] =
            emitFrom(buf, offset, nextIndex, Chunk.empty).flatMap { case (rest, restOffset, index, records) =>
                if rest.size > maxLineBytes then
                    Result.fail[LimitExceededException, (Framer, Chunk[Record])](
                        LimitExceededException(LimitName, rest.size, maxLineBytes)
                    )
                else
                    Result.succeed[LimitExceededException, (Framer, Chunk[Record])](
                        (Framer(rest, index, restOffset, maxLineBytes, false), records)
                    )
            }
        end scan

        @tailrec
        private def emitFrom(
            buf: Span[Byte],
            offset: Long,
            index: Long,
            acc: Chunk[Record]
        )(using Frame): Result[LimitExceededException, (Span[Byte], Long, Long, Chunk[Record])] =
            indexOfNewline(buf) match
                case -1 => Result.succeed((buf, offset, index, acc))
                case nl =>
                    val line = stripReturn(buf.slice(0, nl))
                    if line.size > maxLineBytes then
                        Result.fail(LimitExceededException(LimitName, line.size, maxLineBytes))
                    else
                        val rest       = buf.slice(nl + 1, buf.size)
                        val restOffset = offset + nl + 1
                        if isBlank(line) then emitFrom(rest, restOffset, index, acc)
                        else emitFrom(rest, restOffset, index + 1, acc :+ Record(line, index, offset))
                    end if
            end match
        end emitFrom
    end Framer

    object Framer:
        /** Creates a framer positioned at the start of an input.
          *
          * @param maxLineBytes
          *   the largest record the framer will emit, in bytes
          * @return
          *   a framer holding no residual, at record index zero and byte offset zero
          */
        def init(maxLineBytes: Int = DefaultMaxLineBytes): Framer =
            Framer(Span.empty[Byte], 0L, 0L, maxLineBytes, true)
    end Framer

    private def indexOfNewline(buf: Span[Byte]): Int =
        var i     = 0
        var found = -1
        while found < 0 && i < buf.size do
            if buf(i) == Newline then found = i
            i += 1
        found
    end indexOfNewline

    private def stripReturn(line: Span[Byte]): Span[Byte] =
        if line.nonEmpty && line(line.size - 1) == Return then line.slice(0, line.size - 1)
        else line

    private def isBlank(line: Span[Byte]): Boolean =
        var i     = 0
        var blank = true
        while blank && i < line.size do
            val b = line(i)
            if b != Space && b != Tab && b != Return then blank = false
            i += 1
        end while
        blank
    end isBlank

end JsonLines
