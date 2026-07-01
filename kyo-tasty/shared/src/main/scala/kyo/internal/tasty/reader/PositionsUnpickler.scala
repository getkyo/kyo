package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.LoadingSymbol
import scala.collection.immutable.IntMap

/** Reads the TASTy Positions section.
  *
  * The Positions section encodes per-AST-address character offsets (spans) together with a line-size table that maps offsets to line/column.
  * kyo-tasty decodes this to produce `(line, column)` pairs for definition-level symbols.
  *
  * Format (from dotty TastyFormat.scala, Positions section grammar):
  * {{{
  * Standard-Section: "Positions" LinesSizes Assoc*
  *   LinesSizes = Nat Nat*             -- number of lines, then size of each line (chars, NOT counting trailing '\n')
  *   Assoc      = Header offset_Delta? offset_Delta? point_Delta?
  *              | SOURCE nameref_Int   -- source path change (SOURCE=4); nameref_Int is a signed Int
  *   Header     = addr_delta<<3 | hasStart<<2 | hasEnd<<1 | hasPoint   (encoded as a SIGNED Int; SOURCE=4 is special-cased)
  * }}}
  *
  * Every field in Assoc, INCLUDING the header, is a signed Int (readInt / readLongInt), never an unsigned Nat: `addr_delta` can be
  * negative (a node's address can precede the previous entry's, e.g. for a class member written before a constructor parameter it
  * refers to), so the sign bit on the header's first byte is significant. Reading the header as a Nat misinterprets any header whose
  * first byte has bit 6 set, corrupting `addr_delta` for that entry and every later entry in the same Positions section, since
  * `curIndex` accumulates `addr_delta` across the whole stream: every symbol after the first corrupted entry loses its `sourcePosition`.
  * If the raw header value equals SOURCE (4), the next Int is a NameRef for the source path.
  *
  * Offsets are character offsets from the start of the source file. Lines are 1-based (line 1 = first line). Columns are 1-based (column 1
  * = first character of the line). The line-size table entry at index i gives the number of characters on line i+1 NOT counting the
  * trailing newline.
  *
  * Reference: dotty compiler/src/dotty/tools/dotc/core/tasty/PositionUnpickler.scala.
  */
