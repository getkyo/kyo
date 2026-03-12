package kyo.internal.tui2.widget

import kyo.*
import kyo.internal.tui2.*
import scala.annotation.tailrec

/** Text input widget — handles Input, Password, Email, Tel, Url, Search, Number, Textarea.
  *
  * Single-line inputs render with horizontal scroll. Textarea renders with word wrapping and vertical scroll. String allocations in
  * handleKey/handlePaste are per-keystroke (event-driven), not per-frame.
  */
private[kyo] object TextInputW:

    def render(
        ti: UI.TextInput,
        elem: UI.Element,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        val rawText   = ValueResolver.resolveString(ti.value, ctx.signals)
        val isFocused = ctx.focus.isFocused(elem)
        val cursor    = if isFocused then ctx.focus.cursorPos(elem) else -1

        elem match
            case ta: UI.Textarea =>
                renderTextarea(rawText, cursor, cx, cy, cw, ch, rs, elem, ctx, ta.placeholder)
            case _: UI.Password =>
                renderSingleLine(rawText, cursor, cx, cy, cw, rs, elem, ctx, mask = true, ti.placeholder)
            case _ =>
                renderSingleLine(rawText, cursor, cx, cy, cw, rs, elem, ctx, mask = false, ti.placeholder)
        end match
    end render

    private def renderSingleLine(
        text: String,
        cursor: Int,
        cx: Int,
        cy: Int,
        cw: Int,
        rs: ResolvedStyle,
        elem: UI.Element,
        ctx: RenderCtx,
        mask: Boolean,
        placeholder: Maybe[String]
    ): Unit =
        // Show placeholder when text is empty (even when focused, matching browser behavior)
        if text.isEmpty && placeholder.nonEmpty then
            val ph      = placeholder.get
            val phStyle = CellStyle(rs.fg, rs.bg, false, false, false, false, true) // dim
            val phEnd   = math.min(ph.length, cw)
            @tailrec def drawPh(i: Int): Unit =
                if i < phEnd then
                    ctx.canvas.screen.set(cx + i, cy, ph.charAt(i), phStyle)
                    drawPh(i + 1)
            drawPh(0)
            // Still show cursor when focused
            if cursor >= 0 then
                ctx.focus.cursorScreenX = cx
                ctx.focus.cursorScreenY = cy
        else
            val scrollX   = ctx.focus.scrollX(elem)
            val newScroll = adjustHorizontalScroll(text, cursor, scrollX, cw)
            ctx.focus.setScrollX(elem, newScroll)
            val style    = rs.cellStyle
            val selStyle = style.swapColors
            val selS     = ctx.focus.selStart(elem)
            val selE     = ctx.focus.selEnd(elem)
            val selLo    = if selS >= 0 then math.min(selS, selE) else -1
            val selHi    = if selS >= 0 then math.max(selS, selE) else -1
            val visEnd   = math.min(text.length, newScroll + cw)
            @tailrec def drawChars(i: Int): Unit =
                if i < visEnd then
                    val ch =
                        if mask then '*'
                        else TextMetrics.applyTransform(text.charAt(i), rs.textTransform)
                    val s = if i >= selLo && i < selHi then selStyle else style
                    ctx.canvas.screen.set(cx + i - newScroll, cy, ch, s)
                    drawChars(i + 1)
            drawChars(newScroll)
            if cursor >= 0 then
                val screenX = cx + cursor - newScroll
                if screenX >= cx && screenX < cx + cw then
                    ctx.focus.cursorScreenX = screenX
                    ctx.focus.cursorScreenY = cy
            end if
        end if
    end renderSingleLine

    private def renderTextarea(
        text: String,
        cursor: Int,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        rs: ResolvedStyle,
        elem: UI.Element,
        ctx: RenderCtx,
        placeholder: Maybe[String]
    ): Unit =
        // Show placeholder when text is empty (even when focused, matching browser behavior)
        if text.isEmpty && placeholder.nonEmpty then
            val ph      = placeholder.get
            val phStyle = CellStyle(rs.fg, rs.bg, false, false, false, false, true) // dim
            drawTextareaLines(ph, cw, ch, 0, cx, cy, phStyle, 0, ctx.canvas.screen)
            // Still show cursor when focused
            if cursor >= 0 then
                ctx.focus.cursorScreenX = cx
                ctx.focus.cursorScreenY = cy
        else
            val scrollY = ctx.focus.scrollY(elem)
            val style   = rs.cellStyle

            // Find cursor position — packed as Long (line in high 32, col in low 32)
            val cursorPacked = findCursorPosition(text, cw, cursor)
            val cursorLine   = (cursorPacked >>> 32).toInt
            val cursorCol    = (cursorPacked & 0xffffffffL).toInt

            // Adjust scroll to keep cursor visible
            val newScroll =
                if cursor < 0 then scrollY
                else if cursorLine < scrollY then cursorLine
                else if cursorLine >= scrollY + ch then cursorLine - ch + 1
                else scrollY
            ctx.focus.setScrollY(elem, newScroll)

            // Render visible lines
            drawTextareaLines(text, cw, ch, newScroll, cx, cy, style, rs.textTransform, ctx.canvas.screen)

            // Set cursor screen position
            if cursor >= 0 then
                val screenY = cy + cursorLine - newScroll
                val screenX = cx + cursorCol
                if screenY >= cy && screenY < cy + ch && screenX >= cx && screenX < cx + cw then
                    ctx.focus.cursorScreenX = screenX
                    ctx.focus.cursorScreenY = screenY
            end if
        end if
    end renderTextarea

    /** Find cursor line and column within wrapped text. Returns packed Long: line in bits [52..32], col in bits [31..0]. Zero-alloc. */
    private def findCursorPosition(text: String, cw: Int, cursor: Int): Long =
        val packed = TextMetrics.foldLines(text, cw, true, 0L) { (acc, start, end) =>
            val lineNum = (acc & 0xfffffL).toInt
            if cursor >= start && cursor <= end then
                ((lineNum + 1).toLong & 0xfffffL) |
                    ((lineNum.toLong & 0xfffffL) << 20) |
                    (((cursor - start).toLong & 0xfffffL) << 40)
            else
                (acc & ~0xfffffL) | ((lineNum + 1).toLong & 0xfffffL)
            end if
        }
        val cursorLine = ((packed >> 20) & 0xfffffL).toInt
        val cursorCol  = ((packed >> 40) & 0xfffffL).toInt
        (cursorLine.toLong << 32) | (cursorCol.toLong & 0xffffffffL)
    end findCursorPosition

    /** Draw visible textarea lines. */
    private def drawTextareaLines(
        text: String,
        cw: Int,
        ch: Int,
        scroll: Int,
        cx: Int,
        cy: Int,
        style: CellStyle,
        textTransform: Int,
        screen: Screen
    ): Unit =
        val _ = TextMetrics.foldLines(text, cw, true, 0) { (lineIdx, start, end) =>
            val visLine = lineIdx - scroll
            if visLine >= 0 && visLine < ch then
                @tailrec def drawCol(ci: Int): Unit =
                    if ci < end - start then
                        val rawCh         = text.charAt(start + ci)
                        val transformedCh = TextMetrics.applyTransform(rawCh, textTransform)
                        screen.set(cx + ci, cy + visLine, transformedCh, style)
                        drawCol(ci + 1)
                drawCol(0)
            end if
            lineIdx + 1
        }
    end drawTextareaLines

    def handleKey(
        ti: UI.TextInput,
        elem: UI.Element,
        event: InputEvent.Key,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Boolean =
        val text     = ValueResolver.resolveString(ti.value, ctx.signals)
        val pos      = ctx.focus.cursorPos(elem)
        val readOnly = ti.readOnly.getOrElse(false)
        val hasSel   = ctx.focus.hasSelection(elem)

        // Ctrl+A: select all
        if event.ctrl && event.key == UI.Keyboard.Char('a') then
            ctx.focus.setSelection(elem, 0, text.length)
            ctx.focus.setCursorPos(elem, text.length)
            return true
        end if

        // Ctrl+ArrowLeft/Right: word navigation
        if event.ctrl then
            event.key match
                case UI.Keyboard.ArrowLeft =>
                    val newPos = findWordBoundaryLeft(text, pos)
                    if event.shift then extendSelection(elem, pos, newPos, ctx)
                    else ctx.focus.clearSelection(elem)
                    ctx.focus.setCursorPos(elem, newPos)
                    return true
                case UI.Keyboard.ArrowRight =>
                    val newPos = findWordBoundaryRight(text, pos)
                    if event.shift then extendSelection(elem, pos, newPos, ctx)
                    else ctx.focus.clearSelection(elem)
                    ctx.focus.setCursorPos(elem, newPos)
                    return true
                case _ => ()
        end if

        event.key match
            case UI.Keyboard.Backspace if !readOnly =>
                if hasSel then
                    deleteSelection(ti, elem, text, ctx)
                    true
                else if pos > 0 then
                    ValueResolver.setString(ti.value, text.substring(0, pos - 1) + text.substring(pos))
                    ctx.focus.setCursorPos(elem, pos - 1)
                    fireOnInput(ti, ctx)
                    true
                else false
            case UI.Keyboard.Delete if !readOnly =>
                if hasSel then
                    deleteSelection(ti, elem, text, ctx)
                    true
                else if pos < text.length then
                    ValueResolver.setString(ti.value, text.substring(0, pos) + text.substring(pos + 1))
                    fireOnInput(ti, ctx)
                    true
                else false
            case UI.Keyboard.Char(c) if !readOnly =>
                if hasSel then
                    replaceSelection(ti, elem, text, c.toString, ctx)
                else
                    ValueResolver.setString(ti.value, text.substring(0, pos) + c + text.substring(pos))
                    ctx.focus.setCursorPos(elem, pos + 1)
                    fireOnInput(ti, ctx)
                end if
                true
            case UI.Keyboard.ArrowLeft =>
                val newPos = if pos > 0 then pos - 1 else pos
                if event.shift then extendSelection(elem, pos, newPos, ctx)
                else if hasSel then
                    val lo = math.min(ctx.focus.selStart(elem), ctx.focus.selEnd(elem))
                    ctx.focus.clearSelection(elem)
                    ctx.focus.setCursorPos(elem, lo)
                    return true
                else ctx.focus.clearSelection(elem)
                end if
                ctx.focus.setCursorPos(elem, newPos)
                true
            case UI.Keyboard.ArrowRight =>
                val newPos = if pos < text.length then pos + 1 else pos
                if event.shift then extendSelection(elem, pos, newPos, ctx)
                else if hasSel then
                    val hi = math.max(ctx.focus.selStart(elem), ctx.focus.selEnd(elem))
                    ctx.focus.clearSelection(elem)
                    ctx.focus.setCursorPos(elem, hi)
                    return true
                else ctx.focus.clearSelection(elem)
                end if
                ctx.focus.setCursorPos(elem, newPos)
                true
            case UI.Keyboard.Home =>
                val newPos = lineStart(text, pos, elem)
                if event.shift then extendSelection(elem, pos, newPos, ctx)
                else ctx.focus.clearSelection(elem)
                ctx.focus.setCursorPos(elem, newPos)
                true
            case UI.Keyboard.End =>
                val newPos = lineEnd(text, pos, elem)
                if event.shift then extendSelection(elem, pos, newPos, ctx)
                else ctx.focus.clearSelection(elem)
                ctx.focus.setCursorPos(elem, newPos)
                true
            case UI.Keyboard.ArrowUp =>
                if !event.shift then ctx.focus.clearSelection(elem)
                elem match
                    case _: UI.Textarea => moveVertical(text, pos, -1, elem, ctx); true
                    case ni: UI.NumberInput if !readOnly =>
                        stepNumber(ni, ti, text, 1, ctx); true
                    case _ => false
                end match
            case UI.Keyboard.ArrowDown =>
                if !event.shift then ctx.focus.clearSelection(elem)
                elem match
                    case _: UI.Textarea => moveVertical(text, pos, 1, elem, ctx); true
                    case ni: UI.NumberInput if !readOnly =>
                        stepNumber(ni, ti, text, -1, ctx); true
                    case _ => false
                end match
            case UI.Keyboard.Enter =>
                elem match
                    case _: UI.Textarea if !readOnly =>
                        if hasSel then
                            replaceSelection(ti, elem, text, "\n", ctx)
                        else
                            ValueResolver.setString(ti.value, text.substring(0, pos) + "\n" + text.substring(pos))
                            ctx.focus.setCursorPos(elem, pos + 1)
                            fireOnInput(ti, ctx)
                        end if
                        true
                    case _ =>
                        false
            case _ => false
        end match
    end handleKey

    def handlePaste(
        ti: UI.TextInput,
        elem: UI.Element,
        paste: String,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Boolean =
        if ti.readOnly.getOrElse(false) then false
        else if ctx.focus.hasSelection(elem) then
            val text = ValueResolver.resolveString(ti.value, ctx.signals)
            replaceSelection(ti, elem, text, paste, ctx)
            true
        else
            val text = ValueResolver.resolveString(ti.value, ctx.signals)
            val pos  = ctx.focus.cursorPos(elem)
            ValueResolver.setString(ti.value, text.substring(0, pos) + paste + text.substring(pos))
            ctx.focus.setCursorPos(elem, pos + paste.length)
            fireOnInput(ti, ctx)
            true
    end handlePaste

    def handleMouse(
        ti: UI.TextInput,
        elem: UI.Element,
        kind: InputEvent.MouseKind,
        mx: Int,
        my: Int,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Boolean =
        val packed = ctx.getContentPosition(elem)
        if packed == -1L then return false
        val cx   = ctx.posX(packed)
        val cy   = ctx.posY(packed)
        val cw   = ctx.posW(packed)
        val ch   = ctx.posH(packed)
        val text = ValueResolver.resolveString(ti.value, ctx.signals)

        kind match
            case InputEvent.MouseKind.LeftPress =>
                val pos = screenToOffset(ti, elem, text, mx, my, cx, cy, cw, ch, ctx)
                ctx.focus.setCursorPos(elem, pos)
                ctx.focus.clearSelection(elem)
                true
            case InputEvent.MouseKind.LeftDrag =>
                val pos    = screenToOffset(ti, elem, text, mx, my, cx, cy, cw, ch, ctx)
                val anchor = ctx.focus.cursorPos(elem)
                ctx.focus.setSelection(elem, anchor, pos)
                true
            case _ => false
        end match
    end handleMouse

    /** Convert screen coordinates (mx, my) to a text cursor offset. */
    private def screenToOffset(
        ti: UI.TextInput,
        elem: UI.Element,
        text: String,
        mx: Int,
        my: Int,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        ctx: RenderCtx
    ): Int =
        if text.isEmpty then return 0
        elem match
            case _: UI.Textarea =>
                // Textarea: find line from (my - cy + scrollY), then column
                val scrollY    = ctx.focus.scrollY(elem)
                val targetLine = scrollY + (my - cy)
                // Walk lines to find the one at targetLine
                val result = TextMetrics.foldLines(text, cw, true, (0, -1)) { case ((lineIdx, found), start, end) =>
                    if found >= 0 then (lineIdx + 1, found)
                    else if lineIdx == targetLine then
                        val col = math.max(0, math.min(mx - cx, end - start))
                        (lineIdx + 1, start + col)
                    else (lineIdx + 1, -1)
                }
                val offset = result._2
                if offset >= 0 then math.min(offset, text.length)
                else text.length // clicked past last line
            case _ =>
                // Single-line: offset = scrollX + (mx - cx), clamped
                val scrollX = ctx.focus.scrollX(elem)
                math.max(0, math.min(text.length, scrollX + (mx - cx)))
        end match
    end screenToOffset

    // ---- Private helpers ----

    private def fireOnInput(ti: UI.TextInput, ctx: RenderCtx)(using Frame, AllowUnsafe): Unit =
        val newValue = ValueResolver.resolveString(ti.value, ctx.signals)
        ti.onInput.foreach(f => ValueResolver.runHandler(f(newValue)))
        ti.onChange.foreach(f => ValueResolver.runHandler(f(newValue)))
    end fireOnInput

    private def stepNumber(ni: UI.NumberInput, ti: UI.TextInput, text: String, direction: Int, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Unit =
        val step    = ni.step.getOrElse(1.0)
        val min     = ni.min.getOrElse(Double.MinValue)
        val max     = ni.max.getOrElse(Double.MaxValue)
        val current = text.toDoubleOption.getOrElse(0.0)
        val next    = math.max(min, math.min(max, current + step * direction))
        val str     = if next == next.toLong.toDouble then next.toLong.toString else next.toString
        ValueResolver.setString(ti.value, str)
        ctx.focus.setCursorPos(ni, str.length)
        fireOnInput(ti, ctx)
        ni.onChangeNumeric.foreach(f => ValueResolver.runHandler(f(next)))
    end stepNumber

    private def adjustHorizontalScroll(text: String, cursor: Int, scroll: Int, width: Int): Int =
        if cursor < 0 then scroll
        else if cursor < scroll then cursor
        else if cursor >= scroll + width then cursor - width + 1
        else scroll

    private def lineStart(text: String, pos: Int, elem: UI.Element): Int =
        elem match
            case _: UI.Textarea =>
                @tailrec def loop(i: Int): Int =
                    if i <= 0 then 0
                    else if text.charAt(i - 1) == '\n' then i
                    else loop(i - 1)
                loop(pos)
            case _ => 0

    private def lineEnd(text: String, pos: Int, elem: UI.Element): Int =
        elem match
            case _: UI.Textarea =>
                @tailrec def loop(i: Int): Int =
                    if i >= text.length then text.length
                    else if text.charAt(i) == '\n' then i
                    else loop(i + 1)
                loop(pos)
            case _ => text.length

    private def moveVertical(text: String, pos: Int, delta: Int, elem: UI.Element, ctx: RenderCtx): Unit =
        val start = lineStart(text, pos, elem)
        val col   = pos - start
        val targetLine =
            if delta < 0 then
                if start > 0 then lineStart(text, start - 1, elem)
                else start
            else
                val end = lineEnd(text, pos, elem)
                if end < text.length then end + 1
                else end
        val targetEnd = lineEnd(text, targetLine, elem)
        val targetCol = math.min(col, targetEnd - targetLine)
        ctx.focus.setCursorPos(elem, targetLine + targetCol)
    end moveVertical

    // ---- Selection helpers ----

    /** Extend selection from current position to new position. */
    private def extendSelection(elem: UI.Element, oldPos: Int, newPos: Int, ctx: RenderCtx): Unit =
        val selS = ctx.focus.selStart(elem)
        if selS < 0 then
            // Start new selection from old cursor position
            ctx.focus.setSelection(elem, oldPos, newPos)
        else
            // Extend existing selection — anchor stays, end moves
            ctx.focus.setSelection(elem, selS, newPos)
        end if
    end extendSelection

    /** Delete the selected text range and update state. */
    private def deleteSelection(ti: UI.TextInput, elem: UI.Element, text: String, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Unit =
        val s  = ctx.focus.selStart(elem)
        val e  = ctx.focus.selEnd(elem)
        val lo = math.min(s, e)
        val hi = math.max(s, e)
        ValueResolver.setString(ti.value, text.substring(0, lo) + text.substring(hi))
        ctx.focus.setCursorPos(elem, lo)
        ctx.focus.clearSelection(elem)
        fireOnInput(ti, ctx)
    end deleteSelection

    /** Replace the selected text with the given string. */
    private def replaceSelection(ti: UI.TextInput, elem: UI.Element, text: String, replacement: String, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Unit =
        val s  = ctx.focus.selStart(elem)
        val e  = ctx.focus.selEnd(elem)
        val lo = math.min(s, e)
        val hi = math.max(s, e)
        ValueResolver.setString(ti.value, text.substring(0, lo) + replacement + text.substring(hi))
        ctx.focus.setCursorPos(elem, lo + replacement.length)
        ctx.focus.clearSelection(elem)
        fireOnInput(ti, ctx)
    end replaceSelection

    /** Find word boundary scanning left from pos. */
    private def findWordBoundaryLeft(text: String, pos: Int): Int =
        if pos <= 0 then 0
        else
            // Skip spaces, then scan to start of word
            @tailrec def skipSpaces(i: Int): Int =
                if i <= 0 then 0
                else if text.charAt(i - 1) == ' ' then skipSpaces(i - 1)
                else i
            @tailrec def scanWord(i: Int): Int =
                if i <= 0 then 0
                else if text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\n' then i
                else scanWord(i - 1)
            scanWord(skipSpaces(pos))

    /** Find word boundary scanning right from pos. */
    private def findWordBoundaryRight(text: String, pos: Int): Int =
        val len = text.length
        if pos >= len then len
        else
            // Skip current word chars, then skip spaces
            @tailrec def scanWord(i: Int): Int =
                if i >= len then len
                else if text.charAt(i) == ' ' || text.charAt(i) == '\n' then i
                else scanWord(i + 1)
            @tailrec def skipSpaces(i: Int): Int =
                if i >= len then len
                else if text.charAt(i) == ' ' then skipSpaces(i + 1)
                else i
            skipSpaces(scanWord(pos))
        end if
    end findWordBoundaryRight

end TextInputW
