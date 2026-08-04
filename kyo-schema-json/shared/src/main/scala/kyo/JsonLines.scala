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
      *
      * The limit counts the record's own bytes, the ones a decoder will see, and never the line terminator: a `'\n'` and any `'\r'`
      * immediately before it are excluded. A `\r\n`-terminated line therefore holds one more byte on the wire than the limit allows for
      * its record. A pending residual is measured the same way, so a record is accepted or rejected identically whether its terminator
      * arrived in the same chunk or a later one.
      */
    val DefaultMaxLineBytes: Int = 16 * 1024 * 1024

    private val LimitName: String = "JSONL record length"

    private val Newline: Byte = '\n'.toByte
    private val Return: Byte  = '\r'.toByte
    private val Tab: Byte     = '\t'.toByte
    private val Space: Byte   = ' '.toByte

    private val Bom: Span[Byte] =
        Span(0xef.toByte, 0xbb.toByte, 0xbf.toByte)

    private val NewlineSpan: Span[Byte] = Span(Newline)

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
      * Do not compare two records with `==`. `Span` is an opaque array with no structural equality, so the derived `equals` compares
      * `bytes` by reference and two records holding identical content never match. Compare [[text]] or `bytes.is(other.bytes)` instead.
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

    /** The outcome of feeding one chunk to a [[Framer]].
      *
      * `records` always holds every record framed from the fed chunk before any breach, whether or not `error` is present: a limit
      * breach never discards records that were already complete. See [[Framer.feed]] for what `error` and `framer` mean together.
      *
      * @param framer
      *   the advanced framer, safe to use again (including calling `feed` or `finish` on it) only when `error` is `Absent`. When
      *   `error` is `Present`, this still holds the pre-chunk residual, so `finish` on it would emit a bogus partial record.
      * @param records
      *   every record framed from this chunk, in order, up to the breach if there was one
      * @param error
      *   `Present` when no record boundary could be found for the current residual
      */
    final case class Framed(framer: Framer, records: Chunk[Record], error: Maybe[LimitExceededException])

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

        /** Feeds a chunk, returning every record it completed along with the advanced framer.
          *
          * @param chunk
          *   the next bytes of the input, of any size including empty
          * @return
          *   a [[Framed]] holding every record this chunk completed and, if a record or the pending residual exceeds `maxLineBytes`,
          *   the breach as `error`. `records` is populated even when `error` is present: a breach ends framing but never discards a
          *   record that was already complete. When `error` is `Present`, no record boundary could be found for the offending bytes,
          *   so the returned `framer` must not be used again for anything, including feeding it further or calling `finish` on it: it
          *   still holds the pre-chunk residual, so `finish` on it would emit a bogus partial record. When `error` is `Absent`,
          *   `framer` is safe to feed with the next chunk or to call `finish` on.
          */
        def feed(chunk: Span[Byte])(using Frame): Framed =
            val combined = if residual.isEmpty then chunk else residual ++ chunk
            if atStart && startsWithBomPrefix(combined) then
                if combined.size < Bom.size then
                    // Still undecided: the bytes so far are a proper prefix of the mark, and a proper
                    // prefix holds no newline, so there is nothing to emit while waiting for the rest.
                    Framed(Framer(combined, nextIndex, residualOffset, maxLineBytes, true), Chunk.empty, Absent)
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
            if isBlank(trimmed, 0, trimmed.size) then Absent
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

        private def scan(buf: Span[Byte], offset: Long)(using Frame): Framed =
            val (start, restOffset, index, records, breach) = emitFrom(buf, 0, offset, nextIndex, Chunk.empty)
            breach match
                case Present(e) =>
                    // A record inside the buffer exceeded the limit. The records collected before it stay;
                    // the framer itself is not advanced, since the caller must not feed it again.
                    Framed(this, records, Present(e))
                case Absent =>
                    // The residual is measured by the same rule a completed record is: a trailing '\r' is the
                    // first half of a terminator that has not arrived yet, so it does not count toward the limit.
                    val restSize = buf.size - start
                    val restLine =
                        if restSize > 0 && buf(buf.size - 1) == Return then restSize - 1
                        else restSize
                    if restLine > maxLineBytes then
                        Framed(this, records, Present(LimitExceededException(LimitName, restLine, maxLineBytes)))
                    else
                        val rest = if start == 0 then buf else buf.slice(start, buf.size)
                        Framed(Framer(rest, index, restOffset, maxLineBytes, false), records, Absent)
                    end if
            end match
        end scan

        /** Walks `buf` from `start`, emitting one record per newline.
          *
          * Carries `start` as an index rather than re-slicing the tail after each record: `Span.slice` copies, so slicing per record
          * would make framing a chunk of k records cost O(chunk size * k). Only an emitted record copies, which keeps the total cost
          * linear in the chunk with allocation proportional to the output.
          *
          * `offset` is the overall-input offset of `buf(start)`, and the returned `Int` is the index where the unterminated residual
          * begins. A record exceeding `maxLineBytes` stops the walk immediately rather than failing the whole scan, so every record
          * gathered in `acc` up to that point is returned alongside the breach.
          */
        @tailrec
        private def emitFrom(
            buf: Span[Byte],
            start: Int,
            offset: Long,
            index: Long,
            acc: Chunk[Record]
        )(using Frame): (Int, Long, Long, Chunk[Record], Maybe[LimitExceededException]) =
            indexOfNewline(buf, start) match
                case -1 => (start, offset, index, acc, Absent)
                case nl =>
                    val lineEnd  = if nl > start && buf(nl - 1) == Return then nl - 1 else nl
                    val lineSize = lineEnd - start
                    if lineSize > maxLineBytes then
                        (start, offset, index, acc, Present(LimitExceededException(LimitName, lineSize, maxLineBytes)))
                    else
                        val restOffset = offset + (nl + 1 - start)
                        if isBlank(buf, start, lineEnd) then emitFrom(buf, nl + 1, restOffset, index, acc)
                        else
                            emitFrom(
                                buf,
                                nl + 1,
                                restOffset,
                                index + 1,
                                acc :+ Record(buf.slice(start, lineEnd), index, offset)
                            )
                        end if
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

    private def indexOfNewline(buf: Span[Byte], from: Int): Int =
        var i     = from
        var found = -1
        while found < 0 && i < buf.size do
            if buf(i) == Newline then found = i
            i += 1
        end while
        found
    end indexOfNewline

    private def stripReturn(line: Span[Byte]): Span[Byte] =
        if line.nonEmpty && line(line.size - 1) == Return then line.slice(0, line.size - 1)
        else line

    private def isBlank(buf: Span[Byte], from: Int, until: Int): Boolean =
        var i     = from
        var blank = true
        while blank && i < until do
            val b = buf(i)
            if b != Space && b != Tab && b != Return then blank = false
            i += 1
        end while
        blank
    end isBlank

    /** Folds a chunk of per-record results into a single result, short-circuiting on the first
      * failure or panic.
      *
      * `decodeAllBytes` reduces `decodeAllResults` through this. It is not a general-purpose
      * combinator, so it stays private to this file rather than joining `Result` itself.
      */
    private def foldResults[E, A](results: Chunk[Result[E, A]]): Result[E, Chunk[A]] =
        @tailrec
        def loop(i: Int, acc: Chunk[A]): Result[E, Chunk[A]] =
            if i >= results.size then Result.succeed(acc)
            else
                results(i) match
                    case Result.Success(a) => loop(i + 1, acc :+ a)
                    case Result.Failure(e) => Result.fail(e)
                    case Result.Panic(ex)  => Result.panic(ex)
        loop(0, Chunk.empty[A])
    end foldResults

    /** Decodes one framed record.
      *
      * Routes through `Codec.decodeFully`, so a record holding trailing content after a complete value is rejected rather than silently
      * truncated. A failure is wrapped with the record's position so the caller can locate it in the original input.
      *
      * @param record
      *   the framed record to decode
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @return
      *   the decoded value, or a `RecordDecodeException` carrying the record's position and text
      */
    def decodeRecord[A](
        record: Record,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize
    )(using json: Json, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        json.decodeFully[A](record.bytes, maxDepth, maxCollectionSize).mapFailure { cause =>
            RecordDecodeException(record.index, record.byteOffset, record.text, cause)
        }
    end decodeRecord

    /** Decodes every record in a JSONL string, failing on the first undecodable one.
      *
      * @param input
      *   the JSONL text to decode
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineBytes
      *   the largest record the framer will accept, in bytes (default `DefaultMaxLineBytes`)
      * @return
      *   every decoded value in order, or the first decode or framing failure encountered
      */
    def decodeAll[A](
        input: String,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = DefaultMaxLineBytes
    )(using Json, Schema[A], Frame): Result[DecodeException, Chunk[A]] =
        decodeAllBytes[A](
            Span.from(input.getBytes(StandardCharsets.UTF_8)),
            maxDepth,
            maxCollectionSize,
            maxLineBytes
        )
    end decodeAll

    /** Decodes every record in raw JSONL bytes, failing on the first undecodable one.
      *
      * @param input
      *   the raw UTF-8 JSONL bytes to decode
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineBytes
      *   the largest record the framer will accept, in bytes (default `DefaultMaxLineBytes`)
      * @return
      *   every decoded value in order, or the first decode or framing failure encountered
      */
    def decodeAllBytes[A](
        input: Span[Byte],
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = DefaultMaxLineBytes
    )(using Json, Schema[A], Frame): Result[DecodeException, Chunk[A]] =
        foldResults(decodeAllResults[A](input, maxDepth, maxCollectionSize, maxLineBytes))
    end decodeAllBytes

    /** Decodes every record, returning one `Result` per record instead of failing the whole input.
      *
      * The variant for heterogeneous or partially-written logs: a truncated tail or an unrecognized record type yields one failure rather
      * than discarding the whole file.
      *
      * The one failure this cannot recover from is a record exceeding `maxLineBytes`: no record boundary was found, so there is nothing
      * to skip to. That surfaces as a final failure element and ends the chunk, but every record decoded before the breach is kept: a
      * limit breach on record 5 of a 4-record-so-far log yields 4 successes followed by that one failure, not an empty chunk.
      *
      * @param input
      *   the raw UTF-8 JSONL bytes to decode
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineBytes
      *   the largest record the framer will accept, in bytes (default `DefaultMaxLineBytes`)
      * @return
      *   one result per record; a framing failure that finds no record boundary is appended as a final failure element after every
      *   record decoded before it
      */
    def decodeAllResults[A](
        input: Span[Byte],
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = DefaultMaxLineBytes
    )(using Json, Schema[A], Frame): Chunk[Result[DecodeException, A]] =
        val framed  = Framer.init(maxLineBytes).feed(input)
        val decoded = framed.records.map(r => decodeRecord[A](r, maxDepth, maxCollectionSize))
        framed.error match
            case Present(e) => decoded :+ Result.fail(e)
            case Absent =>
                framed.framer.finish match
                    case Absent       => decoded
                    case Present(rec) => decoded :+ decodeRecord[A](rec, maxDepth, maxCollectionSize)
        end match
    end decodeAllResults

    /** Encodes every value as one JSONL record per line, each terminated by a newline.
      *
      * Delegates to `Json.encode`. `Json.encode` takes `Json` as an explicit parameter rather than summoning it internally
      * (`Json.scala`), so a `Json` given in this method's own caller's scope flows through to every `Json.encode` call here the same
      * way it would for a `Json.encode` call written directly at that call site.
      *
      * @param values
      *   the values to encode, in order
      * @return
      *   the JSONL text, or the empty string for an empty input
      */
    def encodeAll[A](values: Seq[A])(using Json, Schema[A], Frame): String =
        val sb = new StringBuilder
        values.foreach(v => discard(sb.append(Json.encode(v)).append('\n')))
        sb.toString
    end encodeAll

    /** Encodes every value as one JSONL record per line, returning raw UTF-8 bytes.
      *
      * Encodes each value once with `Json.encodeBytes`, then concatenates every encoded value and its trailing newline with
      * `Span.concat`, which sizes the output array once and copies each piece into it exactly once. Building the result by
      * repeatedly concatenating onto a growing accumulator (`acc ++ Json.encodeBytes(v) ++ ...`) would instead copy the whole
      * accumulator again for every value, which is quadratic in the number of values; this stays linear in the total output size.
      *
      * @param values
      *   the values to encode, in order
      * @return
      *   the JSONL bytes, or an empty span for an empty input
      */
    def encodeAllBytes[A](values: Seq[A])(using Json, Schema[A], Frame): Span[Byte] =
        val pieces = values.flatMap(v => Seq(Json.encodeBytes(v), NewlineSpan))
        Span.concat(pieces*)
    end encodeAllBytes

    /** Encodes a single value as one JSONL record, terminated by a newline.
      *
      * @param value
      *   the value to encode
      * @return
      *   the value's compact JSON encoding followed by a newline
      */
    def encodeLine[A](value: A)(using Json, Schema[A], Frame): String =
        Json.encode(value) + "\n"
    end encodeLine

end JsonLines
