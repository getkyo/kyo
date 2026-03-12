package kyo.internal

import scala.annotation.tailrec

/** Zero-allocation text utilities for TUI layout and painting.
  *
  * All methods operate on raw String char indices via scanning. No `split`, no `substring` allocations.
  */
private[kyo] object TuiText:

    /** Max width of any natural line in text. O(n) char scan, no allocation. */
    inline def naturalWidth(text: String): Int =
        val len = text.length
        // unsafe: while for char scanning performance
        var i         = 0
        var lineStart = 0
        var maxW      = 0
        while i <= len do
            if i == len || text.charAt(i) == '\n' then
                val lineLen = i - lineStart
                if lineLen > maxW then maxW = lineLen
                lineStart = i + 1
            end if
            i += 1
        end while
        maxW
    end naturalWidth

    /** Number of natural lines in text. O(n) char scan, no allocation. */
    inline def naturalHeight(text: String): Int =
        if text.isEmpty then 1
        else
            val len = text.length
            // unsafe: while for char scanning performance
            var i     = 0
            var count = 1
            while i < len do
                if text.charAt(i) == '\n' then count += 1
                i += 1
            end while
            count

    /** Count wrapped lines without allocation. */
    def lineCount(text: String, maxWidth: Int): Int =
        if maxWidth <= 0 then 1
        else
            val textLen = text.length

            @tailrec def findBreak(pos: Int, end: Int, brk: Int): Int =
                if brk <= pos then end
                else if text.charAt(brk) == ' ' then brk
                else findBreak(pos, end, brk - 1)

            @tailrec def countWrapped(pos: Int, lineEnd: Int, count: Int): Int =
                if pos >= lineEnd then count
                else
                    val end = math.min(pos + maxWidth, lineEnd)
                    if end < lineEnd then
                        val brk     = findBreak(pos, end, end)
                        val nextPos = if text.charAt(brk) == ' ' then brk + 1 else brk
                        countWrapped(nextPos, lineEnd, count + 1)
                    else
                        count + 1
                    end if

            @tailrec def processChars(si: Int, lineStart: Int, count: Int): Int =
                if si > textLen then count
                else if si == textLen || text.charAt(si) == '\n' then
                    val lineLen = si - lineStart
                    val newCount =
                        if lineLen <= maxWidth then count + 1
                        else countWrapped(lineStart, si, count)
                    processChars(si + 1, si + 1, newCount)
                else
                    processChars(si + 1, lineStart, count)

            processChars(0, 0, 0)
    end lineCount

    /** Iterate wrapped lines via CPS callback. No String allocation.
      *
      * Calls `f(startIdx, endIdx)` for each visual line where startIdx/endIdx are char indices into `text` (endIdx exclusive). If
      * wrap=true, word-wraps to maxWidth. If wrap=false, yields natural lines.
      *
      * Returns total number of lines emitted.
      */
    inline def forEachLine(text: String, maxWidth: Int, wrap: Boolean)(
        inline f: (Int, Int) => Unit
    ): Int =
        val textLen = text.length
        if !wrap || maxWidth <= 0 then
            // Natural lines only
            // unsafe: while for char scanning performance
            var i         = 0
            var lineStart = 0
            var count     = 0
            while i <= textLen do
                if i == textLen || text.charAt(i) == '\n' then
                    f(lineStart, i)
                    count += 1
                    lineStart = i + 1
                end if
                i += 1
            end while
            count
        else
            // Word-wrap to maxWidth
            // unsafe: while for char scanning performance
            var si        = 0
            var lineStart = 0
            var count     = 0
            while si <= textLen do
                if si == textLen || text.charAt(si) == '\n' then
                    val lineEnd = si
                    // Wrap this natural line
                    var pos = lineStart
                    while pos < lineEnd do
                        val end = math.min(pos + maxWidth, lineEnd)
                        if end < lineEnd then
                            // Find word break
                            var brk = end
                            while brk > pos && text.charAt(brk) != ' ' do
                                brk -= 1
                            end while
                            if brk <= pos then brk = end
                            f(pos, brk)
                            count += 1
                            pos = if text.charAt(brk) == ' ' then brk + 1 else brk
                        else
                            f(pos, end)
                            count += 1
                            pos = end
                        end if
                    end while
                    if lineStart == lineEnd then
                        // Empty line
                        f(lineStart, lineEnd)
                        count += 1
                    end if
                    lineStart = si + 1
                end if
                si += 1
            end while
            count
        end if
    end forEachLine

    /** Apply text transform to a single character. No String allocation. */
    inline def applyTransform(ch: Char, transform: Int): Char =
        transform match
            case 1 => Character.toUpperCase(ch)
            case 2 => Character.toLowerCase(ch)
            case _ => ch // 0=none, 3=capitalize handled at line level

    /** Apply capitalize transform: uppercase first letter of each word. */
    inline def capitalizeChar(ch: Char, afterSpace: Boolean): Char =
        if afterSpace && Character.isLetter(ch) then Character.toUpperCase(ch)
        else ch

end TuiText
