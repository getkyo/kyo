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
  * Framing happens on bytes rather than on text because a UTF-8 multibyte character can straddle a span boundary. Splitting bytes first
  * and decoding each complete record afterwards removes that hazard without an incremental text decoder.
  *
  * Reachable as `Json.Lines`, which aliases this object.
  *
  * @see
  *   [[JsonLines.Framer]] for the resumable, span-oriented framing value
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
      * identically whether its terminator arrived in the same span or a later one.
      */
    val DefaultMaxLineBytes: Int = 16 * 1024 * 1024

    private val LimitName: String = "JSONL record length"

    private val Newline: Byte = '\n'.toByte
    private val Return: Byte  = '\r'.toByte
    private val Tab: Byte     = '\t'.toByte
    private val Space: Byte   = ' '.toByte

    /** The UTF-8 byte order mark: the bytes `EF BB BF`, which several Windows editors and export tools write at the head of a text file to
      * declare its encoding.
      *
      * It belongs to no record, so the framer consumes it before framing begins and counts it in the byte offsets it reports. Left in
      * place it would prefix the first record, and since the mark is not JSON whitespace that record would fail to parse.
      */
    private val Bom: Span[Byte] =
        Span(0xef.toByte, 0xbb.toByte, 0xbf.toByte)

    private val NewlineSpan: Span[Byte] = Span(Newline)

    /** One framed line, with its position in the overall input.
      *
      * The bytes are the line's own UTF-8 encoding with the terminator (`'\n'` and any preceding `'\r'`) already removed. They are carried
      * as bytes rather than text so that framing never has to decode, which is what keeps a line identical no matter how the input was
      * split into spans.
      *
      * `index` is the line's position among the non-blank lines, so blank and whitespace-only lines do not consume an index while a line
      * skipped for exceeding `maxLineBytes` does, exactly as an undecodable line does. `byteOffset` counts every byte of the overall
      * input, including skipped blank lines and a leading byte order mark, so it addresses the line in the original source.
      *
      * Equality is structural over all three: two lines are equal when they carry the same bytes at the same index and the same offset.
      * `Span` is an opaque array whose derived `equals` would compare by reference, so `equals` and `hashCode` are written out rather than
      * synthesized.
      *
      * @param bytes
      *   the line's UTF-8 bytes, with any terminator removed
      * @param index
      *   zero-based position among non-blank lines
      * @param byteOffset
      *   offset of the line's first byte within the overall input
      */
    final case class Line(bytes: Span[Byte], index: Long, byteOffset: Long):
        /** The line decoded as UTF-8 text. */
        def text: String = new String(bytes.toArray, StandardCharsets.UTF_8)

        override def equals(that: Any): Boolean =
            that match
                case other: Line => index == other.index && byteOffset == other.byteOffset && bytes.is(other.bytes)
                case _           => false

        override def hashCode: Int =
            var h = bytes.hash
            h = h * 31 + index.hashCode
            h = h * 31 + byteOffset.hashCode
            h
        end hashCode
    end Line

    object Line:
        given CanEqual[Line, Line] = CanEqual.derived
    end Line

    /** The bytes a framer holds between line terminators, as the pieces they arrived in.
      *
      * Holding pieces rather than one buffer is what keeps a line that spans many spans linear to assemble: appending is O(1) and the
      * pieces are joined exactly once, when a line is actually resolved. Concatenating on every feed instead would copy the whole residual
      * again per span, so a line spanning k spans of size c would cost O(c*k^2) bytes copied.
      *
      * Two invariants hold, and holding the pieces and their total in one value is what keeps them together at every construction site:
      * `size` is the total of `pieces`, so measuring against `maxLineBytes` never joins anything, and no piece is empty, so
      * [[endsWithReturn]] can read the final held byte off the final piece.
      *
      * `size` is a `Long` because it grows by a whole span at a time: a span landing against a ceiling near `Int.MaxValue` would wrap an
      * `Int` total negative, and a framer carrying a negative total frames nonsense rather than halting.
      *
      * @param pieces
      *   the bytes seen since the last line terminator, in arrival order, none of them empty and none of them holding a newline
      * @param size
      *   total size of `pieces`
      */
    final case class Pending private[kyo] (pieces: Chunk[Span[Byte]], size: Long):

        /** Whether anything at all is held. */
        def isEmpty: Boolean = size == 0L

        /** This run with `piece` appended, or this run unchanged when `piece` carries nothing.
          *
          * Dropping an empty piece drops no bytes and changes no total. It is what keeps [[endsWithReturn]] able to read the last held
          * byte off `pieces.last`, since an empty final piece would have no last byte to read.
          */
        def add(piece: Span[Byte]): Pending =
            if piece.isEmpty then this
            else Pending(pieces.append(piece), size + piece.size)

        /** Whether the last held byte is a `'\r'`, which is how a terminator split across a boundary is recognized.
          *
          * False for an empty run, which is the only case with no last byte to look at.
          */
        def endsWithReturn: Boolean =
            !isEmpty && {
                val piece = pieces.last
                piece(piece.size - 1) == Return
            }

        /** Joins the first `size` bytes held, followed by `tail`, into one span.
          *
          * One allocation sized to the line exactly, and one copy of each byte. `size` may fall short of the total available, which is how
          * a trailing `'\r'` is dropped without a second pass: the join stops at `size` no matter which piece that byte sits in.
          *
          * `Chunk.append` builds an append chain, on which `append` and `last` are both O(1) but `apply` is O(k). That is why this takes
          * `toIndexed` once and walks the result: indexing the chain directly would make walking the pieces quadratic in their count,
          * which is the same shape of defect in the piece count that this representation removes in bytes.
          */
        def join(tail: Span[Byte], size: Int): Span[Byte] =
            if size <= 0 then Span.empty[Byte]
            else if isEmpty then tail.slice(0, size)
            else
                val out     = new Array[Byte](size)
                val indexed = pieces.toIndexed
                var i       = 0
                var written = 0
                while i < indexed.size && written < size do
                    val piece = indexed(i)
                    val n     = math.min(piece.size, size - written)
                    // Unsafe: reads the piece's backing array to copy out of it rather than element by element.
                    // `n` is clamped to the piece, nothing is written back, and the array is not retained.
                    Array.copy(piece.toArrayUnsafe, 0, out, written, n)
                    written += n
                    i += 1
                end while
                // Unsafe: the same read-only bulk copy, bounded by what the pieces left unwritten, which the
                // caller sized against `tail`.
                if written < size then Array.copy(tail.toArrayUnsafe, 0, out, written, size - written)
                // Unsafe: `out` is allocated here, filled here, and handed straight to the caller as its only
                // reference, so wrapping it without a defensive copy cannot alias anything.
                Span.fromUnsafe(out)
            end if
        end join

        /** Whether the first `size` bytes held, followed by `tail`, are all blank.
          *
          * Short-circuits on the first non-blank byte, so a long line costs one byte read rather than a join.
          */
        def isBlankPrefix(tail: Span[Byte], size: Int): Boolean =
            val indexed = pieces.toIndexed
            var i       = 0
            var seen    = 0
            var blank   = true
            while blank && i < indexed.size && seen < size do
                val piece = indexed(i)
                val n     = math.min(piece.size, size - seen)
                blank = isBlank(piece, 0, n)
                seen += n
                i += 1
            end while
            if blank && seen < size then isBlank(tail, 0, size - seen)
            else blank
        end isBlankPrefix
    end Pending

    object Pending:
        /** A run holding nothing. */
        val empty: Pending = Pending(Chunk.empty, 0L)
    end Pending

    /** The outcome of feeding one span to a [[Framer]].
      *
      * `lines` always holds every line the fed span resolved, in order, whichever case this is: a size breach never discards a line that
      * was already resolved. A line within the ceiling arrives as a `Result.Success`; a terminated line over it arrives as a
      * `Result.Failure` holding the breach, sitting exactly where the line sat, which is what lets a lenient consumer report one failure
      * for it and carry on with the next line.
      *
      * The two cases are the two things a size breach can mean, and they are separate states rather than one state carrying a flag. A
      * terminated line over the ceiling has a known boundary, so framing skips to it and carries on: that breach is a failure element
      * inside [[Continued]]. A pending residual over the ceiling has no boundary to skip to, so framing is over, and [[Halted]] carries no
      * framer for exactly that reason.
      */
    enum Framed:
        /** Every line this span resolved, and the framer that frames the next one.
          *
          * @param framer
          *   the advanced framer, safe to `feed` again or to call `finishLine` on
          * @param lines
          *   every line this span resolved, in order
          */
        case Continued(framer: Framer, lines: Chunk[Result[LimitExceededException, Line]])

        /** Every line resolved before the pending residual outgrew `maxLineBytes`, and that breach.
          *
          * No line boundary was found for the residual, so there is nothing to skip to and framing is over. There is no framer to carry on
          * with, and `finishLine` is not reachable either: the bytes that would have produced a trailing line are the ones that breached.
          *
          * @param lines
          *   every line resolved before the residual breached, in order
          * @param breach
          *   the residual's size against the ceiling it exceeded
          */
        case Halted(lines: Chunk[Result[LimitExceededException, Line]], breach: LimitExceededException)
    end Framed

    /** Immutable, resumable framer over arbitrary byte spans.
      *
      * Holds the only stateful concern in JSONL handling, a line straddling a span boundary, as a value. Callers thread it through their
      * own loop rather than the framer owning one, so the same framer serves a whole-input decode and a live stream without duplication.
      * Feeding a framer never mutates it: [[feed]] returns the advanced framer, and the receiver stays usable.
      *
      * The bytes seen since the last terminator are held as a [[Pending]] run of pieces and joined exactly once, when a line is actually
      * resolved. A pending piece exists only because no newline was found in it, since a piece is held only after the span it came from
      * was scanned and came up empty. So each `feed` scans only the span just handed to it, and only four things read across the boundary
      * between the pieces held and that span: joining a line, stripping a `'\r'` that ended a piece when its `'\n'` opens the span,
      * testing a line for blankness, and recognizing a byte order mark.
      *
      * A byte order mark is recognized across span boundaries. While the bytes seen so far are still a proper prefix of the mark, the
      * framer holds them and stays in its start state rather than guessing, so a one-byte-at-a-time feed strips the mark exactly as a
      * whole-input feed does.
      *
      * @param pending
      *   the bytes seen since the last line terminator, none of which holds a newline
      * @param nextIndex
      *   index the next emitted line will carry
      * @param residualOffset
      *   overall-input offset of the first pending byte
      * @param maxLineBytes
      *   line size ceiling
      * @param atStart
      *   true until a byte that settles the byte order mark question is consumed
      */
    final case class Framer private[kyo] (
        pending: Pending,
        nextIndex: Long,
        residualOffset: Long,
        maxLineBytes: Int,
        atStart: Boolean
    ):

        /** Feeds a span, returning every line it resolved along with the framer for the next one.
          *
          * @param span
          *   the next bytes of the input, of any size including empty
          * @return
          *   [[Framed.Continued]] holding every line this span resolved and the advanced framer, or [[Framed.Halted]] when the pending
          *   residual outgrew `maxLineBytes` with no line boundary to skip to. A terminated line over the ceiling is not a halt: it
          *   arrives as a failure element inside [[Framed.Continued]], and framing carries on after its terminator.
          */
        def feed(span: Span[Byte])(using Frame): Framed =
            if atStart then feedAtStart(span)
            else scanSpan(pending, residualOffset, span)

        /** Returns the unterminated trailing line, if the input ended without a newline.
          *
          * Resolves at most the one line the residual holds, which is all an end of input can add: every earlier line was resolved by the
          * [[feed]] that saw its terminator.
          *
          * @return
          *   the final line when the residual holds anything other than whitespace, `Absent` otherwise
          */
        def finishLine: Maybe[Line] =
            val size = if pending.endsWithReturn then (pending.size - 1).toInt else pending.size.toInt
            if pending.isBlankPrefix(Span.empty[Byte], size) then Absent
            else Present(Line(pending.join(Span.empty[Byte], size), nextIndex, residualOffset))
        end finishLine

        /** Settles the byte order mark question, then frames normally.
          *
          * Reached only while `atStart`, so `pending` holds at most two bytes and every one of them already matched the mark: that is the
          * only way a framer stays in its start state. Comparing resumes at the pending total rather than at zero for exactly that reason,
          * which keeps the cross-boundary read here bounded to the three bytes of the mark.
          */
        private def feedAtStart(span: Span[Byte])(using Frame): Framed =
            val held    = pending.size.toInt
            val total   = pending.size + span.size
            val decided = math.min(total, Bom.size.toLong).toInt
            var i       = held
            var marked  = true
            while marked && i < decided do
                if span(i - held) != Bom(i) then marked = false
                i += 1
            end while
            if !marked then scanSpan(pending, residualOffset, span)
            else if total < Bom.size then
                // Still undecided: the bytes so far are a proper prefix of the mark, and a proper prefix
                // holds no newline, so there is nothing to emit while waiting for the rest.
                Framed.Continued(Framer(pending.add(span), nextIndex, residualOffset, maxLineBytes, true), Chunk.empty)
            else
                // The whole mark is in hand, and every byte of it was held or is at the head of this span,
                // so nothing pending survives it.
                scanSpan(Pending.empty, residualOffset + Bom.size, span.slice(Bom.size - held, span.size))
            end if
        end feedAtStart

        /** Resolves every line `span` completes, given the pieces held before it.
          *
          * `offset` is the overall-input offset of the first held byte, or of `span(0)` when nothing is held. Only the first newline in
          * `span` can close a line that spans the held pieces, because a held piece is held precisely because it had no newline in it.
          * Every line after that one lies entirely inside `span`, which is what lets [[emitFrom]] keep its single-array walk.
          */
        private def scanSpan(held: Pending, offset: Long, span: Span[Byte])(using Frame): Framed =
            indexOfNewline(span, 0) match
                case -1 =>
                    settle(held.add(span), offset, nextIndex, Chunk.empty)
                case nl =>
                    val (index, lines) = resolveSpanning(held, offset, span, nl)
                    val (start, restOffset, restIndex, restLines) =
                        emitFrom(span, nl + 1, offset + held.size + nl + 1, index, lines)
                    settle(Pending.empty.add(span.slice(start, span.size)), restOffset, restIndex, restLines)
            end match
        end scanSpan

        /** Resolves the one line that ends at `span(nl)`, the only line this span can complete that began before it.
          *
          * Measuring the line never joins anything: its size is the held total plus `nl`, and the `'\r'` that would trim it is the byte
          * before the newline, which is either in `span` or is the last held byte. Only a kept line is joined, and it is joined once, into
          * an array sized to the line exactly.
          */
        private def resolveSpanning(
            held: Pending,
            offset: Long,
            span: Span[Byte],
            nl: Int
        )(using Frame): (Long, Chunk[Result[LimitExceededException, Line]]) =
            val end       = held.size + nl
            val hasReturn = if nl > 0 then span(nl - 1) == Return else held.endsWithReturn
            val lineSize  = if hasReturn then end - 1 else end
            if lineSize > maxLineBytes then
                (nextIndex + 1, Chunk(Result.fail(LimitExceededException(LimitName, lineSize.toInt, maxLineBytes))))
            else if held.isBlankPrefix(span, lineSize.toInt) then (nextIndex, Chunk.empty)
            else
                (
                    nextIndex + 1,
                    Chunk(Result.succeed(Line(held.join(span, lineSize.toInt), nextIndex, offset)))
                )
            end if
        end resolveSpanning

        /** Measures what is left unresolved and either halts on it or hands back the framer that carries it forward.
          *
          * A total too large for an `Int` is over every ceiling an `Int` can express, so it halts like any other breach, with the reported
          * size saturating rather than wrapping.
          */
        private def settle(
            held: Pending,
            offset: Long,
            index: Long,
            lines: Chunk[Result[LimitExceededException, Line]]
        )(using Frame): Framed =
            // The residual is measured by the same rule a completed line is: a trailing '\r' is the
            // first half of a terminator that has not arrived yet, so it does not count toward the limit.
            val restLine = if held.endsWithReturn then held.size - 1 else held.size
            if restLine > maxLineBytes || held.size > Int.MaxValue then
                // No newline anywhere in the residual, so there is no boundary to resume at and no framer
                // worth handing back. The lines resolved before it stay.
                Framed.Halted(lines, LimitExceededException(LimitName, math.min(restLine, Int.MaxValue.toLong).toInt, maxLineBytes))
            else
                Framed.Continued(Framer(held, index, offset, maxLineBytes, false), lines)
            end if
        end settle

        /** Walks `buf` from `start`, resolving one line per newline.
          *
          * Carries `start` as an index rather than re-slicing the tail after each line: `Span.slice` copies, so slicing per line would make
          * framing a span of k lines cost O(span size * k). Only a kept line copies, which keeps the total cost linear in the span with
          * allocation proportional to the output.
          *
          * `offset` is the overall-input offset of `buf(start)`, and the returned `Int` is the index where the unterminated residual
          * begins. A line over `maxLineBytes` is recorded as a failure element and the walk continues past its terminator, since that
          * terminator is a line boundary like any other. It consumes an index, because it is a non-blank line that a lenient consumer sees
          * one failure for, so an index keeps naming a line's position whether or not the line was kept.
          */
        @tailrec
        private def emitFrom(
            buf: Span[Byte],
            start: Int,
            offset: Long,
            index: Long,
            acc: Chunk[Result[LimitExceededException, Line]]
        )(using Frame): (Int, Long, Long, Chunk[Result[LimitExceededException, Line]]) =
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
                            acc :+ Result.fail(LimitExceededException(LimitName, lineSize, maxLineBytes))
                        )
                    else if isBlank(buf, start, lineEnd) then emitFrom(buf, nl + 1, restOffset, index, acc)
                    else
                        emitFrom(
                            buf,
                            nl + 1,
                            restOffset,
                            index + 1,
                            acc :+ Result.succeed(Line(buf.slice(start, lineEnd), index, offset))
                        )
                    end if
            end match
        end emitFrom
    end Framer

    object Framer:
        /** Creates a framer positioned at the start of an input.
          *
          * @param maxLineBytes
          *   the largest line the framer will emit, in bytes
          * @return
          *   a framer holding no pending bytes, at line index zero and byte offset zero
          */
        def init(maxLineBytes: Int = DefaultMaxLineBytes): Framer =
            Framer(Pending.empty, 0L, 0L, maxLineBytes, true)
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
      *   the framed line to decode
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @return
      *   the decoded value, or a `RecordDecodeException` carrying the record's index and byte offset
      */
    def decodeRecord[A](
        record: Line,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize
    )(using json: Json, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        json.decodeFully[A](record.bytes, maxDepth, maxCollectionSize).mapFailure { cause =>
            RecordDecodeException(record.index, record.byteOffset, cause)
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
                framer.finishLine match
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
        lines: Chunk[Result[LimitExceededException, Line]],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using Json, Schema[A], Frame): Chunk[Result[DecodeException, A]] =
        lines.map {
            case Result.Success(record) => decodeRecord[A](record, maxDepth, maxCollectionSize)
            case Result.Failure(breach) => Result.fail(breach)
            case Result.Panic(ex)       => Result.panic(ex)
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
