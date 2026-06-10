package kyo.test.internal

import kyo.Frame
import scala.collection.mutable.ArrayBuffer

/** Captures subexpression values during assert macro expansion.
  *
  * Each `record(value, position)` returns the value unchanged but appends (position, value.toString) to a per-instance buffer. `diagram`
  * lays out the captured values as a power-assert diagram.
  *
  * One fresh instance is allocated per `assert` call; it is never shared across threads.
  */
// Mutable Array/ArrayBuffer permitted here: diagram rendering is the canonical "mutable read buffer" case per CONTRIBUTING.md.
final class Recorder:

    // (column, renderedValue)
    private val entries = ArrayBuffer.empty[(Int, String)]

    /** Record a subexpression value at the given source column. Returns the value unchanged. */
    def record[A](value: A, col: Int): A =
        entries += ((col, Recorder.render(value)))
        value
    end record

    /** Build a multi-line power-assert diagram from the captured values.
      *
      * Layout:
      *   1. Source line as-is
      *   2. Pipe line: '|' under each captured column, space elsewhere
      *   3. Value lines: values placed at their columns; when a value would overlap the next entry it is pushed to the next line
      *   4. Footer: `// at <fileName>:<lineNumber>`
      */
    def diagram(sourceLine: String, frame: Frame): String =
        val location = frame.position.fileName + ":" + frame.position.lineNumber.toString

        if entries.isEmpty then
            s"$sourceLine\n// at $location"
        else
            // Sort by column ascending
            val sorted = entries.toList.sortBy(_._1)

            // Build the pipe line
            val maxCol  = sorted.map(_._1).max
            val pipeBuf = Array.fill(math.max(0, maxCol + 1))(' ')
            sorted.foreach { case (col, _) =>
                if col < pipeBuf.length then pipeBuf(col) = '|'
            }
            val pipeLine = new String(pipeBuf)

            // Build value lines: place values left-to-right; when a value doesn't fit on the
            // current line (its column < nextAvail) start a new line.
            val valueLines = ArrayBuffer.empty[Array[Char]]

            def ensureLine(idx: Int): Array[Char] =
                while valueLines.size <= idx do
                    valueLines += Array.fill(1)(' ') // start small; we'll expand
                valueLines(idx)
            end ensureLine

            def setAt(lineIdx: Int, col: Int, text: String): Unit =
                // Guard against Int overflow: if col is so large that col + text.length
                // would overflow, skip placement entirely (the diagram would be unprintable).
                if col < 0 || (Int.MaxValue - col) < text.length then ()
                else
                    val line = ensureLine(lineIdx)
                    val need = col + text.length
                    val cur  = line.length
                    val extended =
                        if need > cur then
                            val a = new Array[Char](need)
                            java.lang.System.arraycopy(line, 0, a, 0, cur)
                            java.util.Arrays.fill(a, cur, need, ' ')
                            valueLines(lineIdx) = a
                            a
                        else line
                    text.zipWithIndex.foreach { case (ch, i) => extended(col + i) = ch }
                end if
            end setAt

            // Track the next available position on each line
            val nextAvail = ArrayBuffer.empty[Int]

            def nextAvailFor(idx: Int): Int =
                while nextAvail.size <= idx do nextAvail += 0
                nextAvail(idx)
            end nextAvailFor

            sorted.foreach { case (col, rendered) =>
                // Find the first line where col >= nextAvail
                var lineIdx = 0
                while nextAvailFor(lineIdx) > col do lineIdx += 1
                setAt(lineIdx, col, rendered)
                // +1 for a space separator; guard against Int overflow at extreme columns
                while nextAvail.size <= lineIdx do nextAvail += 0
                val nextPos =
                    if col > Int.MaxValue - rendered.length - 1 then Int.MaxValue
                    else col + rendered.length + 1
                nextAvail(lineIdx) = nextPos
            }

            val valLines = valueLines.map(a => new String(a).stripTrailing()).mkString("\n")

            Recorder.boundedString(s"$sourceLine\n$pipeLine\n$valLines\n// at $location", Recorder.MaxDiagram)
        end if
    end diagram

end Recorder

object Recorder:

    /** Max characters of a single value rendered inline into a power-assert diagram or a structural diff. A
      * value larger than this is unreadable inline anyway; bounding it keeps the failure message compact.
      */
    private[internal] val MaxRenderedValue = 100

    /** Max characters of a full rendered diagram/diff. With up to ~3 bytes per character in modified UTF-8
      * this stays under the 65535-byte `writeUTF` limit the Scala Native test interface uses to ship
      * results back to the JVM, so no failing leaf can ever crash that RPC channel.
      */
    private[internal] val MaxDiagram = 16384

    /** Truncate `s` to at most `max` characters, appending a marker with the original length. Newlines are
      * preserved (this is used for the intentionally multi-line `==` string diff); single-line inline
      * placement goes through [[render]] instead.
      */
    private[internal] def boundedString(s: String, max: Int): String =
        if s.length <= max then s
        else s.substring(0, max) + s"...(${s.length} chars total)"

    /** Null-safe, single-line, length-bounded rendering of an arbitrary value for inline placement in a
      * diagram or diff. `String.valueOf` renders `null` as `"null"` without a NullPointerException (unlike
      * `value.toString`); control characters are escaped so a multi-line value cannot break the
      * column-aligned diagram layout.
      */
    private[internal] def render(value: Any): String =
        val s = String.valueOf(value.asInstanceOf[AnyRef])
        val flat = s
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        if flat.length <= MaxRenderedValue then flat
        else flat.substring(0, MaxRenderedValue) + s"...(${s.length} chars total)"
    end render

end Recorder
