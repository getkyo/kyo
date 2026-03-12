package kyo.internal.tui2.widget

import kyo.*
import kyo.Maybe.*
import kyo.discard
import kyo.internal.tui2.*

/** Single lookup for widget dispatch — one match, one place. Adding a widget = implementing Widget + adding one case here.
  */
private[kyo] object WidgetRegistry:

    /** Returns the Widget for a given element type. */
    def lookup(elem: UI.Element): Widget =
        elem match
            case _: UI.TextInput => elem match
                    case _: UI.Textarea => TextareaWidget
                    case _              => TextInputWidget
            case _: UI.BooleanInput => CheckboxWidget
            case _: UI.RangeInput   => RangeWidget
            case _: UI.PickerInput => elem match
                    case _: UI.Select => SelectWidget
                    case _            => EditablePickerWidget
            case _: UI.FileInput => FileInputWidget
            case _: UI.Hr        => HrWidget
            case _: UI.Li        => LiWidget
            case _: UI.Table     => TableWidget
            case _: UI.Img       => ImgWidget
            case _: UI.Anchor    => AnchorWidget
            case _               => ContainerWidget

    /** Default content width for text inputs (excluding borders/padding). */
    private val DefaultInputWidth = 20

    // ---- Widget implementations ----

    private[widget] object ContainerWidget extends Widget:
        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit = Container.render(elem, cx, cy, cw, ch, rs, ctx)

        override def handleClick(elem: UI.Element, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            elem.attrs.onClick.fold(false) { a =>
                ValueResolver.runHandler(a)
                true
            }

        override def handleKey(elem: UI.Element, event: InputEvent.Key, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            if super.handleKey(elem, event, ctx) then true
            else
                event.key match
                    case UI.Keyboard.ArrowUp | UI.Keyboard.ArrowDown =>
                        val rs = ctx.measureRs
                        rs.inherit(ctx)
                        rs.applyProps(ctx.theme.styleFor(elem))
                        rs.applyProps(ValueResolver.resolveStyle(elem.attrs.uiStyle, ctx.signals))
                        if rs.overflow == 2 then
                            val sy    = ctx.getScrollY(elem)
                            val delta = if event.key == UI.Keyboard.ArrowUp then -1 else 1
                            ctx.setScrollY(elem, math.max(0, sy + delta))
                            true
                        else false
                        end if
                    case _ => false
    end ContainerWidget

    private object TextInputWidget extends Widget:
        override def measureWidth(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int  = DefaultInputWidth
        override def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = 1
        override def acceptsTextInput: Boolean                                                                   = true

        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            TextInputW.render(elem.asInstanceOf[UI.TextInput], elem, cx, cy, cw, ch, rs, ctx)

        override def handleKey(elem: UI.Element, event: InputEvent.Key, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            TextInputW.handleKey(elem.asInstanceOf[UI.TextInput], elem, event, ctx)

        override def handlePaste(elem: UI.Element, paste: String, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            TextInputW.handlePaste(elem.asInstanceOf[UI.TextInput], elem, paste, ctx)

        override def handleMouse(elem: UI.Element, kind: InputEvent.MouseKind, mx: Int, my: Int, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            TextInputW.handleMouse(elem.asInstanceOf[UI.TextInput], elem, kind, mx, my, ctx)
    end TextInputWidget

    private object TextareaWidget extends Widget:
        override def measureWidth(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int  = DefaultInputWidth
        override def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = 3
        override def acceptsTextInput: Boolean                                                                   = true

        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            TextInputW.render(elem.asInstanceOf[UI.TextInput], elem, cx, cy, cw, ch, rs, ctx)

        override def handleKey(elem: UI.Element, event: InputEvent.Key, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            TextInputW.handleKey(elem.asInstanceOf[UI.TextInput], elem, event, ctx)

        override def handlePaste(elem: UI.Element, paste: String, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            TextInputW.handlePaste(elem.asInstanceOf[UI.TextInput], elem, paste, ctx)

        override def handleMouse(elem: UI.Element, kind: InputEvent.MouseKind, mx: Int, my: Int, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            TextInputW.handleMouse(elem.asInstanceOf[UI.TextInput], elem, kind, mx, my, ctx)
    end TextareaWidget

    private object CheckboxWidget extends Widget:
        override def measureWidth(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int  = 3
        override def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = 1
        override def selfRendered: Boolean                                                                       = true

        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            CheckboxW.render(elem.asInstanceOf[UI.BooleanInput], cx, cy, cw, ch, rs, ctx)

        override def handleClick(elem: UI.Element, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            CheckboxW.toggle(elem.asInstanceOf[UI.BooleanInput], ctx)
    end CheckboxWidget

    private object RangeWidget extends Widget:
        override def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = 1
        override def selfRendered: Boolean                                                                       = true

        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            RangeW.render(elem.asInstanceOf[UI.RangeInput], cx, cy, cw, ch, rs, ctx)

        override def handleKey(elem: UI.Element, event: InputEvent.Key, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            RangeW.handleKey(elem.asInstanceOf[UI.RangeInput], event, ctx)

        override def handleMouse(elem: UI.Element, kind: InputEvent.MouseKind, mx: Int, my: Int, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            RangeW.handleMouse(elem.asInstanceOf[UI.RangeInput], elem, kind, mx, my, ctx)
    end RangeWidget

    private object SelectWidget extends Widget:
        override def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = 1
        override def measureWidth(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int =
            val sel  = elem.asInstanceOf[UI.Select]
            var maxW = 0
            var i    = 0
            while i < sel.children.size do
                sel.children(i) match
                    case opt: UI.Opt =>
                        val text = extractOptText(opt)
                        maxW = math.max(maxW, text.length)
                    case _ => ()
                end match
                i += 1
            end while
            maxW + 2 // text + " ▼"
        end measureWidth

        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            PickerW.render(elem.asInstanceOf[UI.PickerInput], elem, cx, cy, cw, ch, rs, ctx)

        override def handleClick(elem: UI.Element, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            DebugLog(s"[DBG] SelectWidget.handleClick called")
            PickerW.toggleExpanded(elem.asInstanceOf[UI.Select], ctx)
            true
        end handleClick

        override def handleKey(elem: UI.Element, event: InputEvent.Key, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            // When expanded, delegate to PickerW first (handles ArrowUp/Down/Enter/Escape)
            if PickerW.handleKey(elem.asInstanceOf[UI.PickerInput], elem, event, ctx) then true
            else super.handleKey(elem, event, ctx)
    end SelectWidget

    private object EditablePickerWidget extends Widget:
        override def measureWidth(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int  = DefaultInputWidth
        override def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = 1
        override def acceptsTextInput: Boolean                                                                   = true

        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            PickerW.renderEditable(elem.asInstanceOf[UI.PickerInput], elem, cx, cy, cw, ch, rs, ctx)

        override def handleKey(elem: UI.Element, event: InputEvent.Key, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            PickerW.handleEditableKey(elem.asInstanceOf[UI.PickerInput], elem, event, ctx)

        override def handleMouse(elem: UI.Element, kind: InputEvent.MouseKind, mx: Int, my: Int, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            PickerW.handleEditableMouse(elem.asInstanceOf[UI.PickerInput], elem, kind, mx, my, ctx)
    end EditablePickerWidget

    private object FileInputWidget extends Widget:
        override def measureWidth(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int  = DefaultInputWidth
        override def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = 1

        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            FileInputW.render(elem.asInstanceOf[UI.FileInput], cx, cy, cw, ch, rs, ctx)

        override def handleKey(elem: UI.Element, event: InputEvent.Key, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            FileInputW.handleKey(elem.asInstanceOf[UI.FileInput], event, ctx)
    end FileInputWidget

    private object HrWidget extends Widget:
        override def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = 1
        override def selfRendered: Boolean                                                                       = true

        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            HrW.render(cx, cy, cw, rs, ctx.canvas)
    end HrWidget

    private object LiWidget extends Widget:
        override def extraWidth(elem: UI.Element): Int = 2

        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            if ctx.listKind > 0 then
                val markerW =
                    if ctx.listKind == 1 then
                        discard(ctx.canvas.drawString(cx, cy, 2, "\u2022 ", 0, rs.cellStyle))
                        2
                    else
                        ctx.listIndex += 1
                        val marker = s"${ctx.listIndex}. "
                        discard(ctx.canvas.drawString(cx, cy, marker.length, marker, 0, rs.cellStyle))
                        marker.length
                if cw > markerW then
                    Container.render(elem, cx + markerW, cy, cw - markerW, ch, rs, ctx)
            else
                Container.render(elem, cx, cy, cw, ch, rs, ctx)
    end LiWidget

    private object TableWidget extends Widget:
        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            TableW.render(elem.asInstanceOf[UI.Table], cx, cy, cw, ch, rs, ctx)
    end TableWidget

    private object ImgWidget extends Widget:
        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit =
            ImgW.render(elem.asInstanceOf[UI.Img], cx, cy, cw, ch, rs, ctx.canvas, ctx)
    end ImgWidget

    private object AnchorWidget extends Widget:
        def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Unit = Container.render(elem, cx, cy, cw, ch, rs, ctx)

        override def handleClick(elem: UI.Element, ctx: RenderCtx)(
            using
            Frame,
            AllowUnsafe
        ): Boolean =
            val a   = elem.asInstanceOf[UI.Anchor]
            val url = ValueResolver.resolveStringSignal(a.href, ctx.signals)
            if url.nonEmpty then PlatformCmd.openBrowser(url)
            true
        end handleClick
    end AnchorWidget

    // ---- Helpers ----

    private def extractOptText(opt: UI.Opt): String =
        var text = ""
        var i    = 0
        while i < opt.children.size do
            opt.children(i) match
                case UI.Text(v) => text = text + v
                case _          => ()
            i += 1
        end while
        text
    end extractOptText

end WidgetRegistry
