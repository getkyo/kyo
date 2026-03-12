package kyo.internal

import kyo.Maybe
import kyo.Maybe.*
import kyo.Style
import kyo.UI

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
    var nodeType: Array[Byte]   = new Array[Byte](cap) // -1 = text, else tag.ordinal

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
    var pFlags: Array[Int]       = new Array[Int](cap)    // packed: bold|dim|italic|underline|strikethrough|borderStyle|...
    var fg: Array[Int]           = new Array[Int](cap)    // -1 = absent
    var bg: Array[Int]           = new Array[Int](cap)    // -1 = absent
    var bdrClrT: Array[Int]      = new Array[Int](cap)    // -1 = absent
    var bdrClrR: Array[Int]      = new Array[Int](cap)
    var bdrClrB: Array[Int]      = new Array[Int](cap)
    var bdrClrL: Array[Int]      = new Array[Int](cap)
    var opac: Array[Double]      = new Array[Double](cap) // 1.0 = opaque
    var fontSz: Array[Int]       = new Array[Int](cap)
    var shadowClr: Array[Int]    = new Array[Int](cap)    // shadow color, -1 = none
    var shadowX: Array[Int]      = new Array[Int](cap)
    var shadowY: Array[Int]      = new Array[Int](cap)
    var shadowBlur: Array[Int]   = new Array[Int](cap)
    var shadowSpread: Array[Int] = new Array[Int](cap)

    // ---- flex grow/shrink ----
    var flexGrow: Array[Double]   = new Array[Double](cap) // 0.0 = no grow
    var flexShrink: Array[Double] = new Array[Double](cap) // 1.0 = default shrink

    // ---- filters (8 per node: brightness, contrast, grayscale, sepia, invert, saturate, hueRotate, blur) ----
    var filterBits: Array[Int]    = new Array[Int](cap)             // bit mask: which filters are set (8 bits)
    var filterVals: Array[Double] = Array.fill(cap * 8)(Double.NaN) // 8 doubles per node

    // ---- scroll offsets ----
    var scrollX: Array[Int] = new Array[Int](cap) // horizontal scroll offset (pixels)
    var scrollY: Array[Int] = new Array[Int](cap) // vertical scroll offset (pixels)

    // ---- table spanning ----
    var colspan: Array[Int] = new Array[Int](cap) // 1 = normal (default), >1 = span multiple columns
    var rowspan: Array[Int] = new Array[Int](cap) // 1 = normal (default), >1 = span multiple rows

    // ---- content (refs into UI AST -- no copy) ----
    var text: Array[Maybe[String]]         = Array.fill(cap)(Absent) // Absent = no text
    var focusStyle: Array[Maybe[Style]]    = Array.fill(cap)(Absent) // Absent = none
    var activeStyle: Array[Maybe[Style]]   = Array.fill(cap)(Absent) // Absent = none
    var hoverStyle: Array[Maybe[Style]]    = Array.fill(cap)(Absent) // Absent = none
    var disabledStyle: Array[Maybe[Style]] = Array.fill(cap)(Absent) // Absent = none
    var element: Array[Maybe[UI.Element]]  = Array.fill(cap)(Absent) // Absent = text/fragment

    /** O(1) reset -- slots overwritten before read. */
    def reset(): Unit = count = 0

    /** Allocate a new node, growing arrays if needed. Returns the index. */
    def alloc(): Int =
        if count == cap then grow()
        val idx = count
        count += 1
        idx
    end alloc

    // unsafe: asInstanceOf for Maybe array creation
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
        fontSz = java.util.Arrays.copyOf(fontSz, newCap)
        shadowClr = java.util.Arrays.copyOf(shadowClr, newCap)
        shadowX = java.util.Arrays.copyOf(shadowX, newCap)
        shadowY = java.util.Arrays.copyOf(shadowY, newCap)
        shadowBlur = java.util.Arrays.copyOf(shadowBlur, newCap)
        shadowSpread = java.util.Arrays.copyOf(shadowSpread, newCap)
        flexGrow = java.util.Arrays.copyOf(flexGrow, newCap)
        flexShrink = java.util.Arrays.copyOf(flexShrink, newCap)
        filterBits = java.util.Arrays.copyOf(filterBits, newCap)
        val newFilterVals = new Array[Double](newCap * 8)
        java.lang.System.arraycopy(filterVals, 0, newFilterVals, 0, cap * 8)
        filterVals = newFilterVals
        scrollX = java.util.Arrays.copyOf(scrollX, newCap)
        scrollY = java.util.Arrays.copyOf(scrollY, newCap)
        colspan = java.util.Arrays.copyOf(colspan, newCap)
        rowspan = java.util.Arrays.copyOf(rowspan, newCap)
        text = copyMaybeArray(text, newCap)
        focusStyle = copyMaybeArray(focusStyle, newCap)
        activeStyle = copyMaybeArray(activeStyle, newCap)
        hoverStyle = copyMaybeArray(hoverStyle, newCap)
        disabledStyle = copyMaybeArray(disabledStyle, newCap)
        element = copyMaybeArray(element, newCap)
        cap = newCap
    end grow