object PositionsUnpickler:

    /** Absolute TASTy byte address -> (startLine, startColumn, endLine, endColumn), all 1-based,
      * end-exclusive on the end column. Produced by `readSpans` for the occurrence index. Keys are
      * ABSOLUTE (sectionOffset + the section-relative curIndex), the same address space the tree
      * decoder's per-node address cache (`TreeUnpickler.decodeWithAddrs`) and `SymbolBody.addrMap`
      * use, so a scanner can join a decoded node's address straight to its span.
      */
    type PositionMap = scala.collection.immutable.IntMap[(Int, Int, Int, Int)]

    /** Magic value for SOURCE entries in the Assoc stream. Verbatim from dotty TastyFormat.SOURCE = 4. */
    private val SOURCE = 4

    /** Read every addressed span from the Positions section, keyed by ABSOLUTE TASTy byte address.
      *
      * Unlike `read`, which emits a definition-only `(line, column)` point map keyed by symbol id
      * and skips sub-expression addresses, this keeps ALL addresses and both endpoints
      * (`curStart` and `curEnd`), so a use-site IDENT/SELECT node's full span is recoverable. The
      * key is `sectionOffset + curIndex` (absolute), matching the tree decoder's address cache and
      * `SymbolBody.addrMap`, so `sectionOffset` must be the SAME value the body decode uses
      * (`SymbolBody.sectionOffset`). An empty or absent-source section yields an empty map.
      * Structural corruption surfaces as `TastyError.MalformedSection`, mirroring `read`.
      */
    private[kyo] def readSpans(view: kyo.internal.tasty.binary.ByteView, sectionOffset: Int, sourceFile: Maybe[String])(using
        AllowUnsafe
    )
        : Result[TastyError, PositionMap] =
        try Result.Success(readSpansSync(view, sectionOffset, sourceFile))
        catch
            case ex: ArrayIndexOutOfBoundsException =>
                val msg = ex.getMessage
                val reason =
                    if msg != null && msg.contains("exceeds Int.MaxValue") then msg
                    else "unexpected end of Positions section"
                Result.Failure(TastyError.MalformedSection("Positions", reason, view.position))
    end readSpans

    /** Shared implementation for `readSpans`. Reuses `readSync`'s lineSizes/lineStarts construction
      * and `offsetToLineCol` unchanged, with two deltas from `readSync`:
      *   - keeps EVERY addressed entry (no `addrMap` filter; `readSync` skips an address with no
      *     matching definition symbol, `readSpans` has no `addrMap` parameter at all);
      *   - computes the END coordinate (`offsetToLineCol(curEnd, ...)`) alongside the start whenever
      *     `hasEnd`; an entry with no end delta degrades to a zero-width span at its own start
      *     (expected for synthetic/type-level nodes, never for a term use site).
      * A mid-stream `SOURCE` entry is handled exactly like `readSync`: its NameRef payload is
      * consumed and discarded, and every later entry is still written. `readSync` never gates on a
      * SOURCE switch either (it has no name table to resolve the NameRef into a path any more than
      * `readSpans` does) and is validated against real multi-member fixtures, so a real dotty
      * Positions stream's SOURCE entries are not cross-file switches this reader needs to guard
      * against; an earlier draft dropped every entry after SOURCE and lost every use site past the
      * first SOURCE marker in a real pickle (OccurrenceIndexTest caught this).
      */
    private def readSpansSync(
        view: ByteView,
        sectionOffset: Int,
        sourceFile: Maybe[String]
    )(using AllowUnsafe): PositionMap =
        if view.remaining == 0 then return scala.collection.immutable.IntMap.empty

        sourceFile match
            case Maybe.Absent     => return scala.collection.immutable.IntMap.empty
            case Maybe.Present(_) => ()
        end match

        val numLines  = view.readNat()
        val lineSizes = new Array[Int](numLines)
        var i         = 0
        while i < numLines do
            lineSizes(i) = view.readNat()
            i += 1
        end while

        val lineStarts = new Array[Int](numLines + 1)
        lineStarts(0) = 0
        var k = 0
        while k < numLines do
            val nextStart: Long = lineStarts(k).toLong + lineSizes(k).toLong + 1L
            if nextStart > Int.MaxValue then
                throw new ArrayIndexOutOfBoundsException(
                    s"PositionsUnpickler: cumulative lineStart at line ${k + 1} exceeds Int.MaxValue ($nextStart); source file too large"
                )
            end if
            lineStarts(k + 1) = nextStart.toInt
            k += 1
        end while

        var builder  = scala.collection.immutable.IntMap.empty[(Int, Int, Int, Int)]
        var curIndex = 0
        var curStart = 0
        var curEnd   = 0

        while view.remaining > 0 do
            val header = view.readInt()
            if header == SOURCE then
                view.readInt(): Unit
            else
                val addrDelta = header >> 3
                val hasStart  = (header & 4) != 0
                val hasEnd    = (header & 2) != 0
                val hasPoint  = (header & 1) != 0
                curIndex += addrDelta
                if hasStart then curStart += view.readInt()
                if hasEnd then curEnd += view.readInt()
                if hasPoint then view.readInt(): Unit
                val (sl, sc) = offsetToLineCol(curStart, lineStarts)
                val (el, ec) = if hasEnd then offsetToLineCol(curEnd, lineStarts) else (sl, sc)
                builder = builder.updated(curIndex + sectionOffset, (sl, sc, el, ec))
            end if
        end while

        builder
    end readSpansSync

    /** Read the Positions section payload from `view` and return two per-symbol maps: definition points and
      * definition full-extent ranges.
      *
      * The first map keys `symbol.id` to a `Tasty.Position`: the definition's START point (used for
      * `Symbol.sourcePosition`). The second map keys the same `symbol.id` to a `Tasty.SourceRange` covering the
      * definition's FULL extent (start point plus the end coordinate the entry carries), the data behind
      * `Tasty.declarationRange`. Both maps are keyed identically and populated in lock-step, so a symbol present
      * in one is present in the other; the range's `(startLine, startColumn)` always equals its point.
      *
      * When `sourceFile` is `Maybe.Absent`, position production is skipped entirely: two empty maps are returned without error.
      * This matches the behavior of pre-Scala-3.3 TASTy files and files compiled without the Attributes SOURCEFILE attribute
      * (source path only available via SOURCE entries in the Positions stream, which kyo-tasty does not resolve). Symbols in
      * those files stay with `sourcePosition == Maybe.Absent`.
      *
      * A hard-fail error (via `Abort`) is emitted when the Positions section bytes are structurally corrupt (e.g., truncated
      * mid-entry).
      *
      * @param view
      *   ByteView positioned at the start of the Positions section payload. `view.remaining` covers the full section.
      * @param addrMap
      *   Map from TASTy byte address to symbol (from Pass1Result.addrMap). Keys are absolute byte offsets in the TASTy
      *   file (i.e., section-relative address + sectionOffset).
      * @param sourceFile
      *   The source file name from the Attributes section, if available. Absent skips position production.
      * @param sectionOffset
      *   Absolute byte offset of the start of the ASTs section in the TASTy file (Pass1Result.sectionOffset). Added to
      *   each section-relative addr_delta accumulator before looking up in addrMap.
      */
    def read(
        view: ByteView,
        addrMap: IntMap[LoadingSymbol.Materialising],
        sourceFile: Maybe[String],
        sectionOffset: Int
    )(using
        Frame,
        AllowUnsafe
    ): Result[
        TastyError,
        (scala.collection.mutable.LongMap[Tasty.Position], scala.collection.mutable.LongMap[Tasty.SourceRange])
    ] =
        try Result.Success(readSync(view, addrMap, sourceFile, sectionOffset))
        catch
            case ex: ArrayIndexOutOfBoundsException =>
                val msg = ex.getMessage
                val reason =
                    if msg != null && msg.contains("exceeds Int.MaxValue") then msg
                    else "unexpected end of Positions section"
                Result.Failure(TastyError.MalformedSection("Positions", reason, view.position))
    end read

    private def readSync(
        view: ByteView,
        addrMap: IntMap[LoadingSymbol.Materialising],
        sourceFile: Maybe[String],
        sectionOffset: Int
    )(using AllowUnsafe): (scala.collection.mutable.LongMap[Tasty.Position], scala.collection.mutable.LongMap[Tasty.SourceRange]) =
        // An empty section has no data at all; return empty maps immediately without trying to read.
        if view.remaining == 0 then return (scala.collection.mutable.LongMap.empty, scala.collection.mutable.LongMap.empty)

        // When the Attributes section did not record a SOURCEFILE, we cannot associate positions with a
        // file name (this happens for TASTy compiled without the Attributes SOURCEFILE attribute, e.g. some
        // pre-Scala-3.3 compiler outputs and certain synthetic files). Skip Position production: every
        // symbol's sourcePosition stays Absent. This is not a malformed-section error; it is a legitimate
        // encoding where the source path is only carried by SOURCE entries inside the Positions stream,
        // which kyo-tasty does not resolve (they require walking the TASTy NameTable). No error is emitted.
        sourceFile match
            case Maybe.Absent     => return (scala.collection.mutable.LongMap.empty, scala.collection.mutable.LongMap.empty)
            case Maybe.Present(_) => ()
        end match

        val sf = sourceFile.get

        // Read lineSizes: first a Nat giving the number of lines, then one Nat per line giving chars on that line (not counting '\n').
        val numLines  = view.readNat()
        val lineSizes = new Array[Int](numLines)
        var i         = 0
        while i < numLines do
            lineSizes(i) = view.readNat()
            i += 1
        end while

        // Build lineStarts: cumulative character offset of the first character on each line.
        // lineStarts(0) = 0 (line 1 starts at offset 0).
        // lineStarts(k) = lineStarts(k-1) + lineSizes(k-1) + 1 (the +1 accounts for the newline character itself).
        // Arithmetic is widened to Long first to detect Int overflow on pathologically large files;
        // a negative lineStart would silently corrupt all subsequent line/column computations.
        val lineStarts = new Array[Int](numLines + 1)
        lineStarts(0) = 0
        var k = 0
        while k < numLines do
            val nextStart: Long = lineStarts(k).toLong + lineSizes(k).toLong + 1L
            if nextStart > Int.MaxValue then
                throw new ArrayIndexOutOfBoundsException(
                    s"PositionsUnpickler: cumulative lineStart at line ${k + 1} exceeds Int.MaxValue ($nextStart); source file too large"
                )
            end if
            lineStarts(k + 1) = nextStart.toInt
            k += 1
        end while

        // Decode the Assoc stream.
        // Key by symbol.id (primitive Long), not the mutable LoadingSymbol.Materialising case class.
        // Avoids structural-equality fragility when LoadingSymbol.id is mutated post-insertion.
        // Matches the LongMap pattern in AstUnpickler.
        val builder = scala.collection.mutable.LongMap.empty[Tasty.Position]
        // rangeBuilder keeps the definition's FULL extent (start plus the entry's end coordinate), the data
        // behind Tasty.declarationRange. Keyed by symbol.id in lock-step with builder: every emit writes both.
        val rangeBuilder = scala.collection.mutable.LongMap.empty[Tasty.SourceRange]
        var curIndex     = 0
        var curStart     = 0
        var curEnd       = 0

        while view.remaining > 0 do
            // Read header as a signed Int, matching dotty's PositionUnpickler: addr_delta can be negative
            // (see the class scaladoc), so an unsigned Nat read misinterprets headers whose first byte has
            // bit 6 set.
            val header = view.readInt()
            if header == SOURCE then
                // SOURCE entry: the next field is a signed Int NameRef (we skip the source path -- it is already captured via Attributes).
                view.readInt(): Unit
            else
                val addrDelta = header >> 3
                val hasStart  = (header & 4) != 0
                val hasEnd    = (header & 2) != 0
                val hasPoint  = (header & 1) != 0
                curIndex += addrDelta
                if hasStart then curStart += view.readInt()
                if hasEnd then curEnd += view.readInt()
                if hasPoint then view.readInt(): Unit // point delta; not used for line/column derivation
                // Only emit a Position entry if this address maps to a symbol.
                // curIndex is section-relative; addrMap keys are absolute (sectionOffset + section-relative).
                addrMap.get(curIndex + sectionOffset) match
                    case Some(symbol) =>
                        val (line, col) = offsetToLineCol(curStart, lineStarts)
                        builder(symbol.id.toLong) = Tasty.Position(sf, line, col)
                        // Full-extent range: same start point as the Position (both from curStart), end from
                        // curEnd. Mirror readSpans' end handling: an entry with no end delta degrades to a
                        // zero-width range at its own start rather than reusing a stale curEnd.
                        val (endLine, endCol) = if hasEnd then offsetToLineCol(curEnd, lineStarts) else (line, col)
                        rangeBuilder(symbol.id.toLong) = Tasty.SourceRange(sf, line, col, endLine, endCol)
                    case None => () // sub-expression node or unmapped address; skip
                end match
            end if
        end while

        (builder, rangeBuilder)
    end readSync

    /** Convert a character offset to a 1-based (line, column) pair.
      *
      * Uses a linear scan over `lineStarts`. `lineStarts(i)` is the character offset of the first character on line i+1 (1-based). If
      * `offset` lies past the end of all recorded lines, returns (numLines, offset - lastLineStart + 1) to handle synthetic positions.
      */
    private def offsetToLineCol(offset: Int, lineStarts: Array[Int]): (Int, Int) =
        var line = lineStarts.length - 2 // last valid line index (0-based)
        var lo   = 0
        var hi   = lineStarts.length - 2 // inclusive upper bound (last line index)
        // Binary search for the line whose start <= offset < next line start.
        while lo < hi do
            val mid = (lo + hi + 1) >>> 1
            if lineStarts(mid) <= offset then lo = mid
            else hi = mid - 1
        end while
        line = lo
        val col = offset - lineStarts(line) + 1
        (line + 1, col) // convert 0-based line index to 1-based
    end offsetToLineCol

end PositionsUnpickler
