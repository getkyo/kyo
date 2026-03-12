package kyo.internal

import scala.annotation.tailrec

/** Reads TuiLayout flat arrays and paints into TuiRenderer.
  *
  * Two phases:
  *   - `inheritStyles`: forward pass propagating fg/bg/pFlags from parents to children
  *   - `paint`: recursive tree walk rendering backgrounds, borders, text, and children
  *
  * Pure computation, no I/O. The caller is responsible for calling `renderer.clear()` before `paint`.
  */
private[kyo] object TuiPainter:

    // ──────────────────────── Style Inheritance ────────────────────────

    /** Forward pass O(n): propagate inherited properties from parent to child.
      *
      * Parents always have lower indices than children (TuiFlatten allocates depth-first). Text nodes inherit everything (fg, bg, pFlags).
      * Element nodes inherit fg/bg only when Absent.
      */
    def inheritStyles(layout: TuiLayout): Unit =
        val count = layout.count
        @tailrec def loop(idx: Int): Unit =
            if idx < count then
                val pi = layout.parent(idx)
                if pi >= 0 then
                    if layout.nodeType(idx) == TuiLayout.NodeText then
                        if layout.fg(idx) == TuiColor.Absent then
                            layout.fg(idx) = layout.fg(pi)
                        if layout.bg(idx) == TuiColor.Absent then
                            layout.bg(idx) = layout.bg(pi)
                        layout.pFlags(idx) = layout.pFlags(pi)
                    else
                        if layout.fg(idx) == TuiColor.Absent then
                            layout.fg(idx) = layout.fg(pi)
                        if layout.bg(idx) == TuiColor.Absent then
                            layout.bg(idx) = layout.bg(pi)
                    end if
                    val opac = layout.opac(idx)
                    if opac < 1.0f then
                        val parentBg = layout.bg(pi)
                        if parentBg != TuiColor.Absent then
                            if layout.fg(idx) != TuiColor.Absent then
                                layout.fg(idx) = TuiColor.blend(layout.fg(idx), parentBg, opac)
                            if layout.bg(idx) != TuiColor.Absent then
                                layout.bg(idx) = TuiColor.blend(layout.bg(idx), parentBg, opac)
                        end if
                    end if
                end if
                loop(idx + 1)
        loop(1)
    end inheritStyles

    // ──────────────────────── Paint ────────────────────────

    /** Paint the layout tree into the renderer. Caller must call `renderer.clear()` first. */
    def paint(layout: TuiLayout, renderer: TuiRenderer): Unit =
        if layout.count > 0 then
            paintNode(layout, renderer, 0)

    private def paintNode(layout: TuiLayout, renderer: TuiRenderer, idx: Int): Unit =
        if idx >= 0 && idx < layout.count then
            val lf = layout.lFlags(idx)
            if !TuiLayout.isHidden(lf) then
                val nx = layout.x(idx)
                val ny = layout.y(idx)
                val nw = layout.w(idx)
                val nh = layout.h(idx)
                if nw > 0 && nh > 0 then
                    if layout.bg(idx) != TuiColor.Absent then
                        renderer.fillBg(nx, ny, nw, nh, layout.bg(idx))

                    val pf = layout.pFlags(idx)
                    val bT = TuiLayout.hasBorderT(lf)
                    val bR = TuiLayout.hasBorderR(lf)
                    val bB = TuiLayout.hasBorderB(lf)
                    val bL = TuiLayout.hasBorderL(lf)
                    val bs = TuiLayout.borderStyle(pf)

                    if (bT || bR || bB || bL) && bs != TuiLayout.BorderNone then
                        paintBorders(layout, renderer, idx, nx, ny, nw, nh, pf, bT, bR, bB, bL, bs)

                    val mt = layout.text(idx)
                    if mt.isDefined then
                        paintText(layout, renderer, idx, nx, ny, nw, nh, pf, bT, bR, bB, bL, mt.get)

                    val overflow = TuiLayout.overflow(lf)
                    if overflow == 1 then
                        val padL = layout.padL(idx)
                        val padR = layout.padR(idx)
                        val padT = layout.padT(idx)
                        val padB = layout.padB(idx)
                        val bt   = if bT then 1 else 0
                        val br   = if bR then 1 else 0
                        val bb   = if bB then 1 else 0
                        val bl   = if bL then 1 else 0
                        val cw   = math.max(0, nw - bl - br - padL - padR)
                        val ch   = math.max(0, nh - bt - bb - padT - padB)
                        if cw > 0 && ch > 0 then
                            val cx = nx + bl + padL
                            val cy = ny + bt + padT
                            renderer.setClip(cx, cy, cx + cw, cy + ch)
                            paintChildren(layout, renderer, idx)
                            renderer.resetClip()
                        end if
                    else
                        paintChildren(layout, renderer, idx)
                    end if
                end if
            end if
    end paintNode

    private def paintChildren(layout: TuiLayout, renderer: TuiRenderer, idx: Int): Unit =
        @tailrec def loop(ch: Int): Unit =
            if ch != -1 then
                paintNode(layout, renderer, ch)
                loop(layout.nextSibling(ch))
        loop(layout.firstChild(idx))
    end paintChildren

    // ──────────────────────── Borders ────────────────────────

    private def paintBorders(
        layout: TuiLayout,
        renderer: TuiRenderer,
        idx: Int,
        nx: Int,
        ny: Int,
        nw: Int,
        nh: Int,
        pf: Int,
        bT: Boolean,
        bR: Boolean,
        bB: Boolean,
        bL: Boolean,
        bs: Int
    ): Unit =
        val fgFallback =
            if layout.fg(idx) != TuiColor.Absent then layout.fg(idx)
            else TuiColor.pack(100, 100, 120)

        val bcT = if layout.bdrClrT(idx) != TuiColor.Absent then layout.bdrClrT(idx) else fgFallback
        val bcR = if layout.bdrClrR(idx) != TuiColor.Absent then layout.bdrClrR(idx) else fgFallback
        val bcB = if layout.bdrClrB(idx) != TuiColor.Absent then layout.bdrClrB(idx) else fgFallback
        val bcL = if layout.bdrClrL(idx) != TuiColor.Absent then layout.bdrClrL(idx) else fgFallback

        TuiLayout.borderChars(
            bs,
            TuiLayout.isRoundedTL(pf),
            TuiLayout.isRoundedTR(pf),
            TuiLayout.isRoundedBR(pf),
            TuiLayout.isRoundedBL(pf)
        ) { (tl, tr, br, bl, hz, vt) =>
            val bgc = layout.bg(idx)

            if bT then
                val stT = packBorderStyle(bcT, bgc)
                if bL then renderer.set(nx, ny, tl, packBorderStyle(bcL, bgc))
                hline(renderer, nx + (if bL then 1 else 0), ny, nx + nw - (if bR then 1 else 0), hz, stT)
                if bR then renderer.set(nx + nw - 1, ny, tr, packBorderStyle(bcR, bgc))
            end if

            if bB then
                val stB = packBorderStyle(bcB, bgc)
                if bL then renderer.set(nx, ny + nh - 1, bl, packBorderStyle(bcL, bgc))
                hline(renderer, nx + (if bL then 1 else 0), ny + nh - 1, nx + nw - (if bR then 1 else 0), hz, stB)
                if bR then renderer.set(nx + nw - 1, ny + nh - 1, br, packBorderStyle(bcR, bgc))
            end if

            if bL then
                vline(renderer, nx, ny + (if bT then 1 else 0), ny + nh - (if bB then 1 else 0), vt, packBorderStyle(bcL, bgc))

            if bR then
                vline(renderer, nx + nw - 1, ny + (if bT then 1 else 0), ny + nh - (if bB then 1 else 0), vt, packBorderStyle(bcR, bgc))
        }
    end paintBorders

    private inline def packBorderStyle(fg: Int, bg: Int): Long =
        TuiRenderer.packStyle(fg = fg, bg = bg)

    private def hline(renderer: TuiRenderer, x0: Int, y: Int, x1: Int, ch: Char, style: Long): Unit =
        @tailrec def loop(x: Int): Unit =
            if x < x1 then
                renderer.set(x, y, ch, style)
                loop(x + 1)
        loop(x0)
    end hline

    private def vline(renderer: TuiRenderer, x: Int, y0: Int, y1: Int, ch: Char, style: Long): Unit =
        @tailrec def loop(y: Int): Unit =
            if y < y1 then
                renderer.set(x, y, ch, style)
                loop(y + 1)
        loop(y0)
    end vline

    // ──────────────────────── Text ────────────────────────

    private def paintText(
        layout: TuiLayout,
        renderer: TuiRenderer,
        idx: Int,
        nx: Int,
        ny: Int,
        nw: Int,
        nh: Int,
        pf: Int,
        bT: Boolean,
        bR: Boolean,
        bB: Boolean,
        bL: Boolean,
        rawText: String
    ): Unit =
        val padL = layout.padL(idx)
        val padR = layout.padR(idx)
        val padT = layout.padT(idx)
        val padB = layout.padB(idx)
        val bt   = if bT then 1 else 0
        val br   = if bR then 1 else 0
        val bb   = if bB then 1 else 0
        val bl   = if bL then 1 else 0
        val cx   = nx + bl + padL
        val cy   = ny + bt + padT
        val cw   = math.max(0, nw - bl - br - padL - padR)
        val ch   = math.max(0, nh - bt - bb - padT - padB)

        if cw > 0 && ch > 0 then
            val text = applyTextTransform(rawText, TuiLayout.textTrans(pf))

            val lines: kyo.Span[String] =
                if TuiLayout.shouldWrapText(pf) then
                    TuiLayout.wrapText(text, cw)
                else if TuiLayout.hasTextOverflow(pf) then
                    TuiLayout.clipText(text, cw, ch, true)
                else
                    TuiLayout.clipText(text, cw, ch, false)

            val textAlignMode = TuiLayout.textAlign(pf)
            val st            = mkTextStyle(layout, idx)
            val maxX          = cx + cw

            @tailrec def renderLines(li: Int): Unit =
                if li < lines.size && li < ch then
                    val line    = lines(li)
                    val lineLen = line.length
                    val startX = textAlignMode match
                        case TuiLayout.TextAlignCenter => cx + math.max(0, (cw - lineLen) / 2)
                        case TuiLayout.TextAlignRight  => cx + math.max(0, cw - lineLen)
                        case _                         => cx

                    @tailrec def renderChars(ci: Int): Unit =
                        if ci < lineLen && (startX + ci) < maxX then
                            if startX + ci >= cx then
                                renderer.set(startX + ci, cy + li, line.charAt(ci), st)
                            renderChars(ci + 1)
                    renderChars(0)
                    renderLines(li + 1)
            renderLines(0)
        end if
    end paintText

    private def applyTextTransform(text: String, transform: Int): String =
        transform match
            case 1 => text.toUpperCase
            case 2 => text.toLowerCase
            case 3 => capitalize(text)
            case _ => text

    private def capitalize(text: String): String =
        if text.isEmpty then text
        else
            val sb  = new java.lang.StringBuilder(text.length)
            val len = text.length
            @tailrec def loop(i: Int, afterSpace: Boolean): Unit =
                if i < len then
                    val c = text.charAt(i)
                    if afterSpace && Character.isLetter(c) then
                        sb.append(Character.toUpperCase(c))
                        loop(i + 1, false)
                    else
                        sb.append(c)
                        loop(i + 1, Character.isWhitespace(c))
                    end if
            loop(0, true)
            sb.toString

    private inline def mkTextStyle(layout: TuiLayout, idx: Int): Long =
        val pf = layout.pFlags(idx)
        TuiRenderer.packStyle(
            fg = layout.fg(idx),
            bg = layout.bg(idx),
            bold = TuiLayout.isBold(pf),
            dim = TuiLayout.isDim(pf),
            italic = TuiLayout.isItalic(pf),
            underline = TuiLayout.isUnderline(pf),
            strikethrough = TuiLayout.isStrikethrough(pf)
        )
    end mkTextStyle

end TuiPainter
