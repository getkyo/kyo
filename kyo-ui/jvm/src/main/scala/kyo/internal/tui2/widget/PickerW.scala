package kyo.internal.tui2.widget

import kyo.*
import kyo.Maybe.*
import kyo.discard
import kyo.internal.tui2.*
import scala.annotation.tailrec

/** Picker widget — handles Select (dropdown) and other picker inputs. */
private[kyo] object PickerW:

    def render(
        pi: UI.PickerInput,
        elem: UI.Element,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        val rawValue = getPickerValue(pi, ctx)
        val value =
            if rawValue.nonEmpty then rawValue
            else
                elem match
                    case sel: UI.Select => findInitiallySelected(sel, pi, ctx)
                    case _              => ""
        val style = rs.cellStyle
        elem match
            case sel: UI.Select =>
                val expanded    = ctx.dropdownOpen && ctx.focus.isFocused(elem)
                val arrow       = if expanded then '\u25b2' else '\u25bc' // ▲ when open, ▼ when closed
                val displayText = findSelectedOptionText(sel, value)
                discard(ctx.canvas.drawString(cx, cy, math.max(0, cw - 2), displayText, 0, style))
                if cw >= 2 then
                    ctx.canvas.drawChar(cx + cw - 1, cy, arrow, style)
                // Schedule deferred dropdown rendering if expanded
                if expanded then
                    val options      = collectOptionValues(sel)
                    val optTexts     = collectOptionTexts(sel)
                    val highlightIdx = ctx.dropdownHighlight
                    // Position dropdown directly below the content row, using the element's full width
                    val packed = ctx.getPosition(sel)
                    val elemX  = if packed != -1L then ctx.posX(packed) else cx
                    val elemW  = if packed != -1L then ctx.posW(packed) else cw
                    ctx.scheduleDropdown(sel, elemX, cy, elemW, optTexts, options, options.length, highlightIdx, style)
                end if
            case _ =>
                discard(ctx.canvas.drawString(cx, cy, cw, value, 0, style))
        end match
    end render

    def handleKey(
        pi: UI.PickerInput,
        elem: UI.Element,
        event: InputEvent.Key,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Boolean =
        elem match
            case sel: UI.Select =>
                if ctx.dropdownOpen then
                    event.key match
                        case UI.Keyboard.ArrowUp =>
                            if ctx.dropdownHighlight > 0 then
                                ctx.dropdownHighlight -= 1
                            true
                        case UI.Keyboard.ArrowDown =>
                            val options = collectOptionValues(sel)
                            if ctx.dropdownHighlight < options.length - 1 then
                                ctx.dropdownHighlight += 1
                            true
                        case UI.Keyboard.Enter =>
                            // Select the highlighted option and close
                            val options = collectOptionValues(sel)
                            if ctx.dropdownHighlight >= 0 && ctx.dropdownHighlight < options.length then
                                val newValue = options(ctx.dropdownHighlight)
                                setPickerValue(sel, newValue, ctx)
                                sel.onChange.foreach(f => ValueResolver.runHandler(f(newValue)))
                            end if
                            ctx.dropdownOpen = false
                            true
                        case UI.Keyboard.Escape =>
                            ctx.dropdownOpen = false
                            true
                        case _ => false
                else
                    event.key match
                        case UI.Keyboard.ArrowUp   => selectPrev(sel, ctx); true
                        case UI.Keyboard.ArrowDown => selectNext(sel, ctx); true
                        case _                     => false
                end if
            case _ => false

    // ---- Value storage helpers (fall back to internal map when no SignalRef bound) ----

    private def getPickerValue(pi: UI.PickerInput, ctx: RenderCtx)(using Frame, AllowUnsafe): String =
        val v = ValueResolver.resolveString(pi.value, ctx.signals)
        if v.nonEmpty then v
        else
            val internal = ctx.pickerValues.get(pi)
            if internal != null then internal else ""
        end if
    end getPickerValue

    private def setPickerValue(pi: UI.PickerInput, value: String, ctx: RenderCtx)(using Frame, AllowUnsafe): Unit =
        pi.value match
            case Present(_: SignalRef[?]) => ValueResolver.setString(pi.value, value)
            case _                        => discard(ctx.pickerValues.put(pi, value))

    // ---- Private helpers ----

    /** Find the display text for the currently selected option. */
    private def findSelectedOptionText(sel: UI.Select, value: String): String =
        @tailrec def loop(i: Int): String =
            if i >= sel.children.size then value
            else
                sel.children(i) match
                    case opt: UI.Opt =>
                        val optValue = opt.value.getOrElse("")
                        if optValue == value then extractText(opt)
                        else loop(i + 1)
                    case _ => loop(i + 1)
        loop(0)
    end findSelectedOptionText

    /** Extract text content from an Opt element's children. */
    private def extractText(opt: UI.Opt): String =
        if opt.children.isEmpty then opt.value.getOrElse("")
        else
            @tailrec def loop(i: Int, result: String): String =
                if i >= opt.children.size then result
                else
                    opt.children(i) match
                        case UI.Text(t) => loop(i + 1, if result.isEmpty then t else result + " " + t)
                        case _          => loop(i + 1, result)
            loop(0, "")

    /** Find the initially selected option via Opt.selected, falling back to the first option (HTML convention). */
    private def findInitiallySelected(sel: UI.Select, pi: UI.PickerInput, ctx: RenderCtx)(using Frame, AllowUnsafe): String =
        @tailrec def loop(i: Int, firstOpt: String): String =
            if i >= sel.children.size then
                // No selected attribute found — fall through to first option (HTML <select> convention)
                if firstOpt.nonEmpty then
                    setPickerValue(pi, firstOpt, ctx)
                firstOpt
            else
                sel.children(i) match
                    case opt: UI.Opt =>
                        val v = opt.value.getOrElse("")
                        if ValueResolver.resolveBoolean(opt.selected, ctx.signals) then
                            setPickerValue(pi, v, ctx)
                            v
                        else loop(i + 1, if firstOpt.isEmpty then v else firstOpt)
                        end if
                    case _ => loop(i + 1, firstOpt)
        loop(0, "")
    end findInitiallySelected

    /** Select the previous option in a Select. */
    private def selectPrev(sel: UI.Select, ctx: RenderCtx)(using Frame, AllowUnsafe): Unit =
        val currentValue = getPickerValue(sel, ctx) match
            case v if v.nonEmpty => v
            case _               => findInitiallySelected(sel, sel, ctx)
        val options = collectOptionValues(sel)
        val idx     = findOptionIndex(options, currentValue)
        if idx > 0 then
            val newValue = options(idx - 1)
            setPickerValue(sel, newValue, ctx)
            sel.onChange.foreach(f => ValueResolver.runHandler(f(newValue)))
        end if
    end selectPrev

    /** Select the next option in a Select. */
    def selectNext(sel: UI.Select, ctx: RenderCtx)(using Frame, AllowUnsafe): Unit =
        val currentValue = getPickerValue(sel, ctx) match
            case v if v.nonEmpty => v
            case _               => findInitiallySelected(sel, sel, ctx)
        val options = collectOptionValues(sel)
        val idx     = findOptionIndex(options, currentValue)
        if idx < options.length - 1 then
            val newValue = options(idx + 1)
            setPickerValue(sel, newValue, ctx)
            sel.onChange.foreach(f => ValueResolver.runHandler(f(newValue)))
        end if
    end selectNext

    /** Toggle the dropdown open/closed. Sets highlight to the current value's index. */
    def toggleExpanded(sel: UI.Select, ctx: RenderCtx)(using Frame, AllowUnsafe): Unit =
        if ctx.dropdownOpen then
            ctx.dropdownOpen = false
        else
            ctx.dropdownOpen = true
            // Set highlight to the currently selected option
            val currentValue = getPickerValue(sel, ctx) match
                case v if v.nonEmpty => v
                case _               => findInitiallySelected(sel, sel, ctx)
            val options = collectOptionValues(sel)
            val idx     = findOptionIndex(options, currentValue)
            ctx.dropdownHighlight = if idx >= 0 then idx else 0
        end if
    end toggleExpanded

    /** Select a specific option by index. Used when clicking on a dropdown option. */
    def selectByIndex(sel: UI.Select, index: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Unit =
        val options = collectOptionValues(sel)
        if index >= 0 && index < options.length then
            val newValue = options(index)
            setPickerValue(sel, newValue, ctx)
            sel.onChange.foreach(f => ValueResolver.runHandler(f(newValue)))
        end if
    end selectByIndex

    /** Collect option display texts from a Select's children. */
    private def collectOptionTexts(sel: UI.Select): Array[String] =
        val builder = scala.collection.mutable.ArrayBuffer.empty[String]
        @tailrec def loop(i: Int): Unit =
            if i < sel.children.size then
                sel.children(i) match
                    case opt: UI.Opt => builder += extractText(opt)
                    case _           => ()
                loop(i + 1)
        loop(0)
        builder.toArray
    end collectOptionTexts

    /** Collect option values from a Select's children. Allocates per-event (not per-frame). */
    private def collectOptionValues(sel: UI.Select): Array[String] =
        val builder = scala.collection.mutable.ArrayBuffer.empty[String]
        @tailrec def loop(i: Int): Unit =
            if i < sel.children.size then
                sel.children(i) match
                    case opt: UI.Opt => builder += opt.value.getOrElse("")
                    case _           => ()
                loop(i + 1)
        loop(0)
        builder.toArray
    end collectOptionValues

    /** Find the index of a value in the options array. */
    private def findOptionIndex(options: Array[String], value: String): Int =
        @tailrec def loop(i: Int): Int =
            if i >= options.length then -1
            else if options(i) == value then i
            else loop(i + 1)
        loop(0)
    end findOptionIndex

    // ---- Editable picker (DateInput, TimeInput, ColorInput) ----

    /** Render editable picker — shows value with cursor for text editing. */
    def renderEditable(
        pi: UI.PickerInput,
        elem: UI.Element,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        val value     = ValueResolver.resolveString(pi.value, ctx.signals)
        val style     = rs.cellStyle
        val cursorPos = ctx.focus.cursorPos(elem)
        val scrollX   = ctx.focus.scrollX(elem)
        val isFocused = ctx.focus.isFocused(elem)

        // Draw value with scroll offset
        val drawLen = discard(ctx.canvas.drawString(cx, cy, cw, value, scrollX, style))

        // Show cursor position for terminal
        if isFocused then
            val screenCursorX = cx + cursorPos - scrollX
            if screenCursorX >= cx && screenCursorX < cx + cw then
                ctx.focus.cursorScreenX = screenCursorX
                ctx.focus.cursorScreenY = cy
        end if
    end renderEditable

    /** Handle key events for editable picker — text editing mode. */
    def handleEditableKey(
        pi: UI.PickerInput,
        elem: UI.Element,
        event: InputEvent.Key,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Boolean =
        val value     = ValueResolver.resolveString(pi.value, ctx.signals)
        val cursorPos = ctx.focus.cursorPos(elem)
        event.key match
            case UI.Keyboard.ArrowLeft =>
                if cursorPos > 0 then ctx.focus.setCursorPos(elem, cursorPos - 1)
                true
            case UI.Keyboard.ArrowRight =>
                if cursorPos < value.length then ctx.focus.setCursorPos(elem, cursorPos + 1)
                true
            case UI.Keyboard.Home =>
                ctx.focus.setCursorPos(elem, 0)
                true
            case UI.Keyboard.End =>
                ctx.focus.setCursorPos(elem, value.length)
                true
            case UI.Keyboard.Backspace =>
                if cursorPos > 0 then
                    val newValue = value.substring(0, cursorPos - 1) + value.substring(cursorPos)
                    ValueResolver.setString(pi.value, newValue)
                    ctx.focus.setCursorPos(elem, cursorPos - 1)
                    pi.onChange.foreach(f => ValueResolver.runHandler(f(newValue)))
                end if
                true
            case UI.Keyboard.Delete =>
                if cursorPos < value.length then
                    val newValue = value.substring(0, cursorPos) + value.substring(cursorPos + 1)
                    ValueResolver.setString(pi.value, newValue)
                    pi.onChange.foreach(f => ValueResolver.runHandler(f(newValue)))
                end if
                true
            case UI.Keyboard.Char(c) if !event.ctrl && !event.alt =>
                val newValue = value.substring(0, cursorPos) + c + value.substring(cursorPos)
                ValueResolver.setString(pi.value, newValue)
                ctx.focus.setCursorPos(elem, cursorPos + 1)
                pi.onChange.foreach(f => ValueResolver.runHandler(f(newValue)))
                true
            case _ => false
        end match
    end handleEditableKey

    /** Handle mouse events for editable picker — click to place cursor. */
    def handleEditableMouse(
        pi: UI.PickerInput,
        elem: UI.Element,
        kind: InputEvent.MouseKind,
        mx: Int,
        my: Int,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Boolean =
        kind match
            case InputEvent.MouseKind.LeftPress =>
                val packed = ctx.getContentPosition(elem)
                if packed == -1L then return false
                val cx      = ctx.posX(packed)
                val value   = ValueResolver.resolveString(pi.value, ctx.signals)
                val scrollX = ctx.focus.scrollX(elem)
                val offset  = math.max(0, math.min(value.length, scrollX + (mx - cx)))
                ctx.focus.setCursorPos(elem, offset)
                true
            case _ => false
    end handleEditableMouse

end PickerW
