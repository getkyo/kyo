package kyo.internal

import kyo.Maybe
import kyo.Maybe.*
import kyo.Span
import kyo.discard
import scala.annotation.tailrec

/** Flat array table holding all layout, style, and tree structure data for one frame.
  *
  * Each UI node gets an integer index. Tree structure is encoded via index references in parallel arrays. All arrays are pre-allocated and
  * reused across frames via arena-reset semantics: `reset()` sets count=0, every slot is written before read.
  */
final private[kyo] class TuiLayout(initialCapacity: Int = 256):

    private var cap: Int = initialCapacity
    var count: Int       = 0

    // ---- tree structure ----
    var parent: Array[Int]      = new Array[Int](cap)  // parent index, -1 = root
    var firstChild: Array[Int]  = new Array[Int](cap)  // -1 = leaf
    var nextSibling: Array[Int] = new Array[Int](cap)  // -1 = last
    var lastChild: Array[Int]   = new Array[Int](cap)  // for O(1) child append
    var nodeType: Array[Byte]   = new Array[Byte](cap) // element type tag

    // ---- geometry (written by measure + arrange) ----
    var x: Array[Int]     = new Array[Int](cap)
    var y: Array[Int]     = new Array[Int](cap)
    var w: Array[Int]     = new Array[Int](cap)
    var h: Array[Int]     = new Array[Int](cap)
    var intrW: Array[Int] = new Array[Int](cap) // intrinsic width from measure
    var intrH: Array[Int] = new Array[Int](cap) // intrinsic height from measure

    // ---- layout style (flat, no LayoutStyle objects) ----
    var lFlags: Array[Int] = new Array[Int](cap) // packed: direction|align|justify|overflow|hidden|borders
    var padT: Array[Int]   = new Array[Int](cap)
    var padR: Array[Int]   = new Array[Int](cap)
    var padB: Array[Int]   = new Array[Int](cap)
    var padL: Array[Int]   = new Array[Int](cap)
    var marT: Array[Int]   = new Array[Int](cap)
    var marR: Array[Int]   = new Array[Int](cap)
    var marB: Array[Int]   = new Array[Int](cap)
    var marL: Array[Int]   = new Array[Int](cap)
    var gap: Array[Int]    = new Array[Int](cap)
    var sizeW: Array[Int]  = new Array[Int](cap)
    var sizeH: Array[Int]  = new Array[Int](cap) // -1 = auto
    var minW: Array[Int]   = new Array[Int](cap)
    var maxW: Array[Int]   = new Array[Int](cap) // -1 = none
    var minH: Array[Int]   = new Array[Int](cap)
    var maxH: Array[Int]   = new Array[Int](cap)
    var transX: Array[Int] = new Array[Int](cap)
    var transY: Array[Int] = new Array[Int](cap)

    // ---- paint style (flat, no PaintStyle objects) ----
    var pFlags: Array[Int]  = new Array[Int](cap)   // packed: bold|dim|italic|underline|strikethrough|borderStyle|...
    var fg: Array[Int]      = new Array[Int](cap)   // -1 = absent
    var bg: Array[Int]      = new Array[Int](cap)   // -1 = absent
    var bdrClrT: Array[Int] = new Array[Int](cap)   // -1 = absent
    var bdrClrR: Array[Int] = new Array[Int](cap)
    var bdrClrB: Array[Int] = new Array[Int](cap)
    var bdrClrL: Array[Int] = new Array[Int](cap)
    var opac: Array[Float]  = new Array[Float](cap) // 1.0 = opaque
    var lineH: Array[Int]   = new Array[Int](cap)
    var letSp: Array[Int]   = new Array[Int](cap)
    var fontSz: Array[Int]  = new Array[Int](cap)
    var shadow: Array[Int]  = new Array[Int](cap)   // -1 = none

    // ---- content (refs into UI AST — no copy) ----
    var text: Array[Maybe[String]]        = Array.fill(cap)(Absent) // Absent = no text
    var focusStyle: Array[Maybe[AnyRef]]  = Array.fill(cap)(Absent) // Absent = none (ref to UI AST Style)
    var activeStyle: Array[Maybe[AnyRef]] = Array.fill(cap)(Absent) // Absent = none
    var element: Array[Maybe[AnyRef]]     = Array.fill(cap)(Absent) // Absent = text/fragment

    /** O(1) reset — slots overwritten before read. */
    def reset(): Unit = count = 0

    /** Allocate a new node, growing arrays if needed. Returns the index. */
    def alloc(): Int =
        if count == cap then grow()
        val idx = count
        count += 1
        idx
    end alloc

    private def copyMaybeArray[A <: AnyRef](src: Array[Maybe[A]], newLen: Int): Array[Maybe[A]] =
        val dst = new Array[AnyRef](newLen).asInstanceOf[Array[Maybe[A]]]
        java.lang.System.arraycopy(src, 0, dst, 0, src.length)
        dst
    end copyMaybeArray

    /** Double all arrays. */
    private def grow(): Unit =
        val newCap = cap * 2
        parent = java.util.Arrays.copyOf(parent, newCap)
        firstChild = java.util.Arrays.copyOf(firstChild, newCap)
        nextSibling = java.util.Arrays.copyOf(nextSibling, newCap)
        lastChild = java.util.Arrays.copyOf(lastChild, newCap)
        nodeType = java.util.Arrays.copyOf(nodeType, newCap)
        x = java.util.Arrays.copyOf(x, newCap)
        y = java.util.Arrays.copyOf(y, newCap)
        w = java.util.Arrays.copyOf(w, newCap)
        h = java.util.Arrays.copyOf(h, newCap)
        intrW = java.util.Arrays.copyOf(intrW, newCap)
        intrH = java.util.Arrays.copyOf(intrH, newCap)
        lFlags = java.util.Arrays.copyOf(lFlags, newCap)
        padT = java.util.Arrays.copyOf(padT, newCap)
        padR = java.util.Arrays.copyOf(padR, newCap)
        padB = java.util.Arrays.copyOf(padB, newCap)
        padL = java.util.Arrays.copyOf(padL, newCap)
        marT = java.util.Arrays.copyOf(marT, newCap)
        marR = java.util.Arrays.copyOf(marR, newCap)
        marB = java.util.Arrays.copyOf(marB, newCap)
        marL = java.util.Arrays.copyOf(marL, newCap)
        gap = java.util.Arrays.copyOf(gap, newCap)
        sizeW = java.util.Arrays.copyOf(sizeW, newCap)
        sizeH = java.util.Arrays.copyOf(sizeH, newCap)
        minW = java.util.Arrays.copyOf(minW, newCap)
        maxW = java.util.Arrays.copyOf(maxW, newCap)
        minH = java.util.Arrays.copyOf(minH, newCap)
        maxH = java.util.Arrays.copyOf(maxH, newCap)
        transX = java.util.Arrays.copyOf(transX, newCap)
        transY = java.util.Arrays.copyOf(transY, newCap)
        pFlags = java.util.Arrays.copyOf(pFlags, newCap)
        fg = java.util.Arrays.copyOf(fg, newCap)
        bg = java.util.Arrays.copyOf(bg, newCap)
        bdrClrT = java.util.Arrays.copyOf(bdrClrT, newCap)
        bdrClrR = java.util.Arrays.copyOf(bdrClrR, newCap)
        bdrClrB = java.util.Arrays.copyOf(bdrClrB, newCap)
        bdrClrL = java.util.Arrays.copyOf(bdrClrL, newCap)
        opac = java.util.Arrays.copyOf(opac, newCap)
        lineH = java.util.Arrays.copyOf(lineH, newCap)
        letSp = java.util.Arrays.copyOf(letSp, newCap)
        fontSz = java.util.Arrays.copyOf(fontSz, newCap)
        shadow = java.util.Arrays.copyOf(shadow, newCap)
        text = copyMaybeArray(text, newCap)
        focusStyle = copyMaybeArray(focusStyle, newCap)
        activeStyle = copyMaybeArray(activeStyle, newCap)
        element = copyMaybeArray(element, newCap)
        cap = newCap
    end grow

