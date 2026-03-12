package kyo.internal.tui2

import scala.annotation.tailrec

/** Zero-allocation text utilities for TUI layout and painting.
  *
  * All methods operate on raw String char indices. No split, no substring.
  */
private[kyo] object TextMetrics:

    /** Max width of any natural line. */
    inline def naturalWidth(text: String): Int =
        val len = text.length
        @tailrec def loop(i: Int, lineStart: Int, maxW: Int): Int =
            if i > len then maxW
            else if i == len || text.charAt(i) == '\n' then
                val lineLen = i - lineStart
                loop(i + 1, i + 1, if lineLen > maxW then lineLen else maxW)
            else
                loop(i + 1, lineStart, maxW)
        loop(0, 0, 0)
    end naturalWidth

    /** Number of natural lines. */
    inline def naturalHeight(text: String): Int =
        if text.isEmpty then 1
        else
            val len = text.length
            @tailrec def loop(i: Int, count: Int): Int =
                if i >= len then count
                else if text.charAt(i) == '\n' then loop(i + 1, count + 1)
                else loop(i + 1, count)
            loop(0, 1)

    /** Count wrapped lines without allocation. */
    def lineCount(text: String, maxWidth: Int): Int =
        if maxWidth <= 0 then 1
        else
            @tailrec def wrapCount(pos: Int, lineEnd: Int, count: Int): Int =
                if pos >= lineEnd then count
                else
                    val end = math.min(pos + maxWidth, lineEnd)
                    if end < lineEnd then
                        val brk     = findWordBreak(text, pos, end)
                        val nextPos = if brk < text.length && text.charAt(brk) == ' ' then brk + 1 else brk
                        wrapCount(nextPos, lineEnd, count + 1)
                    else
                        count + 1
                    end if

            @tailrec def scanLines(si: Int, lineStart: Int, count: Int): Int =
                if si > text.length then count
                else if si == text.length || text.charAt(si) == '\n' then
                    val lineLen  = si - lineStart
                    val newCount = if lineLen <= maxWidth then count + 1 else wrapCount(lineStart, si, count)
                    scanLines(si + 1, si + 1, newCount)
                else
                    scanLines(si + 1, lineStart, count)

            scanLines(0, 0, 0)
    end lineCount

    /** Iterate wrapped lines via CPS callback. No String allocation.
      *
      * Calls f(startIdx, endIdx) for each visual line (endIdx exclusive). Returns total lines emitted.
      */
    inline def forEachLine(text: String, maxWidth: Int, wrap: Boolean)(
        inline f: (Int, Int) => Unit
    ): Int =
        val textLen = text.length
        if !wrap || maxWidth <= 0 then
            @tailrec def scanNatural(i: Int, lineStart: Int, count: Int): Int =
                if i > textLen then count
                else if i == textLen || text.charAt(i) == '\n' then
                    f(lineStart, i)
                    scanNatural(i + 1, i + 1, count + 1)
                else
                    scanNatural(i + 1, lineStart, count)
            scanNatural(0, 0, 0)
        else
            def wrapSegment(pos: Int, lineEnd: Int, count: Int): Int =
                @tailrec def loop(p: Int, c: Int): Int =
                    if p >= lineEnd then c
                    else
                        val end = math.min(p + maxWidth, lineEnd)
                        if end < lineEnd then
                            val brk = findWordBreak(text, p, end)
                            f(p, brk)
                            val nextP = if brk < textLen && text.charAt(brk) == ' ' then brk + 1 else brk
                            loop(nextP, c + 1)
                        else
                            f(p, end)
                            c + 1
                        end if
                loop(pos, count)
            end wrapSegment

            @tailrec def scanLines(si: Int, lineStart: Int, count: Int): Int =
                if si > textLen then count
                else if si == textLen || text.charAt(si) == '\n' then
                    val newCount =
                        if lineStart == si then
                            f(lineStart, si)
                            count + 1
                        else
                            wrapSegment(lineStart, si, count)
                    scanLines(si + 1, si + 1, newCount)
                else
                    scanLines(si + 1, lineStart, count)

            scanLines(0, 0, 0)
        end if
    end forEachLine

    /** Fold over text lines with an accumulator. Single var isolated here — stack-local after inlining. */
    inline def foldLines[A](text: String, maxWidth: Int, wrap: Boolean, init: A)(
        inline f: (A, Int, Int) => A
    ): A =
        var acc = init
        val _lineCount = forEachLine(text, maxWidth, wrap) { (start, end) =>
            acc = f(acc, start, end)
        }
        acc
    end foldLines

    /** Find word break position scanning backward from end. */
    private def findWordBreak(text: String, pos: Int, end: Int): Int =
        @tailrec def loop(brk: Int): Int =
            if brk <= pos then end
            else if text.charAt(brk) == ' ' then brk
            else loop(brk - 1)
        loop(end)
    end findWordBreak

    /** Apply text transform to a single character. */
    inline def applyTransform(ch: Char, transform: Int): Char =
        transform match
            case 1 => Character.toUpperCase(ch)
            case 2 => Character.toLowerCase(ch)
            case _ => ch // 0=none, 3=capitalize at line level

    /** Capitalize first letter of each word. */
    inline def capitalizeChar(ch: Char, afterSpace: Boolean): Char =
        if afterSpace && Character.isLetter(ch) then Character.toUpperCase(ch)
        else ch

end TextMetrics