end TuiLayout

/** Layout operations: linking, measuring, and arranging nodes. */
private[kyo] object TuiLayout:

    // ---- Node type sentinel for text nodes ----
    inline val NodeText = -1

    // ---- lFlags bit layout ----
    inline val DirBit        = 0  // 0=column, 1=row
    inline val AlignShift    = 1  // 2 bits
    inline val AlignMask     = 0x3
    inline val JustShift     = 3  // 3 bits
    inline val JustMask      = 0x7
    inline val OverflowShift = 6  // 2 bits
    inline val OverflowMask  = 0x3
    inline val HiddenBit     = 8
    inline val BorderTBit    = 9
    inline val BorderRBit    = 10
    inline val BorderBBit    = 11
    inline val BorderLBit    = 12
    inline val DisabledBit   = 13
    inline val PositionBit   = 14 // 0=flow, 1=overlay
    inline val StretchBit    = 15 // per-child cross-axis stretch (like CSS align-self: stretch)
    inline val TableRowBit   = 16 // row is a table row (tr inside table) — uses equal-width column layout
    inline val NoWrapBit     = 17 // text children should scroll, not wrap (single-line input)

    // ---- lFlags accessors ----
    inline def isRow(flags: Int): Boolean        = (flags & (1 << DirBit)) != 0
    inline def isColumn(flags: Int): Boolean     = !isRow(flags)
    inline def align(flags: Int): Int            = (flags >>> AlignShift) & AlignMask
    inline def justify(flags: Int): Int          = (flags >>> JustShift) & JustMask
    inline def overflow(flags: Int): Int         = (flags >>> OverflowShift) & OverflowMask
    inline def isHidden(flags: Int): Boolean     = (flags & (1 << HiddenBit)) != 0
    inline def hasBorderT(flags: Int): Boolean   = (flags & (1 << BorderTBit)) != 0
    inline def hasBorderR(flags: Int): Boolean   = (flags & (1 << BorderRBit)) != 0
    inline def hasBorderB(flags: Int): Boolean   = (flags & (1 << BorderBBit)) != 0
    inline def hasBorderL(flags: Int): Boolean   = (flags & (1 << BorderLBit)) != 0
    inline def isDisabled(flags: Int): Boolean   = (flags & (1 << DisabledBit)) != 0
    inline def isOverlay(flags: Int): Boolean    = (flags & (1 << PositionBit)) != 0
    inline def isStretch(flags: Int): Boolean    = (flags & (1 << StretchBit)) != 0
    inline def isScrollable(flags: Int): Boolean = overflow(flags) == 2
    inline def isTableRow(flags: Int): Boolean   = (flags & (1 << TableRowBit)) != 0
    inline def isNoWrap(flags: Int): Boolean     = (flags & (1 << NoWrapBit)) != 0

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

    /** Get box-drawing characters for a border style via continuation -- zero allocation. Passes (TL, TR, BR, BL, Horiz, Vert) directly to
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
                f('\u250f', '\u2513', '\u251b', '\u2517', '\u2501', '\u2503')
            case BorderDouble =>
                f('\u2554', '\u2557', '\u255d', '\u255a', '\u2550', '\u2551')
            case BorderRounded =>
                f('\u256d', '\u256e', '\u256f', '\u2570', '\u2500', '\u2502')
            case BorderDashed =>
                f(
                    if roundTL then '\u256d' else '\u250c',
                    if roundTR then '\u256e' else '\u2510',
                    if roundBR then '\u256f' else '\u2518',
                    if roundBL then '\u2570' else '\u2514',
                    '\u2504',
                    '\u2506'
                )
            case BorderDotted =>
                f(
                    if roundTL then '\u256d' else '\u250c',
                    if roundTR then '\u256e' else '\u2510',
                    if roundBR then '\u256f' else '\u2518',
                    if roundBL then '\u2570' else '\u2514',
                    '\u2508',
                    '\u250a'
                )
            case BorderBlock =>
                f('\u2588', '\u2588', '\u2588', '\u2588', '\u2588', '\u2588')
            case BorderOuterHalf =>
                f('\u259b', '\u259c', '\u259f', '\u2599', '\u2580', '\u258c')
            case BorderInnerHalf =>
                f('\u2597', '\u2596', '\u2598', '\u259d', '\u2584', '\u2590')
            case _ => // Thin or None -- thin with optional per-corner rounding
                f(
                    if roundTL then '\u256d' else '\u250c',
                    if roundTR then '\u256e' else '\u2510',
                    if roundBR then '\u256f' else '\u2518',
                    if roundBL then '\u2570' else '\u2514',
                    '\u2500',
                    '\u2502'
                )

    // ---- Justify constants ----
    inline val JustStart   = 0
    inline val JustCenter  = 1
    inline val JustEnd     = 2
    inline val JustBetween = 3
    inline val JustAround  = 4
    inline val JustEvenly  = 5

    // ---- Align constants ----
    inline val AlignStart   = 0
    inline val AlignCenter  = 1
    inline val AlignEnd     = 2
    inline val AlignStretch = 3

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

end TuiLayout
