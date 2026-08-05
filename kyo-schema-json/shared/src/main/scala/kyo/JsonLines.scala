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
      * immediately before it are excluded. A maximum-size record therefore occupies one more byte on the wire than the limit when it ends
      * in `'\n'`, and two more when it ends in `"\r\n"`. A pending residual is measured the same way, so a record is accepted or rejected
      * identically whether its terminator arrived in the same chunk or a later one.
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
      * `index` is the line's position among the non-blank lines, so blank and whitespace-only lines do not consume an index while a line
      * skipped for exceeding `maxLineBytes` does, exactly as an undecodable record does. `byteOffset` counts every byte of the overall
      * input, including skipped blank lines and a leading byte order mark, so it addresses the record in the original source.
      *
      * Do not compare two records with `==`. `Span` is an opaque array with no structural equality, so the derived `equals` compares
      * `bytes` by reference and two records holding identical content never match. Compare [[text]] or `bytes.is(other.bytes)` instead.
      *
      * @param bytes
      *   the record's UTF-8 bytes, with any line terminator removed
      * @param index
      *   zero-based position among non-blank lines
      * @param byteOffset
      *   offset of the record's first byte within the overall input
      */
    final case class Record(bytes: Span[Byte], index: Long, byteOffset: Long):
        /** The record decoded as UTF-8 text. */
        def text: String = new String(bytes.toArray, StandardCharsets.UTF_8)
    end Record

    /** One line a framer resolved out of the input.
      *
      * A line is either framed as a record or rejected for exceeding `maxLineBytes`. Both travel in one ordered sequence, so a rejected
      * line stays exactly where it sat among the records around it, which is what lets a lenient consumer report one failure for it and
      * carry on with the next record.
      */
    enum Line:
        /** A line within the size ceiling, framed with its position in the input. */
        case Kept(record: Record)

        /** A terminated line over the size ceiling. Its terminator is the boundary framing resumed at, so the lines after it are framed
          * normally and carry their ordinary index and offset.
          */
        case Skipped(breach: LimitExceededException)
    end Line

    /** The outcome of feeding one chunk to a [[Framer]].
      *
      * `lines` always holds every line the fed chunk resolved, in order, whichever case this is: a size breach never discards a line that
      * was already resolved.
      *
      * The two cases are the two things a size breach can mean, and they are separate states rather than one state carrying a flag. A
      * terminated line over the ceiling has a known boundary, so framing skips to it and carries on: that breach is a [[Line.Skipped]]
      * inside a [[Continued]]. A pending residual over the ceiling has no boundary to skip to, so framing is over, and [[Halted]] carries
      * no framer for exactly that reason.
      */
    enum Framed:
        /** Every line this chunk resolved, and the framer that frames the next one.
          *
          * @param framer
          *   the advanced framer, safe to `feed` again or to call `finish` on
          * @param lines
          *   every line this chunk resolved, in order
          */
        case Continued(framer: Framer, lines: Chunk[Line])

        /** Every line resolved before the pending residual outgrew `maxLineBytes`, and that breach.
          *
          * No record boundary was found for the residual, so there is nothing to skip to and framing is over. There is no framer to carry
          * on with, and `finish` is not reachable either: the bytes that would have produced a trailing record are the ones that breached.
          *
          * @param lines
          *   every line resolved before the residual breached, in order
          * @param breach
          *   the residual's size against the ceiling it exceeded
          */
        case Halted(lines: Chunk[Line], breach: LimitExceededException)
    end Framed

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

        /** Feeds a chunk, returning every line it resolved along with the framer for the next one.
          *
          * @param chunk
          *   the next bytes of the input, of any size including empty
          * @return
          *   [[Framed.Continued]] holding every line this chunk resolved and the advanced framer, or [[Framed.Halted]] when the pending
          *   residual outgrew `maxLineBytes` with no record boundary to skip to. A terminated line over the ceiling is not a halt: it
          *   arrives as a [[Line.Skipped]] inside [[Framed.Continued]], and framing carries on after its terminator.
          */
        def feed(chunk: Span[Byte])(using Frame): Framed =
            val combined = if residual.isEmpty then chunk else residual ++ chunk
            if atStart && startsWithBomPrefix(combined) then
                if combined.size < Bom.size then
                    // Still undecided: the bytes so far are a proper prefix of the mark, and a proper
                    // prefix holds no newline, so there is nothing to emit while waiting for the rest.
                    Framed.Continued(Framer(combined, nextIndex, residualOffset, maxLineBytes, true), Chunk.empty)
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
            val (start, restOffset, index, lines) = emitFrom(buf, 0, offset, nextIndex, Chunk.empty)
            // The residual is measured by the same rule a completed record is: a trailing '\r' is the
            // first half of a terminator that has not arrived yet, so it does not count toward the limit.
            val restSize = buf.size - start
            val restLine =
                if restSize > 0 && buf(buf.size - 1) == Return then restSize - 1
                else restSize
            if restLine > maxLineBytes then
                // No newline anywhere in the residual, so there is no boundary to resume at and no framer
                // worth handing back. The lines resolved before it stay.
                Framed.Halted(lines, LimitExceededException(LimitName, restLine, maxLineBytes))
            else
                val rest = if start == 0 then buf else buf.slice(start, buf.size)
                Framed.Continued(Framer(rest, index, restOffset, maxLineBytes, false), lines)
            end if
        end scan

        /** Walks `buf` from `start`, resolving one line per newline.
          *
          * Carries `start` as an index rather than re-slicing the tail after each line: `Span.slice` copies, so slicing per record would
          * make framing a chunk of k records cost O(chunk size * k). Only a kept record copies, which keeps the total cost linear in the
          * chunk with allocation proportional to the output.
          *
          * `offset` is the overall-input offset of `buf(start)`, and the returned `Int` is the index where the unterminated residual
          * begins. A line over `maxLineBytes` is recorded as a [[Line.Skipped]] and the walk continues past its terminator, since that
          * terminator is a record boundary like any other. It consumes a record index, because it is a non-blank line that a lenient
          * consumer sees one failure for, so an index keeps naming a line's position whether or not the line was kept.
          */
        @tailrec
        private def emitFrom(
            buf: Span[Byte],
            start: Int,
            offset: Long,
            index: Long,
            acc: Chunk[Line]
        )(using Frame): (Int, Long, Long, Chunk[Line]) =
            indexOfNewline(buf, start) match
                case -1 => (start, offset, index, acc)
                case nl =>
                    val lineEnd    = if nl > start && buf(nl - 1) == Return then nl - 1 else nl
                    val lineSize   = lineEnd - start
                    val restOffset = offset + (nl + 1 - start)
                    if lineSize > maxLineBytes then
                        emitFrom(
                            buf,
                            nl + 1,
                            restOffset,
                            index + 1,
                            acc :+ Line.Skipped(LimitExceededException(LimitName, lineSize, maxLineBytes))
                        )
                    else if isBlank(buf, start, lineEnd) then emitFrom(buf, nl + 1, restOffset, index, acc)
                    else
                        emitFrom(
                            buf,
                            nl + 1,
                            restOffset,
                            index + 1,
                            acc :+ Line.Kept(Record(buf.slice(start, lineEnd), index, offset))
                        )
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
      * `decodeAllBytes` reduces `decodeAllBytesResults` through this. It is not a general-purpose
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
        foldResults(decodeAllBytesResults[A](input, maxDepth, maxCollectionSize, maxLineBytes))
    end decodeAllBytes

    /** Decodes every record, returning one `Result` per record instead of failing the whole input.
      *
      * The variant for heterogeneous or partially-written logs: a truncated tail or an unrecognized record type yields one failure rather
      * than discarding the whole file.
      *
      * A record exceeding `maxLineBytes` is one failure element too, as long as its terminator arrived: the boundary is known, so framing
      * skips the record and the records after it decode normally. Only an oversized trailing residual, which has no boundary to skip to,
      * cannot be recovered from; it surfaces as a final failure element and ends the chunk. Either way every record decoded before the
      * breach is kept: a limit breach on record 5 of a 4-record-so-far log yields 4 successes followed by that one failure, not an empty
      * chunk.
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
      *   one result per record, including one failure per record skipped for exceeding `maxLineBytes`; an oversized trailing residual,
      *   which finds no record boundary, is appended as a final failure element after every record decoded before it
      */
    def decodeAllBytesResults[A](
        input: Span[Byte],
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = DefaultMaxLineBytes
    )(using Json, Schema[A], Frame): Chunk[Result[DecodeException, A]] =
        Framer.init(maxLineBytes).feed(input) match
            case Framed.Continued(framer, lines) =>
                val decoded = decodeLines[A](lines, maxDepth, maxCollectionSize)
                framer.finish match
                    case Absent       => decoded
                    case Present(rec) => decoded :+ decodeRecord[A](rec, maxDepth, maxCollectionSize)
            case Framed.Halted(lines, breach) =>
                decodeLines[A](lines, maxDepth, maxCollectionSize) :+ Result.fail(breach)
        end match
    end decodeAllBytesResults

    /** Decodes every kept line and turns every skipped one into the failure a consumer sees in its place.
      *
      * Keeping the two in one pass is what preserves the ordering the framer resolved them in, so a skipped record's failure sits
      * between the results of the records around it rather than being appended after them.
      */
    private def decodeLines[A](
        lines: Chunk[Line],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using Json, Schema[A], Frame): Chunk[Result[DecodeException, A]] =
        lines.map {
            case Line.Kept(record)    => decodeRecord[A](record, maxDepth, maxCollectionSize)
            case Line.Skipped(breach) => Result.fail(breach)
        }
    end decodeLines

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
      * accumulator again for every value, which is quadratic in the number of values; this stays linear in the total output size for
      * every input `Seq`. `Span.concat` indexes its pieces (`spans(i)`), which is O(i) for a `List`, so the pieces are forced to
      * `IndexedSeq` before the call: a no-op for a `Vector` or `ArraySeq`, one linear copy for a `List`. Skipping that forcing would
      * keep the `List` case quadratic in the number of values, just via pointer chases instead of byte copies.
      *
      * @param values
      *   the values to encode, in order
      * @return
      *   the JSONL bytes, or an empty span for an empty input
      */
    def encodeAllBytes[A](values: Seq[A])(using Json, Schema[A], Frame): Span[Byte] =
        val pieces = values.flatMap(v => Seq(Json.encodeBytes(v), NewlineSpan))
        Span.concat(pieces.toIndexedSeq*)
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