end TuiLayout

/** Layout operations: linking, measuring, and arranging nodes. */
private[kyo] object TuiLayout:

    // ---- lFlags bit layout ----
    inline val DirBit        = 0 // 0=column, 1=row
    inline val AlignShift    = 1 // 2 bits
    inline val AlignMask     = 0x3
    inline val JustShift     = 3 // 3 bits
    inline val JustMask      = 0x7
    inline val OverflowShift = 6 // 2 bits
    inline val OverflowMask  = 0x3
    inline val HiddenBit     = 8
    inline val BorderTBit    = 9
    inline val BorderRBit    = 10
    inline val BorderBBit    = 11
    inline val BorderLBit    = 12
    inline val DisabledBit   = 13

    // ---- lFlags accessors ----
    inline def isRow(flags: Int): Boolean      = (flags & (1 << DirBit)) != 0
    inline def isColumn(flags: Int): Boolean   = !isRow(flags)
    inline def align(flags: Int): Int          = (flags >>> AlignShift) & AlignMask
    inline def justify(flags: Int): Int        = (flags >>> JustShift) & JustMask
    inline def overflow(flags: Int): Int       = (flags >>> OverflowShift) & OverflowMask
    inline def isHidden(flags: Int): Boolean   = (flags & (1 << HiddenBit)) != 0
    inline def hasBorderT(flags: Int): Boolean = (flags & (1 << BorderTBit)) != 0
    inline def hasBorderR(flags: Int): Boolean = (flags & (1 << BorderRBit)) != 0
    inline def hasBorderB(flags: Int): Boolean = (flags & (1 << BorderBBit)) != 0
    inline def hasBorderL(flags: Int): Boolean = (flags & (1 << BorderLBit)) != 0
    inline def isDisabled(flags: Int): Boolean = (flags & (1 << DisabledBit)) != 0

    // ---- pFlags bit layout ----
    inline val BoldBit          = 0
    inline val DimBit           = 1
    inline val ItalicBit        = 2
    inline val UnderlineBit     = 3
    inline val StrikethroughBit = 4
    inline val BorderStyleShift = 5  // 4 bits
    inline val BorderStyleMask  = 0xf
    inline val RoundedTLBit     = 9
    inline val RoundedTRBit     = 10
    inline val RoundedBRBit     = 11
    inline val RoundedBLBit     = 12
    inline val TextAlignShift   = 13 // 2 bits
    inline val TextAlignMask    = 0x3
    inline val TextDecoShift    = 15 // 2 bits
    inline val TextDecoMask     = 0x3
    inline val TextTransShift   = 17 // 2 bits
    inline val TextTransMask    = 0x3
    inline val TextOverflowBit  = 19
    inline val WrapTextBit      = 20

    // ---- pFlags accessors ----
    inline def isBold(pf: Int): Boolean          = (pf & (1 << BoldBit)) != 0
    inline def isDim(pf: Int): Boolean           = (pf & (1 << DimBit)) != 0
    inline def isItalic(pf: Int): Boolean        = (pf & (1 << ItalicBit)) != 0
    inline def isUnderline(pf: Int): Boolean     = (pf & (1 << UnderlineBit)) != 0
    inline def isStrikethrough(pf: Int): Boolean = (pf & (1 << StrikethroughBit)) != 0

    inline def borderStyle(pf: Int): Int     = (pf >>> BorderStyleShift) & BorderStyleMask
    inline def isRoundedTL(pf: Int): Boolean = (pf & (1 << RoundedTLBit)) != 0
    inline def isRoundedTR(pf: Int): Boolean = (pf & (1 << RoundedTRBit)) != 0
    inline def isRoundedBR(pf: Int): Boolean = (pf & (1 << RoundedBRBit)) != 0
    inline def isRoundedBL(pf: Int): Boolean = (pf & (1 << RoundedBLBit)) != 0

    inline def textAlign(pf: Int): Int           = (pf >>> TextAlignShift) & TextAlignMask
    inline def textDeco(pf: Int): Int            = (pf >>> TextDecoShift) & TextDecoMask
    inline def textTrans(pf: Int): Int           = (pf >>> TextTransShift) & TextTransMask
    inline def hasTextOverflow(pf: Int): Boolean = (pf & (1 << TextOverflowBit)) != 0
    inline def shouldWrapText(pf: Int): Boolean  = (pf & (1 << WrapTextBit)) != 0

    // ---- Border style constants ----
    inline val BorderNone      = 0
    inline val BorderThin      = 1
    inline val BorderRounded   = 2
    inline val BorderHeavy     = 3
    inline val BorderDouble    = 4
    inline val BorderDashed    = 5
    inline val BorderDotted    = 6
    inline val BorderBlock     = 7
    inline val BorderOuterHalf = 8
    inline val BorderInnerHalf = 9

    // ---- Text align constants ----
    inline val TextAlignLeft    = 0
    inline val TextAlignCenter  = 1
    inline val TextAlignRight   = 2
    inline val TextAlignJustify = 3

    /** Get box-drawing characters for a border style via continuation — zero allocation. Passes (TL, TR, BR, BL, Horiz, Vert) directly to
      * the continuation function.
      */
    inline def borderChars[A](
        style: Int,
        roundTL: Boolean,
        roundTR: Boolean,
        roundBR: Boolean,
        roundBL: Boolean
    )(inline f: (Char, Char, Char, Char, Char, Char) => A): A =
        style match
            case BorderHeavy =>
                f('┏', '┓', '┛', '┗', '━', '┃')
            case BorderDouble =>
                f('╔', '╗', '╝', '╚', '═', '║')
            case BorderRounded =>
                f('╭', '╮', '╯', '╰', '─', '│')
            case BorderDashed =>
                f(
                    if roundTL then '╭' else '┌',
                    if roundTR then '╮' else '┐',
                    if roundBR then '╯' else '┘',
                    if roundBL then '╰' else '└',
                    '┄',
                    '┆'
                )
            case BorderDotted =>
                f(
                    if roundTL then '╭' else '┌',
                    if roundTR then '╮' else '┐',
                    if roundBR then '╯' else '┘',
                    if roundBL then '╰' else '└',
                    '┈',
                    '┊'
                )
            case BorderBlock =>
                f('█', '█', '█', '█', '█', '█')
            case BorderOuterHalf =>
                f('▛', '▜', '▟', '▙', '▀', '▌')
            case BorderInnerHalf =>
                f('▗', '▖', '▘', '▝', '▄', '▐')
            case _ => // Thin or None — thin with optional per-corner rounding
                f(
                    if roundTL then '╭' else '┌',
                    if roundTR then '╮' else '┐',
                    if roundBR then '╯' else '┘',
                    if roundBL then '╰' else '└',
                    '─',
                    '│'
                )

    // ---- Justify constants ----
    inline val JustStart   = 0
    inline val JustCenter  = 1
    inline val JustEnd     = 2
    inline val JustBetween = 3
    inline val JustAround  = 4
    inline val JustEvenly  = 5

    // ---- Node type constants (stored as Byte in nodeType array) ----
    inline val NodeText     = 0
    inline val NodeDiv      = 1
    inline val NodeSpan     = 2
    inline val NodeP        = 3
    inline val NodeButton   = 4
    inline val NodeInput    = 5
    inline val NodeTextarea = 6
    inline val NodeSelect   = 7
    inline val NodeOption   = 8
    inline val NodeAnchor   = 9
    inline val NodeForm     = 10
    inline val NodeLabel    = 11
    inline val NodeH1       = 12
    inline val NodeH2       = 13
    inline val NodeH3       = 14
    inline val NodeH4       = 15
    inline val NodeH5       = 16
    inline val NodeH6       = 17
    inline val NodeUl       = 18
    inline val NodeOl       = 19
    inline val NodeLi       = 20
    inline val NodeTable    = 21
    inline val NodeTr       = 22
    inline val NodeTd       = 23
    inline val NodeTh       = 24
    inline val NodeHr       = 25
    inline val NodeBr       = 26
    inline val NodePre      = 27
    inline val NodeCode     = 28
    inline val NodeNav      = 29
    inline val NodeHeader   = 30
    inline val NodeFooter   = 31
    inline val NodeSection  = 32
    inline val NodeMain     = 33
    inline val NodeImg      = 34
    inline val NodeFragment = 35

    /** Whether this node type uses row (inline) direction by default. */
    inline def isInlineNode(nt: Int): Boolean =
        nt == NodeSpan || nt == NodeNav || nt == NodeLi

    /** Whether this node type is focusable. */
    inline def isFocusable(nt: Int): Boolean =
        nt == NodeButton || nt == NodeInput || nt == NodeTextarea ||
            nt == NodeSelect || nt == NodeAnchor

    // ---- Align constants ----
    inline val AlignStart   = 0
    inline val AlignCenter  = 1
    inline val AlignEnd     = 2
    inline val AlignStretch = 3

    /** Wrap text to fit within maxWidth, breaking at word boundaries when possible. */
    def wrapText(text: String, maxWidth: Int): Span[String] =
        if maxWidth <= 0 then Span(text)
        else
            val srcLines = text.split("\n", -1)
            val result   = new java.util.ArrayList[String](srcLines.length)

            @tailrec def findBreak(line: String, pos: Int, end: Int, brk: Int): Int =
                if brk <= pos then end // no space found, hard break
                else if line.charAt(brk) == ' ' then brk
                else findBreak(line, pos, end, brk - 1)

            @tailrec def wrapLine(line: String, pos: Int): Unit =
                if pos < line.length then
                    val end = math.min(pos + maxWidth, line.length)
                    if end < line.length then
                        val brk = findBreak(line, pos, end, end)
                        result.add(line.substring(pos, brk))
                        val nextPos = if line.charAt(brk) == ' ' then brk + 1 else brk
                        wrapLine(line, nextPos)
                    else
                        discard(result.add(line.substring(pos, end)))
                    end if

            @tailrec def processLines(i: Int): Unit =
                if i < srcLines.length then
                    val line = srcLines(i)
                    if line.length <= maxWidth then
                        result.add(line)
                    else
                        wrapLine(line, 0)
                    end if
                    processLines(i + 1)

            processLines(0)
            Span.fromUnsafe(result.toArray(new Array[String](0)))
        end if
    end wrapText

    /** Count wrapped lines without allocating strings — for layout only. */
    def wrapLineCount(text: String, maxWidth: Int): Int =
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
        end if
    end wrapLineCount

    /** Clip text lines to fit within maxWidth/maxHeight, adding ellipsis if overflow flagged. Uses a shared StringBuilder to avoid
      * substring+concat allocations.
      */
    def clipText(text: String, maxWidth: Int, maxHeight: Int, ellipsis: Boolean): Span[String] =
        if maxWidth <= 0 || maxHeight <= 0 then Span.empty[String]
        else
            val srcLines = text.split("\n", -1)
            val count    = math.min(srcLines.length, maxHeight)
            val result   = new Array[String](count)
            val sb       = new java.lang.StringBuilder(maxWidth + 4)

            def truncate(line: String): String =
                if ellipsis && maxWidth > 1 then
                    sb.setLength(0)
                    sb.append(line, 0, maxWidth - 1)
                    sb.append('…')
                    sb.toString
                else if ellipsis && maxWidth == 1 then "…"
                else line.substring(0, maxWidth)

            @tailrec def clipLines(i: Int): Unit =
                if i < count then
                    val line = srcLines(i)
                    result(i) = if line.length > maxWidth then truncate(line) else line
                    clipLines(i + 1)

            clipLines(0)

            // If there are more lines than maxHeight and ellipsis, mark the last line
            if ellipsis && srcLines.length > maxHeight && count > 0 then
                val last = result(count - 1)
                result(count - 1) =
                    if last.length >= maxWidth && maxWidth > 1 then
                        sb.setLength(0)
                        sb.append(last, 0, maxWidth - 1)
                        sb.append('…')
                        sb.toString
                    else if last.length < maxWidth then
                        sb.setLength(0)
                        sb.append(last)
                        sb.append('…')
                        sb.toString
                    else last
            end if
            Span.fromUnsafe(result)
        end if
    end clipText

    /** Link a child node to its parent. O(1) via lastChild tracking. */
    def linkChild(layout: TuiLayout, parentIdx: Int, childIdx: Int): Unit =
        layout.parent(childIdx) = parentIdx
        layout.firstChild(childIdx) = -1
        layout.nextSibling(childIdx) = -1
        layout.lastChild(childIdx) = -1
        if parentIdx >= 0 then
            if layout.firstChild(parentIdx) == -1 then
                layout.firstChild(parentIdx) = childIdx
            else
                layout.nextSibling(layout.lastChild(parentIdx)) = childIdx
            end if
            layout.lastChild(parentIdx) = childIdx
        end if
    end linkChild

    /** Bottom-up intrinsic sizing. Reverse traversal ensures children measured before parents. */
    def measure(layout: TuiLayout): Unit =

        @tailrec def maxLineWidth(lines: Array[String], j: Int, maxW: Int): Int =
            if j >= lines.length then maxW
            else maxLineWidth(lines, j + 1, math.max(maxW, lines(j).length))

        inline def sumChildren(layout: TuiLayout, firstChild: Int, row: Boolean)(
            inline f: (Int, Int, Int) => Unit
        ): Unit =
            @tailrec def loop(c: Int, mainSum: Int, crossMax: Int, childCount: Int): Unit =
                if c == -1 then f(mainSum, crossMax, childCount)
                else if isHidden(layout.lFlags(c)) then
                    loop(layout.nextSibling(c), mainSum, crossMax, childCount)
                else
                    val cw = layout.intrW(c)
                    val ch = layout.intrH(c)
                    if row then loop(layout.nextSibling(c), mainSum + cw, math.max(crossMax, ch), childCount + 1)
                    else loop(layout.nextSibling(c), mainSum + ch, math.max(crossMax, cw), childCount + 1)
            loop(firstChild, 0, 0, 0)
        end sumChildren

        @tailrec def loop(i: Int): Unit =
            if i >= 0 then
                if !isHidden(layout.lFlags(i)) then
                    val maybeTxt = layout.text(i)
                    if maybeTxt.isDefined then
                        val lines = maybeTxt.get.split("\n", -1)
                        layout.intrW(i) = maxLineWidth(lines, 0, 0)
                        layout.intrH(i) = lines.length
                    else
                        val flags = layout.lFlags(i)
                        val row   = isRow(flags)
                        sumChildren(layout, layout.firstChild(i), row) { (mainSum, crossMax, childCount) =>
                            val gapTotal = if childCount > 1 then layout.gap(i) * (childCount - 1) else 0
                            val bT       = if hasBorderT(flags) then 1 else 0
                            val bR       = if hasBorderR(flags) then 1 else 0
                            val bB       = if hasBorderB(flags) then 1 else 0
                            val bL       = if hasBorderL(flags) then 1 else 0
                            val insetW   = layout.padL(i) + layout.padR(i) + bL + bR
                            val insetH   = layout.padT(i) + layout.padB(i) + bT + bB

                            if row then
                                layout.intrW(i) = mainSum + gapTotal + insetW
                                layout.intrH(i) = crossMax + insetH
                            else
                                layout.intrW(i) = crossMax + insetW
                                layout.intrH(i) = mainSum + gapTotal + insetH
                            end if
                        }
                    end if

                    // Apply explicit size, then clamp
                    if layout.sizeW(i) >= 0 then layout.intrW(i) = layout.sizeW(i)
                    if layout.sizeH(i) >= 0 then layout.intrH(i) = layout.sizeH(i)
                    if layout.minW(i) >= 0 && layout.intrW(i) < layout.minW(i) then layout.intrW(i) = layout.minW(i)
                    if layout.maxW(i) >= 0 && layout.intrW(i) > layout.maxW(i) then layout.intrW(i) = layout.maxW(i)
                    if layout.minH(i) >= 0 && layout.intrH(i) < layout.minH(i) then layout.intrH(i) = layout.minH(i)
                    if layout.maxH(i) >= 0 && layout.intrH(i) > layout.maxH(i) then layout.intrH(i) = layout.maxH(i)
                end if
                loop(i - 1)

        loop(layout.count - 1)
    end measure

    /** Top-down position assignment. Root fills terminal, children positioned by parent. */
    def arrange(layout: TuiLayout, termW: Int, termH: Int): Unit =
        if layout.count != 0 then
            // Root node fills terminal
            layout.x(0) = 0
            layout.y(0) = 0
            layout.w(0) = termW
            layout.h(0) = termH

            inline def countChildrenLoop(layout: TuiLayout, firstChild: Int, row: Boolean)(
                inline f: (Int, Int) => Unit
            ): Unit =
                @tailrec def loop(c: Int, totalMain: Int, childCount: Int): Unit =
                    if c == -1 then f(totalMain, childCount)
                    else if isHidden(layout.lFlags(c)) then
                        loop(layout.nextSibling(c), totalMain, childCount)
                    else
                        val cMain = if row then layout.intrW(c) else layout.intrH(c)
                        loop(layout.nextSibling(c), totalMain + cMain, childCount + 1)
                loop(firstChild, 0, 0)
            end countChildrenLoop

            @tailrec def positionChildren(
                layout: TuiLayout,
                c: Int,
                row: Boolean,
                contentX: Int,
                contentY: Int,
                contentH: Int,
                contentW: Int,
                alignMode: Int,
                betweenGap: Int,
                childCount: Int,
                pos: Int,
                childIdx: Int
            ): Unit =
                if c != -1 then
                    if isHidden(layout.lFlags(c)) then
                        positionChildren(
                            layout,
                            layout.nextSibling(c),
                            row,
                            contentX,
                            contentY,
                            contentH,
                            contentW,
                            alignMode,
                            betweenGap,
                            childCount,
                            pos,
                            childIdx
                        )
                    else
                        val cMainSize  = if row then layout.intrW(c) else layout.intrH(c)
                        val cCrossSize = if row then layout.intrH(c) else layout.intrW(c)
                        val crossSpace = if row then contentH else contentW

                        val crossOffset = alignMode match
                            case AlignStart   => 0
                            case AlignCenter  => (crossSpace - cCrossSize) / 2
                            case AlignEnd     => crossSpace - cCrossSize
                            case AlignStretch => 0
                            case _            => 0

                        val childW = if row then cMainSize else (if alignMode == AlignStretch then crossSpace else cCrossSize)
                        val childH = if row then (if alignMode == AlignStretch then crossSpace else cCrossSize) else cMainSize

                        if row then
                            layout.x(c) = contentX + pos + layout.transX(c)
                            layout.y(c) = contentY + crossOffset + layout.transY(c)
                        else
                            layout.x(c) = contentX + crossOffset + layout.transX(c)
                            layout.y(c) = contentY + pos + layout.transY(c)
                        end if

                        layout.w(c) = childW
                        layout.h(c) = childH

                        // Re-wrap text node if wrapping is enabled and width is now known
                        if layout.text(c).isDefined && shouldWrapText(layout.pFlags(c)) && childW > 0 then
                            val lines = wrapLineCount(layout.text(c).get, childW)
                            layout.intrH(c) = lines
                            layout.h(c) = lines
                        end if

                        val nextPos = pos + (if row then layout.w(c) else layout.h(c)) +
                            (if childIdx < childCount - 1 then betweenGap else 0)
                        positionChildren(
                            layout,
                            layout.nextSibling(c),
                            row,
                            contentX,
                            contentY,
                            contentH,
                            contentW,
                            alignMode,
                            betweenGap,
                            childCount,
                            nextPos,
                            childIdx + 1
                        )
                    end if

            @tailrec def loop(i: Int): Unit =
                if i < layout.count then
                    if !isHidden(layout.lFlags(i)) then
                        val flags = layout.lFlags(i)
                        val row   = isRow(flags)

                        val bT       = if hasBorderT(flags) then 1 else 0
                        val bR       = if hasBorderR(flags) then 1 else 0
                        val bB       = if hasBorderB(flags) then 1 else 0
                        val bL       = if hasBorderL(flags) then 1 else 0
                        val contentX = layout.x(i) + bL + layout.padL(i) + layout.marL(i)
                        val contentY = layout.y(i) + bT + layout.padT(i) + layout.marT(i)
                        val contentW =
                            math.max(0, layout.w(i) - bL - bR - layout.padL(i) - layout.padR(i) - layout.marL(i) - layout.marR(i))
                        val contentH =
                            math.max(0, layout.h(i) - bT - bB - layout.padT(i) - layout.padB(i) - layout.marT(i) - layout.marB(i))

                        countChildrenLoop(layout, layout.firstChild(i), row) { (totalMain, childCount) =>
                            val gapSize     = layout.gap(i)
                            val gapTotal    = if childCount > 1 then gapSize * (childCount - 1) else 0
                            val mainSpace   = if row then contentW else contentH
                            val freeSpace   = math.max(0, mainSpace - totalMain - gapTotal)
                            val justifyMode = justify(flags)
                            val alignMode   = align(flags)

                            val mainOffset = justifyMode match
                                case JustCenter => freeSpace / 2
                                case JustEnd    => freeSpace
                                case JustAround =>
                                    if childCount > 0 then freeSpace / childCount / 2 else 0
                                case JustEvenly =>
                                    if childCount > 0 then freeSpace / (childCount + 1) else 0
                                case _ => 0

                            val betweenGap = justifyMode match
                                case JustBetween =>
                                    if childCount > 1 then gapSize + freeSpace / (childCount - 1) else gapSize
                                case JustAround =>
                                    if childCount > 0 then gapSize + freeSpace / childCount else gapSize
                                case JustEvenly =>
                                    if childCount > 0 then gapSize + freeSpace / (childCount + 1) else gapSize
                                case _ => gapSize

                            positionChildren(
                                layout,
                                layout.firstChild(i),
                                row,
                                contentX,
                                contentY,
                                contentH,
                                contentW,
                                alignMode,
                                betweenGap,
                                childCount,
                                mainOffset,
                                0
                            )
                        }
                    end if
                    loop(i + 1)

            loop(0)
        end if
    end arrange

end TuiLayout
