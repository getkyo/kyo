package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
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
  *   Header     = addr_delta<<3 | hasStart<<2 | hasEnd<<1 | hasPoint   (encoded as Nat; SOURCE=4 is special-cased)
  * }}}
  *
  * All fields in Assoc are signed Ints (readInt / readLongInt). The header is read as a Nat first; if the raw value equals SOURCE (4), the
  * next Int is a NameRef for the source path.
  *
  * Offsets are character offsets from the start of the source file. Lines are 1-based (line 1 = first line). Columns are 1-based (column 1
  * = first character of the line). The line-size table entry at index i gives the number of characters on line i+1 NOT counting the
  * trailing newline.
  *
  * Reference: dotty compiler/src/dotty/tools/dotc/core/tasty/PositionUnpickler.scala.
  */
object PositionsUnpickler:

    /** Magic value for SOURCE entries in the Assoc stream. Verbatim from dotty TastyFormat.SOURCE = 4. */
    private val SOURCE = 4

    /** Read the Positions section payload from `view` and return a map from symbol to Tasty.Position.
      *
      * When `sourceFile` is `Maybe.Absent`, position production is skipped entirely: an empty map is returned without error.
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
      *   Map from TASTy byte address to symbol (from Pass1Result.addrMap). Entries whose address is not in this map are skipped.
      * @param sourceFile
      *   The source file name from the Attributes section, if available. Absent skips position production.
      */
    def read(
        view: ByteView,
        addrMap: IntMap[Tasty.Symbol],
        sourceFile: Maybe[String]
    )(using Frame, AllowUnsafe): Map[Tasty.Symbol, Tasty.Position] < (Sync & Abort[TastyError]) =
        val result =
            try Right(readSync(view, addrMap, sourceFile))
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    val msg = ex.getMessage
                    val reason =
                        if msg != null && msg.contains("exceeds Int.MaxValue") then msg
                        else "unexpected end of Positions section"
                    Left(TastyError.MalformedSection("Positions", reason, view.position))
        result match
            case Right(m)  => Sync.defer(m)
            case Left(err) => Abort.fail(err)
    end read

    private def readSync(
        view: ByteView,
        addrMap: IntMap[Tasty.Symbol],
        sourceFile: Maybe[String]
    )(using AllowUnsafe): Map[Tasty.Symbol, Tasty.Position] =
        // An empty section has no data at all; return an empty map immediately without trying to read.
        if view.remaining == 0 then return Map.empty

        // When the Attributes section did not record a SOURCEFILE, we cannot associate positions with a
        // file name (this happens for TASTy compiled without the Attributes SOURCEFILE attribute, e.g. some
        // pre-Scala-3.3 compiler outputs and certain synthetic files). Skip Position production: every
        // symbol's sourcePosition stays Absent. This is not a malformed-section error; it is a legitimate
        // encoding where the source path is only carried by SOURCE entries inside the Positions stream,
        // which kyo-tasty does not resolve (they require walking the TASTy NameTable). No error is emitted.
        sourceFile match
            case Maybe.Absent     => return Map.empty
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
        // a negative lineStart would silently corrupt all subsequent line/column computations (B9).
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
        val builder  = Map.newBuilder[Tasty.Symbol, Tasty.Position]
        var curIndex = 0
        var curStart = 0
        var curEnd   = 0

        while view.remaining > 0 do
            // Read header as a Nat (not a signed Int) to detect SOURCE=4 before interpreting flags.
            val header = view.readNat()
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
                addrMap.get(curIndex) match
                    case Some(sym) =>
                        val (line, col) = offsetToLineCol(curStart, lineStarts)
                        builder += (sym -> Tasty.Position(sf, line, col))
                    case None => () // sub-expression node or unmapped address; skip
                end match
            end if
        end while

        builder.result()
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
