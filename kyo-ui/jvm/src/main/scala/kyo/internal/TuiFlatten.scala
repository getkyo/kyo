package kyo.internal

import kyo.*
import kyo.Maybe.*

/** Walk UI AST, resolve signals inline, resolve styles -> TuiLayout flat arrays.
  *
  * 5-case match on UI: Text, Element, Reactive, Foreach, Fragment. No intermediate snapshot tree. Signals are resolved during traversal and
  * collected for awaitAny.
  */
private[kyo] object TuiFlatten:

    /** Read current signal value, bypassing computation allocation. */
    private inline def readSignal[A](s: Signal[A])(using Frame, AllowUnsafe): A =
        Sync.Unsafe.evalOrThrow(s.current)

    /** Flatten the UI tree into the layout's flat arrays. Resets layout and signals before starting. */
    def flatten(ui: UI, layout: TuiLayout, signals: TuiSignalCollector, parentW: Int, parentH: Int, theme: TuiResolvedTheme)(
        using
        Frame,
        AllowUnsafe
    ): Unit =
        layout.reset()
        signals.reset()
        flattenNode(ui, layout, signals, -1, parentW, parentH, theme)
    end flatten

    private def flattenNode(
        ui: UI,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        parentIdx: Int,
        parentW: Int,
        parentH: Int,
        theme: TuiResolvedTheme
    )(using Frame, AllowUnsafe): Unit =
        ui match
            case UI.Text(value) =>
                val idx = layout.alloc()
                TuiLayout.linkChild(layout, parentIdx, idx)
                TuiStyle.setDefaults(layout, idx)
                layout.text(idx) = Present(value)
                layout.nodeType(idx) = TuiLayout.NodeText.toByte

            case elem: UI.Element =>
                val idx = layout.alloc()
                TuiLayout.linkChild(layout, parentIdx, idx)
                resolveElement(elem, layout, signals, idx, parentW, parentH, theme)
                // Structural defaults for void elements
                elem match
                    case _: UI.Hr | _: UI.Br =>
                        if layout.sizeH(idx) < 0 then layout.sizeH(idx) = 1
                    case _ => ()
                end match
                // Hr stretches to fill parent width (like block elements in HTML)
                if elem.isInstanceOf[UI.Hr] then
                    layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.StretchBit)
                // Mark Tr inside Table for equal-width column layout
                if elem.isInstanceOf[UI.Tr] && parentIdx >= 0 then
                    val parentElem = layout.element(parentIdx)
                    if parentElem.isDefined && parentElem.get.isInstanceOf[UI.Table] then
                        layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.TableRowBit)
                end if
                elem.children.foreach(child =>
                    flattenNode(child, layout, signals, idx, parentW, parentH, theme)
                )
                resolveInputText(elem, layout, signals, idx)
                resolveImg(elem, layout, idx)
                resolveSelectOptions(elem, layout, signals, idx)

            case UI.Reactive(signal) =>
                signals.add(signal)
                val resolved = readSignal(signal)
                flattenNode(resolved, layout, signals, parentIdx, parentW, parentH, theme)

            // unsafe: asInstanceOf for erased type parameter
            case fe: UI.Foreach[?] @unchecked =>
                signals.add(fe.signal)
                val items  = readSignal(fe.signal)
                val render = fe.render.asInstanceOf[(Int, Any) => UI]
                val size   = items.size
                @scala.annotation.tailrec
                def loop(i: Int): Unit =
                    if i < size then
                        flattenNode(render(i, items(i)), layout, signals, parentIdx, parentW, parentH, theme)
                        loop(i + 1)
                loop(0)

            case UI.Fragment(children) =>
                children.foreach(child =>
                    flattenNode(child, layout, signals, parentIdx, parentW, parentH, theme)
                )
    end flattenNode

    private def resolveElement(
        elem: UI.Element,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int,
        parentW: Int,
        parentH: Int,
        theme: TuiResolvedTheme
    )(using Frame, AllowUnsafe): Unit =
        // Resolve style (may be Signal[Style])
        val userStyle: Style = elem.attrs.uiStyle match
            case s: Style => s
            case sig: Signal[?] =>
                signals.add(sig)
                // unsafe: asInstanceOf for union type resolution
                readSignal(sig.asInstanceOf[Signal[Style]])
        // Boolean inputs (Checkbox/Radio) don't get input borders from theme — they render as [x]/[ ] or (*)/( )
        val themeStyle = if elem.isInstanceOf[UI.BooleanInput] then Style.empty else theme.forElement(elem)
        TuiStyle.resolveWithTheme(themeStyle, userStyle, layout, idx, parentW, parentH)
        layout.element(idx) = Present(elem)
        layout.nodeType(idx) = 0 // non-text node
        if elem.isInstanceOf[UI.Inline] then
            layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.DirBit)
        // Table rows should stretch to fill table width
        if elem.isInstanceOf[UI.Table] then
            layout.lFlags(idx) = (layout.lFlags(idx) & ~(TuiLayout.AlignMask << TuiLayout.AlignShift)) |
                (TuiLayout.AlignStretch << TuiLayout.AlignShift)
        resolveHidden(elem.attrs.hidden, layout, signals, idx)
        // Resolve disabled from HasDisabled elements
        elem match
            case hd: UI.HasDisabled => resolveDisabled(hd.disabled, layout, signals, idx)
            case _                  => ()
        // Set colspan/rowspan for table cells
        elem match
            case td: UI.Td =>
                if td.colspan.isDefined then layout.colspan(idx) = math.max(1, td.colspan.get)
                if td.rowspan.isDefined then layout.rowspan(idx) = math.max(1, td.rowspan.get)
            case th: UI.Th =>
                if th.colspan.isDefined then layout.colspan(idx) = math.max(1, th.colspan.get)
                if th.rowspan.isDefined then layout.rowspan(idx) = math.max(1, th.rowspan.get)
            case _ => ()
        end match
    end resolveElement

    private val DefaultInputWidth = 20

    /** Password mask character. */
    private val PasswordMask = '\u2022' // •

    private def resolveInputText(
        elem: UI.Element,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int
    )(using Frame, AllowUnsafe): Unit =
        elem match
            case bi: UI.BooleanInput =>
                resolveCheckbox(bi.checked, layout, signals, idx, bi.isInstanceOf[UI.Radio])
            case ti: UI.TextInput =>
                val rawValue = ti.value match
                    case Present(s: String) => s
                    // unsafe: asInstanceOf for union type resolution
                    case Present(sr: SignalRef[?]) => signals.add(sr); readSignal(sr.asInstanceOf[Signal[String]])
                    case _                         => ""
                val isPassword = elem.isInstanceOf[UI.Password]
                val displayText =
                    if rawValue.nonEmpty then
                        if isPassword then maskPassword(rawValue.length) else rawValue
                    else ti.placeholder.getOrElse("")
                val child = layout.alloc()
                TuiLayout.linkChild(layout, idx, child)
                TuiStyle.setDefaults(layout, child)
                layout.text(child) = Present(displayText)
                layout.nodeType(child) = TuiLayout.NodeText.toByte
                if layout.sizeW(idx) < 0 then
                    // Account for borders and padding added by theme
                    val lf = layout.lFlags(idx)
                    val insetW = layout.padL(idx) + layout.padR(idx) +
                        (if TuiLayout.hasBorderL(lf) then 1 else 0) + (if TuiLayout.hasBorderR(lf) then 1 else 0)
                    layout.sizeW(idx) = DefaultInputWidth + insetW
                end if
                if layout.sizeH(idx) < 0 then
                    // Textarea gets 3 lines, Input gets 1 (plus borders and padding from theme)
                    val lf = layout.lFlags(idx)
                    val insetH = layout.padT(idx) + layout.padB(idx) +
                        (if TuiLayout.hasBorderT(lf) then 1 else 0) + (if TuiLayout.hasBorderB(lf) then 1 else 0)
                    val contentH = if elem.isInstanceOf[UI.Textarea] then 3 else 1
                    layout.sizeH(idx) = contentH + insetH
                end if
                // Single-line text inputs scroll text horizontally, not wrap
                if !elem.isInstanceOf[UI.Textarea] then
                    layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.NoWrapBit)
                // Textarea scrolls overflow (wrapped text may exceed fixed content height)
                if elem.isInstanceOf[UI.Textarea] then
                    layout.lFlags(idx) = (layout.lFlags(idx) & ~(TuiLayout.OverflowMask << TuiLayout.OverflowShift)) |
                        (2 << TuiLayout.OverflowShift) // 2 = scroll
            case pi: UI.PickerInput =>
                val rawValue = pi.value match
                    case Present(s: String)        => s
                    case Present(sr: SignalRef[?]) => signals.add(sr); readSignal(sr.asInstanceOf[Signal[String]])
                    case _                         => ""
                if rawValue.nonEmpty then
                    val child = layout.alloc()
                    TuiLayout.linkChild(layout, idx, child)
                    TuiStyle.setDefaults(layout, child)
                    layout.text(child) = Present(rawValue)
                    layout.nodeType(child) = TuiLayout.NodeText.toByte
                end if
                if layout.sizeW(idx) < 0 then
                    val lf = layout.lFlags(idx)
                    val insetW = layout.padL(idx) + layout.padR(idx) +
                        (if TuiLayout.hasBorderL(lf) then 1 else 0) + (if TuiLayout.hasBorderR(lf) then 1 else 0)
                    layout.sizeW(idx) = DefaultInputWidth + insetW
                end if
                if layout.sizeH(idx) < 0 then
                    val lf = layout.lFlags(idx)
                    val insetH = layout.padT(idx) + layout.padB(idx) +
                        (if TuiLayout.hasBorderT(lf) then 1 else 0) + (if TuiLayout.hasBorderB(lf) then 1 else 0)
                    layout.sizeH(idx) = 1 + insetH
                end if
                layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.NoWrapBit)
            case ri: UI.RangeInput =>
                val rawValue = ri.value match
                    case Present(d: Double)        => d.toString
                    case Present(sr: SignalRef[?]) => signals.add(sr); readSignal(sr.asInstanceOf[Signal[Double]]).toString
                    case _                         => ""
                if rawValue.nonEmpty then
                    val child = layout.alloc()
                    TuiLayout.linkChild(layout, idx, child)
                    TuiStyle.setDefaults(layout, child)
                    layout.text(child) = Present(rawValue)
                    layout.nodeType(child) = TuiLayout.NodeText.toByte
                end if
                if layout.sizeW(idx) < 0 then
                    val lf = layout.lFlags(idx)
                    val insetW = layout.padL(idx) + layout.padR(idx) +
                        (if TuiLayout.hasBorderL(lf) then 1 else 0) + (if TuiLayout.hasBorderR(lf) then 1 else 0)
                    layout.sizeW(idx) = DefaultInputWidth + insetW
                end if
                if layout.sizeH(idx) < 0 then
                    val lf = layout.lFlags(idx)
                    val insetH = layout.padT(idx) + layout.padB(idx) +
                        (if TuiLayout.hasBorderT(lf) then 1 else 0) + (if TuiLayout.hasBorderB(lf) then 1 else 0)
                    layout.sizeH(idx) = 1 + insetH
                end if
                layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.NoWrapBit)
            case _: UI.FileInput =>
                if layout.sizeW(idx) < 0 then
                    val lf = layout.lFlags(idx)
                    val insetW = layout.padL(idx) + layout.padR(idx) +
                        (if TuiLayout.hasBorderL(lf) then 1 else 0) + (if TuiLayout.hasBorderR(lf) then 1 else 0)
                    layout.sizeW(idx) = DefaultInputWidth + insetW
                end if
                if layout.sizeH(idx) < 0 then
                    val lf = layout.lFlags(idx)
                    val insetH = layout.padT(idx) + layout.padB(idx) +
                        (if TuiLayout.hasBorderT(lf) then 1 else 0) + (if TuiLayout.hasBorderB(lf) then 1 else 0)
                    layout.sizeH(idx) = 1 + insetH
                end if
            case hi: UI.HiddenInput =>
                // Hidden inputs are not visible
                layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.HiddenBit)
            case _ => ()
    end resolveInputText

    /** Create a password mask string of the given length. */
    private def maskPassword(len: Int): String =
        val sb = new java.lang.StringBuilder(len)
        @scala.annotation.tailrec
        def loop(i: Int): String =
            if i >= len then sb.toString
            else
                sb.append(PasswordMask)
                loop(i + 1)
        loop(0)
    end maskPassword

    /** Render checkbox as `[x]`/`[ ]` or radio as `(*)`/`( )` based on checked state. */
    private def resolveCheckbox(
        checked: Maybe[Boolean | Signal[Boolean]],
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int,
        isRadio: Boolean = false
    )(using Frame, AllowUnsafe): Unit =
        val isChecked = resolveChecked(checked, signals)
        val text =
            if isRadio then (if isChecked then "(*)" else "( )")
            else (if isChecked then "[x]" else "[ ]")
        val child = layout.alloc()
        TuiLayout.linkChild(layout, idx, child)
        TuiStyle.setDefaults(layout, child)
        layout.text(child) = Present(text)
        layout.nodeType(child) = TuiLayout.NodeText.toByte
        if layout.sizeW(idx) < 0 then
            layout.sizeW(idx) = 3
        if layout.sizeH(idx) < 0 then
            layout.sizeH(idx) = 1
    end resolveCheckbox

    /** Resolve a checked value (static or signal) to Boolean. */
    private def resolveChecked(checked: Maybe[Boolean | Signal[Boolean]], signals: TuiSignalCollector)(
        using
        Frame,
        AllowUnsafe
    ): Boolean =
        if checked.isEmpty then false
        else
            checked.get match
                case b: Boolean =>
                    b
                case s: Signal[?] =>
                    signals.add(s)
                    // unsafe: asInstanceOf for union type resolution
                    readSignal(s.asInstanceOf[Signal[Boolean]])

    /** For Img elements with no children, render alt text as a text child. */
    private def resolveImg(
        elem: UI.Element,
        layout: TuiLayout,
        idx: Int
    ): Unit =
        elem match
            case img: UI.Img if layout.firstChild(idx) == -1 =>
                // Display alt text (TUI cannot render images — always fall back to alt text)
                val altText = img.alt.getOrElse("[img]")
                val child   = layout.alloc()
                TuiLayout.linkChild(layout, idx, child)
                TuiStyle.setDefaults(layout, child)
                layout.text(child) = Present(altText)
                layout.nodeType(child) = TuiLayout.NodeText.toByte
            case _ => ()
    end resolveImg

    /** For Select elements, mark child Opt elements with selected indicator. */
    private def resolveSelectOptions(
        elem: UI.Element,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int
    )(using Frame, AllowUnsafe): Unit =
        if elem.isInstanceOf[UI.Select] then
            // Walk children: for each Opt child, prepend selected indicator
            @scala.annotation.tailrec
            def walkChildren(childIdx: Int): Unit =
                if childIdx >= 0 then
                    val childElem = layout.element(childIdx)
                    if childElem.isDefined then
                        childElem.get match
                            case opt: UI.Opt =>
                                val isSelected = resolveChecked(opt.selected, signals)
                                if isSelected then
                                    // Prefix the first text child with "▶ "
                                    val textIdx = layout.firstChild(childIdx)
                                    if textIdx >= 0 && layout.text(textIdx).isDefined then
                                        layout.text(textIdx) = Present("\u25b6 " + layout.text(textIdx).get)
                                end if
                            case _ => ()
                    end if
                    walkChildren(layout.nextSibling(childIdx))
            walkChildren(layout.firstChild(idx))
    end resolveSelectOptions

    private def resolveHidden(
        hidden: Maybe[Boolean | Signal[Boolean]],
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int
    )(using Frame, AllowUnsafe): Unit =
        if !hidden.isEmpty then
            hidden.get match
                case b: Boolean =>
                    if b then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.HiddenBit)
                case s: Signal[?] =>
                    signals.add(s)
                    // unsafe: asInstanceOf for union type resolution
                    val b = readSignal(s.asInstanceOf[Signal[Boolean]])
                    if b then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.HiddenBit)

    private def resolveDisabled(
        disabled: Maybe[Boolean | Signal[Boolean]],
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int
    )(using Frame, AllowUnsafe): Unit =
        if !disabled.isEmpty then
            disabled.get match
                case b: Boolean =>
                    if b then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.DisabledBit)
                case s: Signal[?] =>
                    signals.add(s)
                    // unsafe: asInstanceOf for union type resolution
                    val b = readSignal(s.asInstanceOf[Signal[Boolean]])
                    if b then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.DisabledBit)

end TuiFlatten
