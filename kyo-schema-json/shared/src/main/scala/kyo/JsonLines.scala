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
      * The bytes seen since the last record terminator are held as a sequence of pending pieces rather than as one buffer, and joined
      * exactly once, when a record is actually resolved. Concatenating on every feed instead would copy the whole residual again per
      * chunk, so a record spanning k chunks of size c would cost O(c*k^2) bytes copied: at the shipped defaults a single newline-free 16
      * MiB run copies about 17 GB before the ceiling stops it. Appending a piece is O(1) and joining copies each byte once, which makes
      * the cost linear in the record.
      *
      * A pending piece exists only because no newline was found in it, since a piece is held only after the chunk it came from was
      * scanned and came up empty. So each `feed` scans only the chunk just handed to it, and only four things read across the boundary
      * between the pieces held and that chunk: joining a record, stripping a `'\r'` that ended a piece when its `'\n'` opens the chunk,
      * testing a line for blankness, and recognizing a byte order mark.
      *
      * A byte order mark is recognized across chunk boundaries. While the bytes seen so far are still a proper prefix of the mark, the
      * framer holds them and stays in its start state rather than guessing, so a one-byte-at-a-time feed strips the mark exactly as a
      * whole-input feed does.
      *
      * @param pending
      *   the bytes seen since the last record terminator, in arrival order, none of which holds a newline
      * @param pendingSize
      *   total size of `pending`, carried so the `maxLineBytes` check never has to join anything to measure it
      * @param nextIndex
      *   index the next emitted record will carry
      * @param residualOffset
      *   overall-input offset of the first pending byte
      * @param maxLineBytes
      *   record size ceiling
      * @param atStart
      *   true until a byte that settles the byte order mark question is consumed
      */
    final case class Framer private[kyo] (
        pending: Chunk[Span[Byte]],
        pendingSize: Int,
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
            if atStart then feedAtStart(chunk)
            else scanChunk(pending, pendingSize, residualOffset, chunk)

        /** Returns the unterminated trailing record, if the input ended without a newline.
          *
          * @return
          *   the final record when the residual holds anything other than whitespace, `Absent` otherwise
          */
        def finish: Maybe[Record] =
            val size = if pendingSize > 0 && lastByte(pending) == Return then pendingSize - 1 else pendingSize
            if isBlankPrefix(pending, Span.empty[Byte], size) then Absent
            else Present(Record(join(pending, pendingSize, Span.empty[Byte], size), nextIndex, residualOffset))
        end finish

        /** Settles the byte order mark question, then frames normally.
          *
          * Reached only while `atStart`, so `pending` holds at most two bytes and every one of them already matched the mark: that is the
          * only way a framer stays in its start state. Comparing resumes at `pendingSize` rather than at zero for exactly that reason,
          * which keeps the cross-boundary read here bounded to the three bytes of the mark.
          */
        private def feedAtStart(chunk: Span[Byte])(using Frame): Framed =
            val total   = pendingSize + chunk.size
            val decided = math.min(total, Bom.size)
            var i       = pendingSize
            var marked  = true
            while marked && i < decided do
                if chunk(i - pendingSize) != Bom(i) then marked = false
                i += 1
            end while
            if !marked then scanChunk(pending, pendingSize, residualOffset, chunk)
            else if total < Bom.size then
                // Still undecided: the bytes so far are a proper prefix of the mark, and a proper prefix
                // holds no newline, so there is nothing to emit while waiting for the rest.
                Framed.Continued(
                    Framer(hold(pending, chunk), total, nextIndex, residualOffset, maxLineBytes, true),
                    Chunk.empty
                )
            else
                // The whole mark is in hand, and every byte of it was held or is at the head of this chunk,
                // so nothing pending survives it.
                scanChunk(Chunk.empty, 0, residualOffset + Bom.size, chunk.slice(Bom.size - pendingSize, chunk.size))
            end if
        end feedAtStart

        /** Resolves every line `chunk` completes, given the pieces held before it.
          *
          * `offset` is the overall-input offset of the first held byte, or of `chunk(0)` when nothing is held. Only the first newline in
          * `chunk` can close a line that spans the held pieces, because a held piece is held precisely because it had no newline in it.
          * Every line after that one lies entirely inside `chunk`, which is what lets [[emitFrom]] keep its single-array walk.
          */
        private def scanChunk(held: Chunk[Span[Byte]], heldSize: Int, offset: Long, chunk: Span[Byte])(using Frame): Framed =
            indexOfNewline(chunk, 0) match
                case -1 =>
                    settle(hold(held, chunk), heldSize.toLong + chunk.size, offset, nextIndex, Chunk.empty)
                case nl =>
                    val (index, lines) = resolveSpanning(held, heldSize, offset, chunk, nl)
                    val (start, restOffset, restIndex, restLines) =
                        emitFrom(chunk, nl + 1, offset + heldSize + nl + 1, index, lines)
                    settle(
                        hold(Chunk.empty, chunk.slice(start, chunk.size)),
                        chunk.size - start,
                        restOffset,
                        restIndex,
                        restLines
                    )
            end match
        end scanChunk

        /** Resolves the one line that ends at `chunk(nl)`, the only line this chunk can complete that began before it.
          *
          * Measuring the line never joins anything: its size is the held total plus `nl`, and the `'\r'` that would trim it is the byte
          * before the newline, which is either in `chunk` or is the last held byte. Only a kept line is joined, and it is joined once,
          * into an array sized to the record exactly.
          */
        private def resolveSpanning(
            held: Chunk[Span[Byte]],
            heldSize: Int,
            offset: Long,
            chunk: Span[Byte],
            nl: Int
        )(using Frame): (Long, Chunk[Line]) =
            val end       = heldSize + nl
            val hasReturn = if nl > 0 then chunk(nl - 1) == Return else heldSize > 0 && lastByte(held) == Return
            val lineSize  = if hasReturn then end - 1 else end
            if lineSize > maxLineBytes then
                (nextIndex + 1, Chunk(Line.Skipped(LimitExceededException(LimitName, lineSize, maxLineBytes))))
            else if isBlankPrefix(held, chunk, lineSize) then (nextIndex, Chunk.empty)
            else (nextIndex + 1, Chunk(Line.Kept(Record(join(held, heldSize, chunk, lineSize), nextIndex, offset))))
            end if
        end resolveSpanning

        /** Measures what is left unresolved and either halts on it or hands back the framer that carries it forward.
          *
          * `heldSize` arrives as a `Long` because this is the one place the pending total grows. A chunk landing against a ceiling near
          * `Int.MaxValue` would otherwise wrap it negative, and a framer carrying a negative total frames nonsense rather than halting. A
          * total too large for an `Int` is over every ceiling an `Int` can express, so it halts like any other breach, with the reported
          * size saturating rather than wrapping.
          */
        private def settle(held: Chunk[Span[Byte]], heldSize: Long, offset: Long, index: Long, lines: Chunk[Line])(using Frame): Framed =
            // The residual is measured by the same rule a completed record is: a trailing '\r' is the
            // first half of a terminator that has not arrived yet, so it does not count toward the limit.
            val restLine =
                if heldSize > 0 && lastByte(held) == Return then heldSize - 1
                else heldSize
            if restLine > maxLineBytes || heldSize > Int.MaxValue then
                // No newline anywhere in the residual, so there is no boundary to resume at and no framer
                // worth handing back. The lines resolved before it stay.
                Framed.Halted(lines, LimitExceededException(LimitName, math.min(restLine, Int.MaxValue.toLong).toInt, maxLineBytes))
            else
                Framed.Continued(Framer(held, heldSize.toInt, index, offset, maxLineBytes, false), lines)
            end if
        end settle

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
          *   a framer holding no pending bytes, at record index zero and byte offset zero
          */
        def init(maxLineBytes: Int = DefaultMaxLineBytes): Framer =
            Framer(Chunk.empty, 0, 0L, 0L, maxLineBytes, true)
    end Framer

    /** Appends `piece` to the pending pieces, dropping it when it carries nothing.
      *
      * Keeping empty pieces out is what lets [[lastByte]] read the last held byte off the last piece.
      *
      * `Chunk.append` builds an append chain, on which `append` and `last` are both O(1) but `apply` is O(k). That is why [[join]] and
      * [[isBlankPrefix]] each take `toIndexed` once and walk the result: indexing the chain directly would make walking the pieces
      * quadratic in their count, which is the same shape of defect in the piece count that this representation removes in bytes.
      */
    private def hold(pieces: Chunk[Span[Byte]], piece: Span[Byte]): Chunk[Span[Byte]] =
        if piece.isEmpty then pieces else pieces.append(piece)

    /** The last byte of the last pending piece. Requires a non-empty `pieces`, which every caller checks by its running total. */
    private def lastByte(pieces: Chunk[Span[Byte]]): Byte =
        val piece = pieces.last
        piece(piece.size - 1)

    /** Joins the first `size` bytes of `pieces` followed by `tail` into one span.
      *
      * One allocation sized to the record exactly, and one copy of each byte. `size` may fall short of the total available, which is how a
      * trailing `'\r'` is dropped without a second pass: the join stops at `size` no matter which piece that byte sits in.
      */
    private def join(pieces: Chunk[Span[Byte]], piecesSize: Int, tail: Span[Byte], size: Int): Span[Byte] =
        if size <= 0 then Span.empty[Byte]
        else if piecesSize == 0 then tail.slice(0, size)
        else
            val out     = new Array[Byte](size)
            val indexed = pieces.toIndexed
            var i       = 0
            var written = 0
            while i < indexed.size && written < size do
                val piece = indexed(i)
                val n     = math.min(piece.size, size - written)
                Array.copy(piece.toArrayUnsafe, 0, out, written, n)
                written += n
                i += 1
            end while
            if written < size then Array.copy(tail.toArrayUnsafe, 0, out, written, size - written)
            // Unsafe: `out` is allocated here, filled here, and handed straight to the caller as its only
            // reference, so wrapping it without a defensive copy cannot alias anything.
            Span.fromUnsafe(out)
        end if
    end join

    /** Whether the first `size` bytes of `pieces` followed by `tail` are all blank.
      *
      * Short-circuits on the first non-blank byte, so a long record costs one byte read rather than a join.
      */
    private def isBlankPrefix(pieces: Chunk[Span[Byte]], tail: Span[Byte], size: Int): Boolean =
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
